package com.aiyu.pull.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * One download: probe, split, work-steal, retry, resume. See development/docs/03-download-engine.md.
 *
 * [run] returns when every byte is on disk and throws [DownloadException] on a fatal error. Pause = cancel the
 * coroutine running [run], then save [snapshot]; a later task built with that [ResumeData] continues from it.
 *
 * [openFile] gets the server's answer before the first byte is written, so the caller can name the file.
 */
class DownloadTask(
    private val client: OkHttpClient,
    private val request: DownloadRequest,
    private val openFile: suspend (RemoteInfo) -> FileChannel,
    private val awaitNetwork: suspend () -> Unit = {},
    private val maxRetries: Int = 10,
    private val retryBaseMillis: Long = 1_000,
    private val bufferBytes: Int = 256 * 1024,
) {
    private class Segment(val start: Long, end: Long, current: Long) {
        val end = AtomicLong(end)
        val current = AtomicLong(current)
        @Volatile var active = false
        @Volatile var retries = 0
        @Volatile var activeSinceMs = 0L
        @Volatile var activeCall: Call? = null
        @Volatile var racerCall: Call? = null
        @Volatile var racing = false
        var slowTicks = 0
        var sampledAt = current
        var speed = 0.0
        val remaining: Long
            get() {
                val e = end.get()
                return if (e == Long.MAX_VALUE) Long.MAX_VALUE else (e - current.get() + 1).coerceAtLeast(0)
            }
    }

    private val segments = CopyOnWriteArrayList<Segment>()
    private val queue = ArrayDeque<Segment>()
    private val lock = Any()
    private val refreshLock = Mutex()
    private val liveCalls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var cancelled = false
    @Volatile private var workersActive = false
    @Volatile private var info: RemoteInfo? = request.resume?.info
    private lateinit var channel: FileChannel
    private lateinit var workerScope: CoroutineScope
    private var totalSpeed = 0.0
    /** Workers holding a segment. Guarded by [lock], so a range given back is never left without a worker. */
    private var alive = 0
    private var refusalCount = 0
    private var lastRefusalMs = 0L
    private var refusalCooldownMs = RECOVERY_BASE_MS
    private var lastDownloaded = -1L

    init {
        // A download first seen as non-resumable keeps its bytes too: the next request asks for the offset and the
        // server's answer decides (206 continues, 200 fails; never a silent restart from 0).
        val ranged = request.resume?.info?.let { it.acceptRanges && it.totalBytes > 0 } == true
        request.resume?.segments?.filter { !it.done && (ranged || it.current > it.start) }?.forEach { s ->
            val seg = Segment(s.start, s.end, s.current)
            segments += seg
            queue += seg
        }
    }

    private val _progress = MutableStateFlow(Progress(info, downloaded(), 0, emptyList()))

    /** Sampled every 250 ms while [run] is active. */
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Unfinished ranges plus what the server said; null before the probe answered. Safe to call any time. */
    fun snapshot(): ResumeData? {
        val i = info ?: return null
        val open = segments.map { SegmentState(it.start, it.end.get(), it.current.get()) }.filter { !it.done }
        return ResumeData(i, open.sortedBy { it.start })
    }

    suspend fun run(): RemoteInfo = withContext(Dispatchers.IO) {
        // Closed only after every worker has stopped, so a pause never shows up as a write error.
        try { work() } finally { if (::channel.isInitialized) runCatching { channel.close() } }
    }

    private suspend fun work(): RemoteInfo = coroutineScope {
        // Blocking socket reads ignore coroutine cancellation; cancelling the calls unblocks them at once on pause.
        launch {
            try { awaitCancellation() } finally { cancelled = true; liveCalls.forEach(Call::cancel) }
        }
        val sampler = launch { while (true) { delay(250); sample() } }
        try {
            val resume = request.resume
            val ranged = resume != null && resume.info.acceptRanges && resume.info.totalBytes > 0
            if (resume != null && (ranged || segments.isNotEmpty())) resumeFrom(resume) else fresh()
            val i = info!!
            if (i.totalBytes >= 0 && segments.any { it.remaining > 0 }) {
                throw DownloadException(ErrorKind.NETWORK, "Download stopped before the end")
            }
            try { channel.force(false) } catch (e: IOException) { throw DownloadException(ErrorKind.DISK, e.message ?: "fsync failed", cause = e) }
            sample()
            i
        } finally {
            sampler.cancel()
            coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        }
    }

    private suspend fun resumeFrom(resume: ResumeData) {
        channel = openChannel(resume.info)
        sample()
        startWorkers(first = null)
    }

    private suspend fun fresh() {
        val first = retrying {
            execute(rangeRequest(request.url, request.headers, 0, null)).also {
                if (it.code == 429 || it.code >= 500) { it.close(); throw IOException("HTTP ${it.code}") }
            }
        }
        try {
            when {
                first.code == 200 || first.code == 206 -> Unit
                first.code.isExpiredLinkCode() -> throw DownloadException(ErrorKind.LINK_EXPIRED, "Link expired (HTTP ${first.code})", first.code)
                else -> throw DownloadException(ErrorKind.HTTP, "HTTP ${first.code}", first.code)
            }
            var i = first.remoteInfo()
            // 200: ranges unknown (many servers answer `bytes=0-` with a plain 200). One extra ranged request tells;
            // the first stream keeps going meanwhile.
            if (first.code == 200 && (i.totalBytes < 0 || i.totalBytes >= 2 * request.minSplitBytes)) {
                i = i.verifyRanges(request.headers, ::execute)
            }
            channel = openChannel(i)
            val total = i.totalBytes
            val seg0 = Segment(0, if (total >= 0) total - 1 else Long.MAX_VALUE, 0)
            synchronized(lock) {
                segments += seg0
                if (i.acceptRanges && total > 0) {
                    // Immediate ramp-up: all parts start now instead of one more per round trip.
                    val n = (total / request.minSplitBytes).coerceIn(1, request.maxConnections.toLong()).toInt()
                    val part = total / n
                    seg0.end.set(part - 1)
                    for (k in 1 until n) {
                        val s = Segment(k * part, if (k == n - 1) total - 1 else (k + 1) * part - 1, k * part)
                        segments += s
                        queue += s
                    }
                }
                info = i
            }
            startWorkers(first = seg0 to first)
        } catch (t: Throwable) {
            runCatching { first.close() }
            throw t
        }
    }

    /** Returns when every worker is done, including the ones [upgrade] or recovery add later. */
    private suspend fun startWorkers(first: Pair<Segment, Response>?): Unit = coroutineScope {
        workerScope = this
        workersActive = true
        try {
            val i = info!!
            val workers = if (i.acceptRanges && i.totalBytes > 0) request.maxConnections.coerceAtLeast(1) else 1
            if (first != null) {
                synchronized(lock) {
                    first.first.active = true
                    first.first.activeSinceMs = System.currentTimeMillis()
                    alive++
                }
            }
            launch { worker(first) }
            repeat(workers - 1) { launch { worker(null) } }
        } finally {
            // workersActive stays true until coroutineScope exits, which happens after all child launches finish.
        }
        workersActive = false
    }

    /** A server first seen as non-resumable just honoured an offset: keep the bytes and let the other workers in. */
    private fun upgrade(seg: Segment, total: Long) {
        synchronized(lock) {
            val i = info!!
            val known = if (i.totalBytes >= 0) i.totalBytes else total
            if (i.acceptRanges || known <= 0) return
            seg.end.compareAndSet(Long.MAX_VALUE, known - 1)
            info = i.copy(acceptRanges = true, totalBytes = known)
            repeat(request.maxConnections - 1) { workerScope.launch { worker(null) } }
        }
    }

    private suspend fun worker(first: Pair<Segment, Response>?) {
        var seg = first?.first ?: claim(newWorker = true) ?: return
        var initial = first?.second
        var holding = true
        try {
            while (true) {
                if (!download(seg, initial)) { holding = false; return }
                initial = null
                seg = claim(newWorker = false) ?: run { holding = false; return }
            }
        } finally {
            if (holding) synchronized(lock) { alive-- }
        }
    }

    /**
     * A queued segment, or half of the biggest running one (work stealing). Null = nothing left worth a connection.
     * Counts the worker in or out of [alive] and marks the claimed segment active under the same lock so concurrent
     * workers can steal from it immediately on resume.
     */
    private fun claim(newWorker: Boolean): Segment? = synchronized(lock) {
        val s = queue.removeFirstOrNull() ?: steal()
        if (s != null) {
            s.active = true
            s.activeSinceMs = System.currentTimeMillis()
            s.slowTicks = 0
            if (newWorker) alive++
        } else if (!newWorker) {
            alive--
        }
        s
    }

    /**
     * The server refused one more connection (many hosts cap connections per client with 403, 429 or 503). While
     * another worker is still downloading, the range goes back to the queue and this worker stops; adaptive recovery
     * probes back up after [refusalCooldownMs] so transient throttles do not lock the download to 1 connection.
     */
    private fun giveBack(seg: Segment): Boolean = synchronized(lock) {
        if (alive <= 1) return false
        alive--
        seg.active = false
        seg.activeCall = null
        queue.addFirst(seg)
        recordRefusal(System.currentTimeMillis())
        true
    }

    private fun recordRefusal(now: Long) {
        refusalCount++
        lastRefusalMs = now
        refusalCooldownMs = (RECOVERY_BASE_MS shl (refusalCount - 1).coerceAtMost(4)).coerceAtMost(60_000L)
    }

    private fun inRefusalCooldown(now: Long): Boolean =
        refusalCount > 0 && (now - lastRefusalMs) < refusalCooldownMs

    private fun steal(preferred: Segment? = null): Segment? {
        val i = info ?: return null
        if (!i.acceptRanges || i.totalBytes <= 0) return null
        while (true) {
            val victim = preferred ?: segments.filter { it.active }.maxByOrNull { it.remaining } ?: return null
            if (!victim.active) return null
            val cur = victim.current.get()
            val oldEnd = victim.end.get()
            val remaining = oldEnd - cur + 1
            if (remaining < 2 * request.minSplitBytes) return null
            val mid = ((cur + remaining / 2) / ALIGN) * ALIGN
            if (mid <= cur) return null
            // The victim re-reads end before every write and trims, so this CAS is the whole hand-off.
            if (!victim.end.compareAndSet(oldEnd, mid - 1)) continue
            return Segment(mid, oldEnd, mid).also { segments += it }
        }
    }

    /** False when the server refused this connection and the segment went back to the queue ([giveBack]). */
    private suspend fun download(seg: Segment, initial: Response?): Boolean {
        seg.active = true
        var pending = initial
        var attempt = 0
        val buffer = ByteBuffer.allocateDirect(bufferBytes)
        try {
            while (seg.remaining > 0) {
                val before = seg.current.get()
                try {
                    val response = pending ?: openSegment(seg)
                    pending = null
                    try {
                        stream(seg, response, buffer, before, isRacer = false)
                    } finally {
                        seg.activeCall = null
                        // close() drains the rest for keep-alive; a concurrent pause cancel makes okio throw ISE there.
                        runCatching { response.close() }
                    }
                    if (seg.current.get() > before) onWorkerProgress()
                    if (seg.end.get() == Long.MAX_VALUE) {
                        // Unknown length: the end of the stream is the end of the file.
                        seg.end.set(seg.current.get() - 1)
                        info = info?.copy(totalBytes = seg.current.get())
                        return true
                    }
                    if (seg.remaining > 0 && seg.current.get() == before) throw IOException("Server closed the connection")
                } catch (e: DownloadException) {
                    val refused = e.kind == ErrorKind.HTTP || e.kind == ErrorKind.LINK_EXPIRED
                    if (refused && seg.current.get() == before) {
                        val otherAlive = synchronized(lock) { alive > 1 }
                        if (otherAlive && attempt < 1) {
                            attempt++
                            seg.retries++
                            awaitNetwork()
                            delay(backoff(attempt))
                            continue
                        }
                        if (giveBack(seg)) return false
                    }
                    throw e
                } catch (e: IllegalStateException) {
                    if (cancelled) throw kotlinx.coroutines.CancellationException("paused")
                    throw e
                } catch (e: IOException) {
                    seg.activeCall = null
                    pending?.close(); pending = null
                    currentCoroutineContext().ensureActive()
                    if (cancelled) throw kotlinx.coroutines.CancellationException("paused")
                    if (seg.remaining <= 0) break
                    if (seg.current.get() > before) { attempt = 0; onWorkerProgress() }
                    attempt++
                    seg.retries++
                    // Twice busy in a row: a per-client connection cap, not a hiccup. Network errors never count here.
                    if (e is Busy && attempt >= 2 && seg.current.get() == before && giveBack(seg)) return false
                    if (attempt > maxRetries) throw DownloadException(ErrorKind.NETWORK, e.message ?: "Network error", cause = e)
                    awaitNetwork()
                    delay(backoff(attempt))
                }
            }
        } finally {
            pending?.close()
            seg.activeCall = null
            seg.active = false
        }
        return true
    }

    private fun onWorkerProgress() = synchronized(lock) {
        if (refusalCount > 0) {
            refusalCount = 0
            refusalCooldownMs = RECOVERY_BASE_MS
        }
    }

    private suspend fun openSegment(seg: Segment, refreshed: Boolean = false): Response {
        val i = info!!
        val end = seg.end.get().takeIf { it != Long.MAX_VALUE }
        val r = execute(rangeRequest(i.finalUrl, request.headers, seg.current.get(), end), seg = seg)
        val code = r.code
        if (code.isExpiredLinkCode()) {
            r.close()
            if (refreshed) throw DownloadException(ErrorKind.LINK_EXPIRED, "Link expired (HTTP $code)", code)
            refreshLink(i.finalUrl)
            return openSegment(seg, refreshed = true)
        }
        if (code == 429 || code >= 500) { r.close(); throw Busy(code) }
        if (code == 200 && seg.current.get() == 0L && (!i.acceptRanges || seg.start == 0L)) {
            if (i.totalBytes >= 0 && r.body.contentLength() >= 0 && r.body.contentLength() != i.totalBytes) {
                r.close(); throw DownloadException(ErrorKind.FILE_CHANGED, "The file on the server changed size")
            }
            return r
        }
        if (code != 206) {
            r.close()
            val message = when {
                code != 200 -> "HTTP $code"
                i.acceptRanges -> "Server stopped accepting resume requests"
                else -> "Connection lost and this server cannot resume. Refresh the link or restart the download."
            }
            throw DownloadException(ErrorKind.HTTP, message, code)
        }
        val range = parseContentRange(r.header("Content-Range"))
        val changed = range == null || range.first != seg.current.get() ||
            (i.totalBytes >= 0 && range.third >= 0 && range.third != i.totalBytes) || !sameEtag(i.etag, r.header("ETag"))
        if (changed) { r.close(); throw DownloadException(ErrorKind.FILE_CHANGED, "The file on the server changed") }
        if (!i.acceptRanges) upgrade(seg, range.third)
        return r
    }

    /**
     * The direct link (often a signed CDN URL) expired. Ask the URL the user added again: it usually redirects to a
     * fresh signed link for the same file. Same size and ETag, or the bytes on disk are not reused.
     */
    private suspend fun refreshLink(failedUrl: String) = refreshLock.withLock {
        val i = info!!
        if (i.finalUrl != failedUrl) return@withLock // another worker already refreshed it
        if (request.url == failedUrl && i.finalUrl == request.url) {
            // Nothing behind it to re-resolve.
            throw DownloadException(ErrorKind.LINK_EXPIRED, "Link expired")
        }
        execute(rangeRequest(request.url, request.headers, 0, 0)).use { r ->
            if (r.code.isExpiredLinkCode()) throw DownloadException(ErrorKind.LINK_EXPIRED, "Link expired (HTTP ${r.code})", r.code)
            if (r.code != 206 && r.code != 200) throw IOException("HTTP ${r.code}")
            val fresh = r.remoteInfo()
            if ((i.totalBytes >= 0 && fresh.totalBytes != i.totalBytes) || !sameEtag(i.etag, fresh.etag)) {
                throw DownloadException(ErrorKind.FILE_CHANGED, "The file behind the link changed")
            }
            info = i.copy(finalUrl = fresh.finalUrl)
        }
    }

    private fun stream(seg: Segment, response: Response, buffer: ByteBuffer, startPos: Long, isRacer: Boolean) {
        val source = response.body.source()
        var streamPos = startPos
        while (true) {
            buffer.clear()
            var eof = false
            // Fill the buffer, but hand bytes to disk as soon as the socket would block: fewer pwrites, no stalls.
            while (buffer.hasRemaining()) {
                if (source.read(buffer) == -1) { eof = true; break }
                if (buffer.position() >= FLUSH_AT && source.buffer.size == 0L) break
            }
            buffer.flip()
            val nextPos = commitBuffer(seg, streamPos, buffer)
            if (nextPos < 0) {
                if (isRacer) seg.activeCall?.cancel() else seg.racerCall?.cancel()
                return
            }
            streamPos = nextPos
            if (seg.remaining <= 0) {
                if (isRacer) seg.activeCall?.cancel() else seg.racerCall?.cancel()
                return
            }
            if (eof) return
            if (cancelled) throw IOException("Canceled")
        }
    }

    /**
     * Writes [buffer] (representing bytes starting at [streamPos]) to disk, skipping any prefix already written
     * by a racing worker and updating [Segment.current] atomically under [seg]'s monitor. Returns the next
     * stream offset, or -1 when the segment has already completed.
     */
    private fun commitBuffer(seg: Segment, streamPos: Long, buffer: ByteBuffer): Long = synchronized(seg) {
        val cur = seg.current.get()
        val end = seg.end.get()
        if (end != Long.MAX_VALUE && cur > end) return -1L
        val avail = buffer.remaining()
        val skip = (cur - streamPos).coerceAtLeast(0)
        if (skip >= avail) return streamPos + avail
        if (skip > 0) buffer.position(buffer.position() + skip.toInt())
        val remaining = if (end == Long.MAX_VALUE) buffer.remaining().toLong() else (end - cur + 1).coerceAtLeast(0)
        val allowed = remaining.coerceAtMost(buffer.remaining().toLong()).toInt()
        if (allowed <= 0) return -1L
        buffer.limit(buffer.position() + allowed)
        var p = cur
        try {
            while (buffer.hasRemaining()) p += channel.write(buffer, p)
        } catch (e: IOException) {
            throw DownloadException(ErrorKind.DISK, e.message ?: "Write failed", cause = e)
        }
        seg.current.set(p)
        return streamPos + avail
    }

    private fun execute(request: Request, seg: Segment? = null, isRacer: Boolean = false): Response {
        val call = client.newCall(request)
        liveCalls += call
        if (seg != null) {
            if (isRacer) seg.racerCall = call else seg.activeCall = call
        }
        if (cancelled) call.cancel()
        try {
            return call.execute()
        } finally {
            liveCalls -= call
        }
    }

    private suspend fun openChannel(i: RemoteInfo): FileChannel = try {
        openFile(i)
    } catch (e: IOException) {
        throw DownloadException(ErrorKind.DISK, e.message ?: "Cannot open file", cause = e)
    }

    private suspend fun <T> retrying(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++attempt > maxRetries) throw DownloadException(ErrorKind.NETWORK, e.message ?: "Network error", cause = e)
                awaitNetwork()
                delay(backoff(attempt))
            }
        }
    }

    private fun backoff(attempt: Int): Long {
        val base = (retryBaseMillis shl (attempt - 1).coerceAtMost(6)).coerceAtMost(60_000)
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    /** Saved segments hold only unfinished ranges, so with a known size everything outside them is on disk. */
    private fun downloaded(): Long {
        val total = info?.totalBytes ?: -1
        return if (total >= 0) total - segments.sumOf { it.remaining } else segments.sumOf { it.current.get() - it.start }
    }

    private fun sample() {
        val now = downloaded()
        val delta = if (lastDownloaded < 0) 0 else (now - lastDownloaded).coerceAtLeast(0)
        lastDownloaded = now
        totalSpeed = if (totalSpeed == 0.0) delta * 4.0 else totalSpeed * (1 - ALPHA) + delta * 4.0 * ALPHA
        val segs = segments.sortedBy { it.start }.map { s ->
            val cur = s.current.get()
            val d = (cur - s.sampledAt).coerceAtLeast(0)
            s.sampledAt = cur
            s.speed = s.speed * (1 - ALPHA) + d * 4.0 * ALPHA
            SegmentProgress(s.start, s.end.get(), cur, s.speed.toLong(), s.active, s.retries)
        }
        _progress.value = Progress(info, now, totalSpeed.toLong(), segs)
        if (workersActive && !cancelled) checkAdaptiveAndEndGame()
    }

    private fun checkAdaptiveAndEndGame() {
        val i = info ?: return
        if (!i.acceptRanges || i.totalBytes <= 0) return
        val nowMs = System.currentTimeMillis()
        val maxConn = request.maxConnections.coerceAtLeast(1)

        // 1. Adaptive recovery: if earlier 403/429/503 refusals reduced concurrency, probe back up after cooldown.
        var launchRecovery = false
        synchronized(lock) {
            if (alive < maxConn && refusalCount > 0 && !inRefusalCooldown(nowMs)) {
                val hasWork = queue.isNotEmpty() || segments.any { it.active && it.remaining >= 2 * request.minSplitBytes }
                if (hasWork) {
                    lastRefusalMs = nowMs
                    launchRecovery = true
                }
            }
        }
        if (launchRecovery) workerScope.launch { worker(null) }

        // 2. Lazy-segment detection: a segment slower than 25% of the median for 6 s is split or reconnected.
        val mature = segments.filter { it.active && (nowMs - it.activeSinceMs) >= LAZY_GRACE_MS }
        if (mature.size >= 2) {
            val sorted = mature.map { it.speed }.sorted()
            val median = sorted[sorted.size / 2]
            if (median > 0.0) {
                val threshold = median * 0.25
                for (s in mature) {
                    if (s.speed < threshold) s.slowTicks++ else s.slowTicks = 0
                    if (s.slowTicks >= LAZY_TICKS) {
                        s.slowTicks = 0
                        var splitSeg: Segment? = null
                        synchronized(lock) {
                            if (alive < maxConn && !inRefusalCooldown(nowMs) && s.remaining >= 2 * request.minSplitBytes) {
                                splitSeg = steal(s)?.also { queue.addLast(it) }
                            }
                        }
                        if (splitSeg != null) {
                            workerScope.launch { worker(null) }
                        } else if (s.remaining > 0) {
                            s.activeCall?.cancel()
                        }
                    }
                }
            }
        }

        // 3. End-game duplicate tail request: when < 2 s remains and a slot is free, race the slowest tail.
        if (totalSpeed > 0.0) {
            val rem = segments.sumOf { it.remaining }
            if (rem in ENDGAME_MIN_BYTES..(totalSpeed * 2.0).toLong()) {
                var victim: Segment? = null
                synchronized(lock) {
                    if (queue.isEmpty() && alive < maxConn && !inRefusalCooldown(nowMs)) {
                        victim = segments
                            .filter { it.active && !it.racing && it.remaining >= ENDGAME_MIN_BYTES }
                            .minByOrNull { it.speed }
                            ?.also {
                                it.racing = true
                                alive++
                            }
                    }
                }
                victim?.let { v -> workerScope.launch { raceEndGame(v) } }
            }
        }
    }

    /** Duplicate request for the tail of a slow segment in end-game; shares [Segment.current] via [commitBuffer]. */
    private suspend fun raceEndGame(seg: Segment) {
        val buffer = ByteBuffer.allocateDirect(bufferBytes)
        try {
            val i = info ?: return
            val start = seg.current.get()
            val end = seg.end.get()
            if (end == Long.MAX_VALUE || start > end) return
            val r = execute(rangeRequest(i.finalUrl, request.headers, start, end), seg = seg, isRacer = true)
            try {
                if (r.code == 206) {
                    val range = parseContentRange(r.header("Content-Range"))
                    if (range != null && range.first == start && sameEtag(i.etag, r.header("ETag"))) {
                        stream(seg, r, buffer, start, isRacer = true)
                    }
                }
            } finally {
                seg.racerCall = null
                runCatching { r.close() }
            }
        } catch (_: Throwable) {
            // End-game racer is best-effort; the primary worker owns retries and error reporting.
        } finally {
            seg.racerCall = null
            seg.racing = false
            synchronized(lock) { alive-- }
        }
    }

    /** 429 or 5xx: the server is there but will not serve this request now. */
    private class Busy(code: Int) : IOException("HTTP $code")

    private companion object {
        const val ALIGN = 64L * 1024
        const val FLUSH_AT = 32 * 1024
        const val ALPHA = 0.3
        const val RECOVERY_BASE_MS = 3_000L
        const val LAZY_GRACE_MS = 2_000L
        const val LAZY_TICKS = 24
        const val ENDGAME_MIN_BYTES = 64L * 1024
    }
}
