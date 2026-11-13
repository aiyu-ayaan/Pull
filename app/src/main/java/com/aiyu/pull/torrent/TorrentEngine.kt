package com.aiyu.pull.torrent

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.aiyu.pull.data.DownloadDao
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.data.Encryption
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.data.decodeFilePriorities
import com.aiyu.pull.data.effectiveFilePriority
import com.aiyu.pull.download.FilePriority
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.PeerInfo
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.address
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.ip_filter
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a .torrent or magnet contains, for the add sheet. */
data class TorrentMeta(val name: String, val infoHash: String, val totalBytes: Long, val files: List<Pair<String, Long>>, val bytes: ByteArray)

/**
 * One libtorrent session for the whole app, started on first use. Pull downloads torrents like any other
 * download: it stops when the chosen files are complete, then seeds only if the user turned seeding on.
 */
@Singleton
class TorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val files: FileStore,
    settings: SettingsRepository,
) {
    val session = SessionManager(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var latest = Settings()
    val settings: Settings get() = latest
    private val prefs by lazy { runCatching { context.getSharedPreferences("pull_torrent_opts", Context.MODE_PRIVATE) }.getOrNull() }
    private val bannedIpSet = ConcurrentHashMap.newKeySet<String>()
    private val firstLastMap = ConcurrentHashMap<Long, Boolean>()
    val bannedIps: Set<String> get() = bannedIpSet.toSet()

    init {
        prefs?.getStringSet("banned_ips", emptySet())?.let(bannedIpSet::addAll)
        // Settings changes apply to a running session at once; a stopped one picks them up when it starts.
        scope.launch {
            settings.settings.collect { s ->
                latest = s
                synchronized(lock) { if (session.isRunning) session.applySettings(pack(s)) }
            }
        }
    }
    private val torrentsDir = File(context.filesDir, "torrents").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "magnet").apply { mkdirs() }

    fun ensureStarted() = synchronized(lock) {
        if (session.isRunning) return
        session.start(SessionParams(pack(latest)))
        if (bannedIpSet.isNotEmpty()) applyIpFilterLocked()
    }

    fun banPeer(ipOrEndpoint: String) {
        val cleanIp = extractPeerIp(ipOrEndpoint)
        if (cleanIp.isBlank()) return
        bannedIpSet.add(cleanIp)
        prefs?.edit()?.putStringSet("banned_ips", bannedIpSet.toSet())?.apply()
        synchronized(lock) {
            if (session.isRunning) applyIpFilterLocked()
        }
    }

    fun isPeerBanned(ipOrEndpoint: String): Boolean = extractPeerIp(ipOrEndpoint) in bannedIpSet

    fun isPrioritizeFirstLast(id: Long): Boolean =
        firstLastMap[id] ?: prefs?.getBoolean("first_last_$id", false) ?: false

    fun setPrioritizeFirstLast(id: Long, on: Boolean) {
        firstLastMap[id] = on
        prefs?.edit()?.putBoolean("first_last_$id", on)?.apply()
    }

    private fun applyIpFilterLocked() {
        runCatching {
            val swigSession = session.swig() ?: return
            val filter = ip_filter()
            val blocked = ip_filter.access_flags.blocked.swigValue().toLong()
            for (ip in bannedIpSet) {
                val ec = error_code()
                val addr = address.from_string(ip, ec)
                if (!ec.failed()) {
                    filter.add_rule(addr, addr, blocked)
                }
            }
            swigSession.set_ip_filter(filter)
        }
    }

    private fun pack(s: Settings) = SettingsPack().apply {
        setEnableDht(s.torrentDht)
        setEnableLsd(s.torrentLsd)
        setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), s.torrentUtp)
        setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), s.torrentUtp)
        setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), s.torrentPortMapping)
        setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), s.torrentPortMapping)
        val enc = when (s.torrentEncryption) {
            Encryption.ENABLED -> settings_pack.enc_policy.pe_enabled
            Encryption.FORCED -> settings_pack.enc_policy.pe_forced
            Encryption.DISABLED -> settings_pack.enc_policy.pe_disabled
        }.swigValue()
        setInteger(settings_pack.int_types.out_enc_policy.swigValue(), enc)
        setInteger(settings_pack.int_types.in_enc_policy.swigValue(), enc)
        connectionsLimit(s.torrentConnections)
        // Pull's own queue decides how many downloads run; libtorrent must not queue them a second time.
        activeDownloads(-1)
        activeLimit(-1)
        downloadRateLimit(s.torrentDownLimit * 1024)
        uploadRateLimit(s.torrentUpLimit * 1024)
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
    @Volatile private var info: TorrentInfo? = null
    @Volatile private var url = ""
    @Volatile private var prioritizeFirstLast = false
    /** All wanted bytes are on disk; the handle then stays in the session for [seed]. */
    @Volatile private var complete = false

    override suspend fun run(): Long = withContext(Dispatchers.IO) {
        engine.ensureStarted()
        val e = dao.get(id) ?: throw IOException("Download removed")
        val file = e.torrentPath?.let(::File)?.takeIf { it.exists() } ?: fetchMetadata(e)
        val ti = TorrentInfo(file)
        val saveDir = File(e.saveDir ?: engine.defaultSaveDir().path).apply { mkdirs() }
        val selected = e.selectedFiles.split(',').mapNotNull(String::toIntOrNull).toSet()
        val overrides = decodeFilePriorities(e.filePriorities)
        val fs = ti.files()
        val priorities = Array(ti.numFiles()) { effectiveFilePriority(it, selected, overrides).toLibtorrent() }
        val top = File(saveDir, if (ti.numFiles() == 1) fs.filePath(0) else ti.name())
        root = top
        paths = (0 until ti.numFiles()).filter { effectiveFilePriority(it, selected, overrides) != FilePriority.SKIP }.map { File(saveDir, fs.filePath(it)) }
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
        (e.extraTrackers.lines() + engine.settings.torrentDefaultTrackers.lines())
            .map(String::trim).filter { it.isNotEmpty() }.distinct()
            .forEach { h.addTracker(AnnounceEntry(it)) }
        info = ti
        url = e.url
        prioritizeFirstLast = engine.isPrioritizeFirstLast(id)
        if (prioritizeFirstLast) applyFirstLastPriorities(h, ti, priorities)
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
                ) {
                    complete = true
                    return@withContext st.totalWanted()
                }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") 0L
        } finally {
            if (!complete) withContext(NonCancellable) { runCatching { engine.session.remove(h) } }
        }
    }

    override suspend fun seed(onStart: () -> Unit) = withContext(Dispatchers.IO) {
        val h = handle ?: return@withContext
        try {
            val ti = info ?: return@withContext
            if (!complete || !engine.settings.torrentSeed || !h.isValid) return@withContext
            onStart()
            while (true) {
                currentCoroutineContext().ensureActive()
                val st = h.status(TorrentHandle.QUERY_PIECES)
                publishLive(h, ti, st, url)
                // Read each time: lowering the ratio in Settings stops a running seed.
                val limit = engine.settings.torrentSeedRatio
                if (limit > 0 && st.allTimeUpload() * 100 >= limit.toLong() * st.totalWanted().coerceAtLeast(1)) return@withContext
                delay(1_000)
            }
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
        val peers = runCatching { h.peerInfo() }.getOrNull().orEmpty()
            .filter { !engine.isPeerBanned(it.ip()) }
            .map { p ->
                val isWebSeed = p.connectionType() == PeerInfo.ConnectionType.WEB_SEED || p.connectionType() == PeerInfo.ConnectionType.HTTP_SEED
                PeerLive(
                    ip = p.ip(),
                    client = p.client(),
                    down = p.downSpeed().toLong(),
                    up = p.upSpeed().toLong(),
                    progress = p.progress(),
                    downloaded = p.totalDownload(),
                    uploaded = p.totalUpload(),
                    flags = formatPeerFlags(p.flags(), isWebSeed),
                    source = formatPeerSource(p.source().toInt()),
                )
            }
        _torrent.value = TorrentLive(
            state = st.state().name.lowercase(),
            peers = st.numPeers(),
            seeds = st.numSeeds(),
            uploadBytesPerSecond = st.uploadPayloadRate().toLong(),
            uploaded = st.allTimeUpload(),
            pieces = pieces,
            files = List(fs.numFiles()) {
                TorrentFileLive(fs.filePath(it), fs.fileSize(it), done?.getOrNull(it) ?: 0, prio?.getOrNull(it)?.toFilePriority() ?: FilePriority.NORMAL)
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
            prioritizeFirstLast = prioritizeFirstLast,
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
        val current = runCatching { h.filePriorities() }.getOrNull()
        val updated = Array(count) { i ->
            if (selected.isEmpty() || i in selected) {
                val cur = current?.getOrNull(i)
                if (cur == null || cur == Priority.IGNORE) Priority.DEFAULT else cur
            } else Priority.IGNORE
        }
        h.prioritizeFiles(updated)
        if (prioritizeFirstLast) info?.let { ti -> applyFirstLastPriorities(h, ti, updated) }
    }

    override fun torrentCommand(c: TorrentCommand) {
        val h = handle?.takeIf { it.isValid } ?: return
        when (c) {
            TorrentCommand.Recheck -> h.forceRecheck()
            TorrentCommand.Reannounce -> { h.forceReannounce(); h.forceDHTAnnounce() }
            is TorrentCommand.Sequential -> if (c.on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            is TorrentCommand.PrioritizeFirstLast -> {
                prioritizeFirstLast = c.on
                engine.setPrioritizeFirstLast(id, c.on)
                info?.let { ti -> applyFirstLastPriorities(h, ti, runCatching { h.filePriorities() }.getOrNull()) }
            }
            is TorrentCommand.Limits -> { h.setDownloadLimit(c.down); h.setUploadLimit(c.up) }
            is TorrentCommand.AddTrackers -> c.urls.forEach { h.addTracker(AnnounceEntry(it)) }
            is TorrentCommand.RemoveTracker -> h.replaceTrackers(h.trackers().filter { it.url() != c.url })
            is TorrentCommand.SetFilePriority -> if (c.index in 0 until count) {
                val current = runCatching { h.filePriorities() }.getOrNull() ?: Array(count) { Priority.DEFAULT }
                if (c.index < current.size) {
                    current[c.index] = c.priority.toLibtorrent()
                    h.prioritizeFiles(current)
                    if (prioritizeFirstLast) info?.let { ti -> applyFirstLastPriorities(h, ti, current) }
                }
            }
            is TorrentCommand.BanPeer -> engine.banPeer(c.ip)
        }
    }

    private fun applyFirstLastPriorities(h: TorrentHandle, ti: TorrentInfo, filePriorities: Array<Priority>?) {
        runCatching {
            val fs = ti.files()
            if (!prioritizeFirstLast) {
                if (ti.numPieces() > 0) {
                    h.prioritizePieces(Array(ti.numPieces()) { Priority.DEFAULT })
                }
                filePriorities?.let(h::prioritizeFiles)
                return
            }
            val fileRanges = (0 until ti.numFiles()).map { i ->
                val wanted = !fs.padFileAt(i) && (filePriorities?.getOrNull(i) ?: Priority.DEFAULT) != Priority.IGNORE
                Triple(fs.fileOffset(i), fs.fileSize(i), wanted)
            }
            val topPieces = firstAndLastPieces(ti.numPieces(), ti.pieceLength(), fileRanges)
            for (piece in topPieces) {
                h.piecePriority(piece, Priority.TOP_PRIORITY)
            }
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

private fun FilePriority.toLibtorrent(): Priority = when (this) {
    FilePriority.SKIP -> Priority.IGNORE
    FilePriority.LOW -> Priority.LOW
    FilePriority.NORMAL -> Priority.DEFAULT
    FilePriority.HIGH -> Priority.TOP_PRIORITY
}

private fun Priority.toFilePriority(): FilePriority = when (this) {
    Priority.IGNORE -> FilePriority.SKIP
    Priority.TWO, Priority.LOW -> FilePriority.LOW
    Priority.SIX, Priority.TOP_PRIORITY -> FilePriority.HIGH
    else -> FilePriority.NORMAL
}

/** Strips port / brackets from a peer endpoint (`"1.2.3.4:6881"` -> `"1.2.3.4"`, `"[::1]:6881"` -> `"::1"`). */
internal fun extractPeerIp(ipOrEndpoint: String): String {
    val trimmed = ipOrEndpoint.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("[") && trimmed.contains("]")) {
        return trimmed.substringAfter('[').substringBefore(']')
    }
    val firstColon = trimmed.indexOf(':')
    val lastColon = trimmed.lastIndexOf(':')
    return if (firstColon > 0 && firstColon == lastColon) trimmed.substring(0, firstColon) else trimmed
}

/** Formats libtorrent `peer_info::flags` bitmask into standard BitTorrent flag characters. */
internal fun formatPeerFlags(flags: Int, isWebSeed: Boolean = false): String = buildString {
    if (isWebSeed) append('W')
    if ((flags and 0x01) != 0) append(if ((flags and 0x02) == 0) 'D' else 'd')
    if ((flags and 0x04) != 0) append(if ((flags and 0x08) == 0) 'U' else 'u')
    if ((flags and 0x400) != 0) append('S')
    if ((flags and 0x20000) != 0) append('P')
    if ((flags and 0x340000) != 0) append('E')
    append(if ((flags and 0x20) == 0) 'I' else 'O')
}

/** Formats libtorrent `peer_info::source` bitmask into human-readable discovery sources. */
internal fun formatPeerSource(source: Int): String = buildList {
    if ((source and 0x01) != 0) add("Tracker")
    if ((source and 0x02) != 0) add("DHT")
    if ((source and 0x04) != 0) add("PEX")
    if ((source and 0x08) != 0) add("LSD")
    if ((source and 0x10) != 0) add("Resume")
    if ((source and 0x20) != 0) add("Incoming")
}.joinToString("/")

/**
 * Computes piece indices in the first ~1% (at least 1 piece) and last ~1% (at least 1 piece)
 * of each wanted file's piece range `[fileOffset, fileOffset + fileSize)`.
 */
internal fun firstAndLastPieces(
    numPieces: Int,
    pieceLength: Int,
    files: List<Triple<Long, Long, Boolean>>,
): Set<Int> {
    if (numPieces <= 0 || pieceLength <= 0) return emptySet()
    val pl = pieceLength.toLong()
    val out = LinkedHashSet<Int>()
    for ((offset, size, wanted) in files) {
        if (!wanted || size <= 0L) continue
        val firstPiece = (offset / pl).toInt().coerceIn(0, numPieces - 1)
        val lastPiece = ((offset + size - 1) / pl).toInt().coerceIn(firstPiece, numPieces - 1)
        val span = lastPiece - firstPiece + 1
        val edge = (span / 100).coerceAtLeast(1)
        for (p in firstPiece until (firstPiece + edge).coerceAtMost(lastPiece + 1)) {
            out.add(p)
        }
        for (p in (lastPiece - edge + 1).coerceAtLeast(firstPiece)..lastPiece) {
            out.add(p)
        }
    }
    return out
}
