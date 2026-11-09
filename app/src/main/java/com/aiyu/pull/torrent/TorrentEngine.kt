package com.aiyu.pull.torrent

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.aiyu.pull.data.DownloadDao
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.download.FileStore
import com.aiyu.pull.download.PeerLive
import com.aiyu.pull.download.TorrentCommand
import com.aiyu.pull.download.TorrentFileLive
import com.aiyu.pull.download.TrackerLive
import com.aiyu.pull.download.TorrentLive
import com.aiyu.pull.download.Transfer
import com.aiyu.pull.engine.Progress
import com.aiyu.pull.engine.RemoteInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What a .torrent or magnet contains, for the add sheet. */
data class TorrentMeta(val name: String, val infoHash: String, val totalBytes: Long, val files: List<Pair<String, Long>>, val bytes: ByteArray)

/**
 * One libtorrent session for the whole app, started on first use. Pull downloads torrents like any other
 * download: it stops when the chosen files are complete (no background seeding yet).
 */
@Singleton
class TorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val files: FileStore,
) {
    val session = SessionManager(false)
    private val lock = Any()
    private val torrentsDir = File(context.filesDir, "torrents").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "magnet").apply { mkdirs() }

    fun ensureStarted() = synchronized(lock) {
        if (session.isRunning) return
        val pack = SettingsPack().apply {
            setEnableDht(true)
            setEnableLsd(true)
            connectionsLimit(200)
            activeDownloads(10)
        }
        session.start(SessionParams(pack))
    }

    fun parse(bytes: ByteArray): TorrentMeta {
        val ti = try { TorrentInfo(bytes) } catch (e: Throwable) { throw IOException("Not a valid torrent file", e) }
        val fs = ti.files()
        return TorrentMeta(
            name = ti.name(),
            infoHash = ti.infoHash().toHex(),
            totalBytes = ti.totalSize(),
            files = List(fs.numFiles()) { fs.filePath(it) to fs.fileSize(it) },
            bytes = bytes,
        )
    }

    /** Asks peers (DHT, trackers in the link) for the torrent's metadata. Null on timeout. */
    suspend fun fetchMagnet(uri: String, timeoutSeconds: Int = 30): ByteArray? = withContext(Dispatchers.IO) {
        ensureStarted()
        // Wait for some DHT nodes first, or the fetch starts blind.
        repeat(20) { if (session.dhtNodes() > 10) return@repeat; delay(500) }
        session.fetchMagnet(uri, timeoutSeconds, tempDir)
    }

    fun store(meta: TorrentMeta): File = File(torrentsDir, "${meta.infoHash}.torrent").apply { writeBytes(meta.bytes) }

    /**
     * Public Download/Pull on Android 11+ (direct file access to Downloads is allowed there) and on 10 with legacy
     * storage; the app's own Downloads folder on 8-9, which needs no storage permission.
     */
    fun defaultSaveDir(): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Pull")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Pull")
        }
        return dir.apply { mkdirs() }
    }

    fun transfer(e: DownloadEntity): Transfer = TorrentTransfer(e.id, this, dao, files, context)
}

