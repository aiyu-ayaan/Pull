package com.aiyu.pull.ui.add

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.pull.R
import com.aiyu.pull.data.CommandTemplate
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.download.DownloadManager
import com.aiyu.pull.engine.DownloadException
import com.aiyu.pull.engine.ErrorKind
import com.aiyu.pull.engine.RemoteInfo
import com.aiyu.pull.torrent.TorrentEngine
import com.aiyu.pull.torrent.TorrentMeta
import com.aiyu.pull.video.VideoEngine
import com.aiyu.pull.video.VideoMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** What the add popup found behind the input. */
sealed interface Analysis {
    data object Idle : Analysis
    data class Loading(val message: Int) : Analysis
    data class FileLink(val info: RemoteInfo) : Analysis
    /** [meta] null while a magnet's metadata is still being fetched from peers. */
    data class Torrent(val meta: TorrentMeta?, val magnet: String?, val fetching: Boolean) : Analysis
    data class Video(val meta: VideoMeta) : Analysis
    /** An HTML page: no file here, but yt-dlp may find a video on it. */
    data class WebPage(val info: RemoteInfo) : Analysis
    data class Failed(val message: String) : Analysis
}

@HiltViewModel
class AddViewModel @Inject constructor(
    private val manager: DownloadManager,
    private val torrents: dagger.Lazy<TorrentEngine>,
    private val videos: dagger.Lazy<VideoEngine>,
    settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<Analysis>(Analysis.Idle)
    val state: StateFlow<Analysis> = _state.asStateFlow()
    val connections: StateFlow<Int> = settings.settings.map { it.connections }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val templates: StateFlow<List<CommandTemplate>> = settings.templates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val videoDefaults: StateFlow<com.aiyu.pull.video.VideoChoice> =
        settings.settings.map { it.video }.stateIn(viewModelScope, SharingStarted.Eagerly, com.aiyu.pull.video.VideoChoice())
    val sequentialDefault: StateFlow<Boolean> = settings.settings.map { it.torrentSequential }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var job: Job? = null
    private var analyzed: String? = null

    fun analyze(input: String, headers: Map<String, String>, torrentFile: String?) {
        val key = torrentFile ?: input
        if (key == analyzed) return
        analyzed = key
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = try {
                when {
                    torrentFile != null -> Analysis.Torrent(torrents.get().parse(File(torrentFile).readBytes()), null, false)
                    input.startsWith("magnet:", ignoreCase = true) -> fetchMagnet(input)
                    !input.startsWith("http://") && !input.startsWith("https://") -> Analysis.Idle
                    VideoEngine.isVideoSite(input) -> { _state.value = Analysis.Loading(R.string.analyze_video); Analysis.Video(videos.get().info(input, headers)) }
                    else -> {
                        _state.value = Analysis.Loading(R.string.probe_loading)
                        val info = manager.probe(input, headers)
                        when {
                            info.mimeType == "application/x-bittorrent" || info.fileName?.endsWith(".torrent", true) == true ->
                                Analysis.Torrent(torrents.get().parse(manager.fetchTorrent(info.finalUrl, headers)), null, false)
                            info.mimeType == "text/html" -> Analysis.WebPage(info)
                            else -> Analysis.FileLink(info)
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: DownloadException) {
                Analysis.Failed(if (e.kind == ErrorKind.LINK_EXPIRED) context.getString(R.string.probe_expired, e.httpCode) else e.message.orEmpty())
            } catch (e: Throwable) {
                android.util.Log.w("Pull", "analyze failed for $key", e)
                Analysis.Failed(e.message?.lines()?.lastOrNull { it.isNotBlank() }?.substringAfter("ERROR: ") ?: context.getString(R.string.probe_failed))
            }
        }
    }

    /** The page was HTML: let yt-dlp look for a video on it. */
    fun findVideos(url: String, headers: Map<String, String> = emptyMap()) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = Analysis.Loading(R.string.analyze_video)
            _state.value = try {
                Analysis.Video(videos.get().info(url, headers))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                android.util.Log.w("Pull", "video search failed for $url", e)
                Analysis.Failed(e.message?.lines()?.lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR: ") ?: context.getString(R.string.analyze_no_video))
            }
        }
    }

    private suspend fun fetchMagnet(uri: String): Analysis {
        _state.value = Analysis.Torrent(null, uri, fetching = true)
        val bytes = torrents.get().fetchMagnet(uri, 45)
        return Analysis.Torrent(bytes?.let { torrents.get().parse(it) }, uri, fetching = false)
    }

    suspend fun addFile(url: String, name: String?, pageUrl: String?, headers: Map<String, String>, connections: Int, start: Boolean): Long =
        manager.add(url, name, headers, pageUrl, connections.coerceIn(1, 32), start).also { reset() }

    suspend fun addTorrent(meta: TorrentMeta?, magnet: String?, selected: Set<Int>, sequential: Boolean, start: Boolean): Long =
        manager.addTorrent(meta, magnet, selected, sequential, start).also { reset() }

    suspend fun addVideo(url: String, title: String, format: String, args: String?, headers: Map<String, String>, size: Long, start: Boolean): Long =
        manager.addVideo(url, title, format, args, headers, size, start).also { reset() }

    /** An earlier download of what [a] points at, if any. */
    suspend fun duplicateOf(a: Analysis, input: String): com.aiyu.pull.data.DownloadEntity? = when (a) {
        is Analysis.FileLink -> manager.findDuplicate(listOf(input, a.info.finalUrl), null)
        is Analysis.Video -> manager.findDuplicate(listOf(input), null)
        is Analysis.Torrent -> {
            val hash = a.meta?.infoHash ?: a.magnet?.let { Regex("""btih:([0-9a-fA-F]{40})""").find(it)?.groupValues?.get(1) }
            if (hash != null) manager.findDuplicate(emptyList(), hash) else null
        }
        else -> null
    }

    fun reset() {
        job?.cancel()
        analyzed = null
        _state.value = Analysis.Idle
    }
}
