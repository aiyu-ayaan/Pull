package com.aiyu.pull.download

import com.aiyu.pull.data.DownloadDao
import com.aiyu.pull.data.encodeSegments
import com.aiyu.pull.engine.DownloadTask
import com.aiyu.pull.engine.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live numbers only a torrent has. */
data class TorrentLive(
    val state: String,
    val peers: Int,
    val seeds: Int,
    val uploadBytesPerSecond: Long,
    val uploaded: Long,
    /** Downsampled piece map: true = have. */
    val pieces: BooleanArray,
    val files: List<TorrentFileLive>,
    val trackers: List<TrackerLive> = emptyList(),
    val peerList: List<PeerLive> = emptyList(),
    val pieceCount: Int = 0,
    val pieceSize: Int = 0,
    val piecesHave: Int = 0,
    /** Seeds and peers the trackers report in the whole swarm, -1 when unknown. */
    val swarmSeeds: Int = -1,
    val swarmPeers: Int = -1,
    val availability: Float = 0f,
    val downloadedTotal: Long = 0,
    val sequential: Boolean = false,
    val downLimit: Int = 0,
    val upLimit: Int = 0,
    val savePath: String = "",
    val comment: String = "",
    val creator: String = "",
    /** Seconds since epoch; 0 when the torrent does not say. */
    val createdAt: Long = 0,
)

data class TorrentFileLive(val path: String, val size: Long, val done: Long, val wanted: Boolean)

data class TrackerLive(val url: String, val tier: Int, val working: Boolean, val updating: Boolean, val fails: Int, val message: String)

data class PeerLive(val ip: String, val client: String, val down: Long, val up: Long, val progress: Float, val downloaded: Long, val uploaded: Long)

/** Things the user can do to a running torrent. */
sealed interface TorrentCommand {
    data object Recheck : TorrentCommand
    data object Reannounce : TorrentCommand
    data class Sequential(val on: Boolean) : TorrentCommand
    data class Limits(val down: Int, val up: Int) : TorrentCommand
    data class AddTrackers(val urls: List<String>) : TorrentCommand
}

/**
 * One running download of any kind (HTTP, torrent, video). [run] finishes when the file is complete; cancelling the
 * coroutine is a pause. [save] persists whatever the next start needs to continue, about once a second.
 */
interface Transfer {
    val progress: StateFlow<Progress>
    val torrent: StateFlow<TorrentLive?> get() = NO_TORRENT

    /** Returns the final size in bytes. */
    suspend fun run(): Long

    suspend fun save(activeDelta: Long)

    /** File is complete on disk: make it visible to other apps. */
    suspend fun publish() {}

    /** Torrent: change which files download, while running. */
    fun updateFiles(selected: Set<Int>) {}

    /** Torrent: apply a command live. Settings that must survive a restart are saved by the manager. */
    fun torrentCommand(c: TorrentCommand) {}

    companion object {
        private val NO_TORRENT = MutableStateFlow<TorrentLive?>(null)
    }
}

/** Segmented HTTP download through the engine. */
class HttpTransfer(
    private val id: Long,
    private val task: DownloadTask,
    private val dao: DownloadDao,
    private val files: FileStore,
) : Transfer {
    override val progress get() = task.progress

    override suspend fun run(): Long = task.run().totalBytes

    override suspend fun save(activeDelta: Long) {
        val snap = task.snapshot()
        if (snap == null) {
            dao.addActiveTime(id, activeDelta)
            return
        }
        val i = snap.info
        val remaining = snap.segments.sumOf { (it.end - it.current + 1).coerceAtLeast(0) }
        val downloaded = if (i.totalBytes >= 0) i.totalBytes - remaining else task.progress.value.downloadedBytes
        dao.saveProgress(
            id, downloaded, encodeSegments(snap.segments), i.finalUrl, i.totalBytes, i.etag, i.lastModified, i.acceptRanges, activeDelta,
        )
    }

    override suspend fun publish() {
        dao.get(id)?.fileUri?.let { files.publish(files.parse(it)) }
    }
}
