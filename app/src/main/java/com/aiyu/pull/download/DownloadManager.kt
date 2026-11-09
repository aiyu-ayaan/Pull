package com.aiyu.pull.download

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.aiyu.pull.data.DownloadDao
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.data.Status
import com.aiyu.pull.data.encodeSegments
import com.aiyu.pull.engine.DownloadException
import com.aiyu.pull.engine.DownloadRequest
import com.aiyu.pull.engine.DownloadTask
import com.aiyu.pull.engine.ErrorKind
import com.aiyu.pull.engine.RemoteInfo
import com.aiyu.pull.engine.SegmentProgress
import com.aiyu.pull.engine.probe
import com.aiyu.pull.service.DownloadService
import com.aiyu.pull.service.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory numbers for a running download, refreshed 4 times a second. Never per byte. */
data class LiveStats(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val segments: List<SegmentProgress>,
    /** Speed once a second, oldest first, last 60 s. */
    val history: List<Long>,
    val peakBytesPerSecond: Long,
)

/**
 * Owns every download's lifecycle. Room is the source of truth: a process death loses at most the last second of
 * progress, and [restore] picks interrupted downloads up again on the next start.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val client: OkHttpClient,
    private val files: FileStore,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val notifier: Notifier,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Download manager failure", e) },
    )

    private class Running(val task: DownloadTask, val job: Job) {
        /** What a cancel means: a pause, or a restart that re-queues. */
        @Volatile var stopAs = Status.PAUSED
        val history = ArrayDeque<Long>()
        var peak = 0L
        var lastSave = SystemClock.elapsedRealtime()
    }

    private val running = ConcurrentHashMap<Long, Running>()
    private val scheduleLock = Mutex()
    private val saveLock = Mutex()
    private val _active = MutableStateFlow(0)
    private val _live = MutableStateFlow<Map<Long, LiveStats>>(emptyMap())
    @Volatile private var restored = false

    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()
    fun download(id: Long): Flow<DownloadEntity?> = dao.observe(id)

    /** Number of downloads moving bytes right now. The service lives exactly as long as this is above 0. */
    val activeCount: StateFlow<Int> = _active.asStateFlow()
    val live: StateFlow<Map<Long, LiveStats>> = _live.asStateFlow()

    init {
        scope.launch { sampleLive() }
    }

    /** Called once per process. Rows still RUNNING were cut off by a process death, reboot or crash. */
    fun restore() {
        if (restored) return
        restored = true
        scope.launch {
            val autoResume = settings.current().autoResume
            dao.moveAll(Status.RUNNING, if (autoResume) Status.QUEUED else Status.PAUSED)
            schedule()
        }
    }

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): RemoteInfo = probe(client, url, headers)

    suspend fun add(
        url: String,
        fileName: String? = null,
        headers: Map<String, String> = emptyMap(),
        pageUrl: String? = null,
        connections: Int? = null,
        start: Boolean = true,
    ): Long {
        val entity = DownloadEntity(
            url = url,
            pageUrl = pageUrl,
            headersJson = DownloadEntity.encodeHeaders(headers),
            fileName = fileName?.trim()?.takeIf { it.isNotEmpty() } ?: nameFromUrl(url),
            maxConnections = connections ?: settings.current().connections,
            status = if (start) Status.QUEUED else Status.PAUSED,
        )
        val id = dao.insert(entity)
        if (start) schedule()
        return id
    }

    fun pause(id: Long) = scope.launch {
        val r = running[id]
        if (r != null) {
            r.stopAs = Status.PAUSED
            r.job.cancelAndJoin()
        } else if (dao.get(id)?.status == Status.QUEUED) {
            dao.setStatus(id, Status.PAUSED)
        }
    }

    fun resume(id: Long) = scope.launch {
        val e = dao.get(id) ?: return@launch
        if (e.status == Status.PAUSED || e.status == Status.FAILED) {
            dao.setStatus(id, Status.QUEUED)
            schedule()
        }
    }

    fun pauseAll() = scope.launch {
        dao.moveAll(Status.QUEUED, Status.PAUSED)
        running.values.forEach { it.stopAs = Status.PAUSED; it.job.cancel() }
    }

    fun resumeAll() = scope.launch {
        dao.moveAll(Status.PAUSED, Status.QUEUED)
        schedule()
    }

    /** Throws the partial file away and downloads again from byte 0. */
    fun restart(id: Long) = scope.launch {
        stop(id)
        val e = dao.get(id) ?: return@launch
        e.fileUri?.let { files.delete(files.parse(it)) }
        dao.update(
            e.copy(
                status = Status.QUEUED, fileUri = null, finalUrl = null, etag = null, lastModified = null, segments = "",
                downloadedBytes = 0, totalBytes = -1, acceptRanges = false, errorKind = null, error = null, activeMillis = 0,
            ),
        )
        schedule()
    }

    /**
     * Swaps in a fresh link for the same file (FDM-style "refresh download address") and resumes. The engine checks
     * size and ETag on the first response, so a link to a different file fails with FILE_CHANGED instead of mixing bytes.
     */
    fun refreshLink(id: Long, url: String, headers: Map<String, String>? = null, pageUrl: String? = null) = scope.launch {
        stop(id)
        val e = dao.get(id) ?: return@launch
        dao.update(
            e.copy(
                url = url,
                finalUrl = if (e.finalUrl != null) url else null,
                headersJson = headers?.let { DownloadEntity.encodeHeaders(e.headers + it) } ?: e.headersJson,
                pageUrl = pageUrl ?: e.pageUrl,
                status = Status.QUEUED, errorKind = null, error = null,
            ),
        )
        schedule()
    }

    /** Removes the row. Unfinished files are always deleted; finished ones only when [withFile]. */
    fun delete(id: Long, withFile: Boolean) = scope.launch {
        stop(id)
        val e = dao.get(id) ?: return@launch
        dao.delete(id)
        if (withFile || e.status != Status.COMPLETED) e.fileUri?.let { files.delete(files.parse(it)) }
        notifier.cancel(id)
    }

    private suspend fun stop(id: Long) {
        running[id]?.let { it.stopAs = Status.PAUSED; it.job.cancelAndJoin() }
    }

    private fun schedule() {
        scope.launch {
            scheduleLock.withLock {
                val max = settings.current().maxDownloads
                for (e in dao.withStatus(Status.QUEUED)) {
                    if (running.size >= max) break
                    if (!running.containsKey(e.id)) start(e)
                }
            }
            if (running.isNotEmpty()) startService()
        }
    }

    private suspend fun start(e: DownloadEntity) {
        dao.setStatus(e.id, Status.RUNNING)
        val task = DownloadTask(
            client = client,
            request = DownloadRequest(e.url, e.headers, e.maxConnections, resume = e.resumeData()),
            openFile = { info -> openFile(e.id, info) },
            awaitNetwork = network::awaitConnected,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(e.id, task) }
        running[e.id] = Running(task, job)
        _active.value = running.size
        job.start()
    }

    private suspend fun CoroutineScope.execute(id: Long, task: DownloadTask) {
        val flusher = launch { while (true) { delay(1_000); save(id) } }
        try {
            val info = task.run()
            flusher.cancelAndJoin()
            save(id)
            val e = dao.get(id) ?: return
            e.fileUri?.let { files.publish(files.parse(it)) }
            dao.finish(id, Status.COMPLETED, null, null, System.currentTimeMillis())
            notifier.completed(id, e.fileName, info.totalBytes)
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                flusher.cancelAndJoin()
                save(id)
                dao.finish(id, running[id]?.stopAs ?: Status.PAUSED, null, null, null)
            }
            throw c
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                flusher.cancelAndJoin()
                save(id)
                val kind = (t as? DownloadException)?.kind?.name ?: if (t is IOException) ErrorKind.DISK.name else "UNKNOWN"
                dao.finish(id, Status.FAILED, kind, t.message ?: t.javaClass.simpleName, null)
                dao.get(id)?.let { notifier.failed(id, it.fileName, kind == ErrorKind.LINK_EXPIRED.name) }
            }
            if (t !is DownloadException) Log.w(TAG, "Download $id failed", t)
        } finally {
            running.remove(id)
            _active.value = running.size
            schedule()
        }
    }

    /** First byte is about to be written: create the file (named after the server's answer) or reopen it. */
    private suspend fun openFile(id: Long, info: RemoteInfo): java.nio.channels.FileChannel {
        val e = dao.get(id) ?: throw IOException("Download removed")
        val existing = e.fileUri?.let(files::parse)
        if (existing != null) {
            // Bytes on disk are the resume state; a fresh file would pass silent zeros off as downloaded data.
            if (!files.exists(existing)) throw IOException("The file was moved or deleted. Restart the download.")
            return files.open(existing, info.totalBytes)
        }
        val name = if (e.fileName == nameFromUrl(e.url)) info.fileName ?: e.fileName else e.fileName
        val (uri, realName) = files.create(name, info.mimeType)
        dao.setFile(id, uri.toString(), realName, info.mimeType)
        return files.open(uri, info.totalBytes)
    }

    private suspend fun save(id: Long) = saveLock.withLock {
        val r = running[id] ?: return@withLock
        val now = SystemClock.elapsedRealtime()
        val delta = now - r.lastSave
        r.lastSave = now
        val snap = r.task.snapshot()
        if (snap == null) {
            dao.addActiveTime(id, delta)
            return@withLock
        }
        val i = snap.info
        val remaining = snap.segments.sumOf { (it.end - it.current + 1).coerceAtLeast(0) }
        val downloaded = if (i.totalBytes >= 0) i.totalBytes - remaining else r.task.progress.value.downloadedBytes
        dao.saveProgress(
            id, downloaded, encodeSegments(snap.segments), i.finalUrl, i.totalBytes, i.etag, i.lastModified, i.acceptRanges, delta,
        )
    }

    private suspend fun sampleLive() {
        var tick = 0
        while (true) {
            if (running.isEmpty()) {
                _live.value = emptyMap()
                _active.first { it > 0 } // sleep while idle instead of ticking 4 times a second
            }
            delay(250)
            tick++
            _live.value = running.mapValues { (_, r) ->
                val p = r.task.progress.value
                if (tick % 4 == 0) {
                    r.history.addLast(p.bytesPerSecond)
                    if (r.history.size > 60) r.history.removeFirst()
                }
                r.peak = maxOf(r.peak, p.bytesPerSecond)
                LiveStats(p.downloadedBytes, p.info?.totalBytes ?: -1, p.bytesPerSecond, p.segments, r.history.toList(), r.peak)
            }
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: Exception) {
            // Android 12+ refuses foreground starts from the background (e.g. a restart nobody opened). Downloads still
            // run while the process lives; the service starts with the next foreground start.
            Log.w(TAG, "Foreground service not started", e)
        }
    }

    companion object {
        private const val TAG = "DownloadManager"

        fun nameFromUrl(url: String): String =
            url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                .replace(Regex("""[\u0000-\u001f<>:"/\\|?*]"""), "_")
                .ifBlank { "download" }
    }
}
