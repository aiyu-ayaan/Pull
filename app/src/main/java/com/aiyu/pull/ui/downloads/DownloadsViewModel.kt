package com.aiyu.pull.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.data.Status
import com.aiyu.pull.download.DownloadManager
import com.aiyu.pull.download.FileStore
import com.aiyu.pull.download.LiveStats
import com.aiyu.pull.ui.etaSeconds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val manager: DownloadManager,
    val files: FileStore,
) : ViewModel() {
    /** Null until Room answers, so the first frame shows nothing instead of a flash of the empty state. */
    val downloads: StateFlow<List<DownloadEntity>?> =
        manager.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val live: StateFlow<Map<Long, LiveStats>> = manager.live

    fun observe(id: Long): Flow<DownloadEntity?> = manager.download(id)

    fun pause(id: Long) = manager.pause(id)
    fun resume(id: Long) = manager.resume(id)
    fun restart(id: Long) = manager.restart(id)
    fun delete(id: Long, withFile: Boolean) = manager.delete(id, withFile)
    fun pauseAll() = manager.pauseAll()
    fun resumeAll() = manager.resumeAll()
    fun refreshLink(id: Long, url: String) = manager.refreshLink(id, url)
    fun setTorrentFiles(id: Long, selected: Set<Int>, count: Int) = manager.setTorrentFiles(id, selected, count)
    fun torrentFiles(e: DownloadEntity) = manager.torrentFiles(e)
}

/** One download as the UI shows it: live numbers while running, saved numbers otherwise. */
data class DownloadView(val entity: DownloadEntity, val live: LiveStats?) {
    val id get() = entity.id
    val running get() = entity.status == Status.RUNNING
    val downloaded get() = live?.downloadedBytes ?: entity.downloadedBytes
    val total get() = live?.totalBytes?.takeIf { it > 0 } ?: entity.totalBytes
    val speed get() = if (running) live?.bytesPerSecond ?: 0 else 0
    val fraction get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val eta get() = if (running && total > 0) etaSeconds(total - downloaded, speed) else null
}