private class TorrentTransfer(
    private val id: Long,
    private val engine: TorrentEngine,
    private val dao: DownloadDao,
    private val files: FileStore,
    private val context: Context,
) : Transfer {
    private val _progress = MutableStateFlow(Progress(null, 0, 0, emptyList()))
    private val _torrent = MutableStateFlow<TorrentLive?>(null)
    override val progress: StateFlow<Progress> = _progress
    override val torrent: StateFlow<TorrentLive?> = _torrent
    @Volatile private var root: File? = null
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var count = 0
    @Volatile private var paths: List<File> = emptyList()
    @Volatile private var about = Triple("", "", 0L)

    override suspend fun run(): Long = withContext(Dispatchers.IO) {
        engine.ensureStarted()
        val e = dao.get(id) ?: throw IOException("Download removed")
        val file = e.torrentPath?.let(::File)?.takeIf { it.exists() } ?: fetchMetadata(e)
        val ti = TorrentInfo(file)
        val saveDir = File(e.saveDir ?: engine.defaultSaveDir().path).apply { mkdirs() }
        val selected = e.selectedFiles.split(',').mapNotNull(String::toIntOrNull).toSet()
        val fs = ti.files()
        val priorities = Array(ti.numFiles()) { if (selected.isEmpty() || it in selected) Priority.DEFAULT else Priority.IGNORE }
        val top = File(saveDir, if (ti.numFiles() == 1) fs.filePath(0) else ti.name())
        root = top
        paths = (0 until ti.numFiles()).filter { selected.isEmpty() || it in selected }.map { File(saveDir, fs.filePath(it)) }
        if (e.fileUri == null) dao.setFile(id, Uri.fromFile(top).toString(), ti.name(), null)

        val flags = if (e.sequential) TorrentFlags.SEQUENTIAL_DOWNLOAD else torrent_flags_t()
        // Re-adding after a pause or process death makes libtorrent re-check what is on disk and continue from there.
        engine.session.download(ti, saveDir, null, priorities, null, flags)
        var found: TorrentHandle? = null
        repeat(50) { found = engine.session.find(ti.infoHash())?.takeIf { it.isValid }; if (found != null) return@repeat; delay(100) }
        val h = found ?: throw IOException("Torrent session did not accept the torrent")
        this@TorrentTransfer.handle = h
        count = ti.numFiles()
        about = Bencode.about(file.readBytes())
        Bencode.webSeeds(file.readBytes()).forEach { url -> if (url !in h.urlSeeds()) h.addUrlSeed(url) }
        e.extraTrackers.lines().map(String::trim).filter { it.isNotEmpty() }.forEach { h.addTracker(AnnounceEntry(it)) }
        h.setDownloadLimit(e.downLimit)
        h.setUploadLimit(e.upLimit)
        h.resume()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val st = h.status(TorrentHandle.QUERY_PIECES)
                publishLive(h, ti, st, e.url)
                if (st.totalWanted() > 0 && st.totalWantedDone() >= st.totalWanted() &&
                    st.state() != TorrentStatus.State.CHECKING_FILES && st.state() != TorrentStatus.State.CHECKING_RESUME_DATA
                ) return@withContext st.totalWanted()
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") 0L
        } finally {
            withContext(NonCancellable) { runCatching { engine.session.remove(h) } }
        }
    }

    private suspend fun fetchMetadata(e: DownloadEntity): File {
        _torrent.value = TorrentLive("metadata", 0, 0, 0, 0, BooleanArray(0), emptyList())
        while (true) {
            currentCoroutineContext().ensureActive()
            val bytes = engine.fetchMagnet(e.url)
            if (bytes != null) {
                val meta = engine.parse(bytes)
                val file = engine.store(meta)
                dao.setTorrent(id, file.path, meta.infoHash, meta.name, meta.totalBytes)
                return file
            }
        }
    }

    private fun publishLive(h: TorrentHandle, ti: TorrentInfo, st: TorrentStatus, url: String) {
        val total = st.totalWanted()
        _progress.value = Progress(
            RemoteInfo(url, total, true, null, null, ti.name(), null),
            st.totalWantedDone(),
            st.downloadPayloadRate().toLong(),
            emptyList(),
        )
        val bits = st.pieces()
        val n = bits.size()
        val buckets = minOf(n, 1024)
        val pieces = BooleanArray(buckets) { b ->
            val from = b * n / buckets
            val to = ((b + 1) * n / buckets).coerceAtLeast(from + 1)
            (from until to).all { bits.getBit(it) }
        }
        val fs = ti.files()
        val done = runCatching { h.fileProgress() }.getOrNull()
        val prio = runCatching { h.filePriorities() }.getOrNull()
        val trackers = runCatching { h.trackers() }.getOrNull().orEmpty().map { t ->
            // A tracker has one endpoint per local interface; the best one tells its state.
            val infos = t.endpoints().map { it.infohashV1() }
            TrackerLive(
                url = t.url(), tier = t.tier(),
                working = infos.any { it.isWorking() }, updating = infos.any { it.updating() },
                fails = infos.maxOfOrNull { it.fails().toInt() } ?: 0,
                message = infos.firstNotNullOfOrNull { it.message().takeIf(String::isNotBlank) }.orEmpty(),
            )
        }
        val peers = runCatching { h.peerInfo() }.getOrNull().orEmpty().map { p ->
            PeerLive(p.ip(), p.client(), p.downSpeed().toLong(), p.upSpeed().toLong(), p.progress(), p.totalDownload(), p.totalUpload())
        }
        _torrent.value = TorrentLive(
            state = st.state().name.lowercase(),
            peers = st.numPeers(),
            seeds = st.numSeeds(),
            uploadBytesPerSecond = st.uploadPayloadRate().toLong(),
            uploaded = st.allTimeUpload(),
            pieces = pieces,
            files = List(fs.numFiles()) {
                TorrentFileLive(fs.filePath(it), fs.fileSize(it), done?.getOrNull(it) ?: 0, prio?.getOrNull(it) != Priority.IGNORE)
            },
            trackers = trackers,
            peerList = peers,
            pieceCount = n,
            pieceSize = ti.pieceLength(),
            piecesHave = (0 until n).count { bits.getBit(it) },
            swarmSeeds = st.numComplete(),
            swarmPeers = st.numIncomplete(),
            availability = st.distributedCopies(),
            downloadedTotal = st.allTimeDownload(),
            sequential = h.flags.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero(),
            downLimit = h.downloadLimit,
            upLimit = h.uploadLimit,
            savePath = h.savePath(),
            comment = about.first,
            creator = about.second,
            createdAt = about.third,
        )
    }

    override fun updateFiles(selected: Set<Int>) {
        val h = handle?.takeIf { it.isValid } ?: return
        h.prioritizeFiles(Array(count) { if (selected.isEmpty() || it in selected) Priority.DEFAULT else Priority.IGNORE })
    }

    override fun torrentCommand(c: TorrentCommand) {
        val h = handle?.takeIf { it.isValid } ?: return
        when (c) {
            TorrentCommand.Recheck -> h.forceRecheck()
            TorrentCommand.Reannounce -> { h.forceReannounce(); h.forceDHTAnnounce() }
            is TorrentCommand.Sequential -> if (c.on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            is TorrentCommand.Limits -> { h.setDownloadLimit(c.down); h.setUploadLimit(c.up) }
            is TorrentCommand.AddTrackers -> c.urls.forEach { h.addTracker(AnnounceEntry(it)) }
        }
    }

    override suspend fun save(activeDelta: Long) {
        val p = _progress.value
        val total = p.info?.totalBytes ?: -1
        if (total > 0) dao.saveSimpleProgress(id, p.downloadedBytes, total, activeDelta) else dao.addActiveTime(id, activeDelta)
    }

    override suspend fun publish() {
        val list = paths.filter { it.exists() }.map { it.path }.toTypedArray()
        if (list.isNotEmpty()) MediaScannerConnection.scanFile(context, list, null, null)
    }
}
