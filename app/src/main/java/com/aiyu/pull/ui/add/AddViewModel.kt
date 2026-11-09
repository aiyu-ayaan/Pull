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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val client: OkHttpClient,
    settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<Analysis>(Analysis.Idle)
    val state: StateFlow<Analysis> = _state.asStateFlow()
    val connections: StateFlow<Int> = settings.settings.map { it.connections }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val templates: StateFlow<List<CommandTemplate>> = settings.templates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
                    isVideoSite(input) -> { _state.value = Analysis.Loading(R.string.analyze_video); Analysis.Video(videos.get().info(input)) }
                    else -> {
                        _state.value = Analysis.Loading(R.string.probe_loading)
                        val info = manager.probe(input, headers)
                        when {
                            info.mimeType == "application/x-bittorrent" || info.fileName?.endsWith(".torrent", true) == true ->
                                Analysis.Torrent(torrents.get().parse(fetchSmall(info.finalUrl, headers)), null, false)
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
                Analysis.Failed(e.message?.lines()?.lastOrNull { it.isNotBlank() }?.substringAfter("ERROR: ") ?: context.getString(R.string.probe_failed))
            }
        }
    }

    /** The page was HTML: let yt-dlp look for a video on it. */
    fun findVideos(url: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = Analysis.Loading(R.string.analyze_video)
            _state.value = try {
                Analysis.Video(videos.get().info(url))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                Analysis.Failed(e.message?.lines()?.lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR: ") ?: context.getString(R.string.analyze_no_video))
            }
        }
    }

    private suspend fun fetchMagnet(uri: String): Analysis {
        _state.value = Analysis.Torrent(null, uri, fetching = true)
        val bytes = torrents.get().fetchMagnet(uri, 45)
        return Analysis.Torrent(bytes?.let { torrents.get().parse(it) }, uri, fetching = false)
    }

    private suspend fun fetchSmall(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            val body = r.body
            if (!r.isSuccessful || body.contentLength() > 20L shl 20) throw java.io.IOException("Cannot fetch the torrent file")
            body.bytes()
        }
    }

    suspend fun addFile(url: String, name: String?, pageUrl: String?, headers: Map<String, String>, connections: Int, start: Boolean): Long =
        manager.add(url, name, headers, pageUrl, connections.coerceIn(1, 32), start).also { reset() }

    suspend fun addTorrent(meta: TorrentMeta?, magnet: String?, selected: Set<Int>, sequential: Boolean, start: Boolean): Long =
        manager.addTorrent(meta, magnet, selected, sequential, start).also { reset() }

    suspend fun addVideo(url: String, title: String, format: String, args: String?, headers: Map<String, String>, size: Long, start: Boolean): Long =
        manager.addVideo(url, title, format, args, headers, size, start).also { reset() }

    fun reset() {
        job?.cancel()
        analyzed = null
        _state.value = Analysis.Idle
    }

    companion object {
        private val videoHosts = listOf(
            "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitter.com", "x.com", "instagram.com",
            "tiktok.com", "facebook.com", "fb.watch", "reddit.com", "twitch.tv", "soundcloud.com", "bilibili.com",
            "bandcamp.com", "streamable.com",
        )

        fun isVideoSite(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
            return videoHosts.any { host == it || host.endsWith(".$it") }
        }
    }
}
