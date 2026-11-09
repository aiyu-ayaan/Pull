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
)

data class TorrentFileLive(val path: String, val size: Long, val done: Long, val wanted: Boolean)

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
