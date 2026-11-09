package com.aiyu.pull.video

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.aiyu.pull.data.DownloadDao
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.download.Transfer
import com.aiyu.pull.engine.DownloadException
import com.aiyu.pull.engine.ErrorKind
import com.aiyu.pull.engine.Progress
import com.aiyu.pull.engine.RemoteInfo
import com.aiyu.pull.torrent.TorrentEngine
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One downloadable format of a video, for the quality picker. */
data class VideoFormat(
    val id: String,
    val label: String,
    val ext: String,
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val sizeBytes: Long,
    val width: Int = 0,
    val fps: Int = 0,
    val vcodec: String = "",
    val acodec: String = "",
    /** Total and audio bitrate in kbit/s; 0 when unknown. */
    val tbr: Int = 0,
    val abr: Int = 0,
) {
    /** Shorter side, so a vertical 1080x1920 video counts as 1080p like a landscape one. */
    val res: Int get() = if (width > 0 && height > 0) minOf(width, height) else height
}

data class VideoMeta(val title: String, val durationSeconds: Int, val thumbnail: String?, val formats: List<VideoFormat>)

/** yt-dlp release channels, as in Seal. */
enum class YtDlpChannel { STABLE, NIGHTLY, MASTER }

/**
 * yt-dlp (with its own Python, FFmpeg and aria2c) through youtubedl-android, the library Seal uses.
 * Everything starts lazily: the runtime unpacks once on first use (a few seconds).
 */
@Singleton
class VideoEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val torrents: TorrentEngine,
) {
    private val initLock = Mutex()
    @Volatile private var ready = false

    suspend fun ensureReady() = initLock.withLock {
        if (ready) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            Aria2c.getInstance().init(context)
        }
        ready = true
    }

    suspend fun info(url: String): VideoMeta {
        ensureReady()
        val info = withContext(Dispatchers.IO) { YoutubeDL.getInstance().getInfo(url) }
        val formats = info.formats.orEmpty().mapNotNull { f ->
            val id = f.formatId ?: return@mapNotNull null
            val hasVideo = f.vcodec != null && f.vcodec != "none"
            val hasAudio = f.acodec != null && f.acodec != "none"
            if (!hasVideo && !hasAudio) return@mapNotNull null // storyboards and the like
            VideoFormat(
                id = id,
                label = f.formatNote ?: f.format ?: id,
                ext = f.ext ?: "",
                height = f.height,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                sizeBytes = if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate,
                width = f.width,
                fps = f.fps,
                vcodec = f.vcodec?.takeIf { hasVideo }.orEmpty(),
                acodec = f.acodec?.takeIf { hasAudio }.orEmpty(),
                tbr = f.tbr,
                abr = f.abr,
            )
        }
        return VideoMeta(info.title ?: url, info.duration, info.thumbnail, formats)
    }

    suspend fun version(): String? {
        ensureReady()
        return withContext(Dispatchers.IO) { YoutubeDL.getInstance().version(context) }
    }

    /** Returns the new version, or null when already current. */
    suspend fun update(channel: YtDlpChannel): String? {
        ensureReady()
        return withContext(Dispatchers.IO) {
            val ch = when (channel) {
                YtDlpChannel.STABLE -> YoutubeDL.UpdateChannel._STABLE
                YtDlpChannel.NIGHTLY -> YoutubeDL.UpdateChannel._NIGHTLY
                YtDlpChannel.MASTER -> YoutubeDL.UpdateChannel._MASTER
            }
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, ch)
            if (status == YoutubeDL.UpdateStatus.DONE) YoutubeDL.getInstance().version(context) else null
        }
    }

    fun defaultSaveDir(): File = torrents.defaultSaveDir()

    fun transfer(e: DownloadEntity): Transfer = VideoTransfer(e, this, dao, context)

    companion object {
        /** Best video + best audio, merged; falls back to the best single file. */
        const val BEST = "bv*+ba/b"
        const val AUDIO_ONLY = "ba/b"

        private val videoHosts = listOf(
            "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitter.com", "x.com", "instagram.com",
            "tiktok.com", "facebook.com", "fb.watch", "reddit.com", "twitch.tv", "soundcloud.com", "bilibili.com",
            "bandcamp.com", "streamable.com",
        )

        /** Sites where a link is a page with a video on it, so yt-dlp handles it instead of the HTTP engine. */
        fun isVideoSite(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
            return videoHosts.any { host == it || host.endsWith(".$it") }
        }
    }
}

private class VideoTransfer(
    private val e: DownloadEntity,
    private val engine: VideoEngine,
    private val dao: DownloadDao,
    private val context: Context,
) : Transfer {
    private val _progress = MutableStateFlow(Progress(null, e.downloadedBytes, 0, emptyList()))
    override val progress: StateFlow<Progress> = _progress
    private val processId = "pull-${e.id}"
    private val tag = "[pull${e.id}]"

    override suspend fun run(): Long {
        engine.ensureReady()
        val dir = File(e.saveDir ?: engine.defaultSaveDir().path).apply { mkdirs() }
        val request = YoutubeDLRequest(e.url).apply {
            addOption("-f", e.videoFormat ?: VideoEngine.BEST)
            // The tag finds the finished file again; continue + part files make pause/resume and process death safe.
            addOption("-o", File(dir, "%(title).150B $tag.%(ext)s").path)
            addOption("--continue")
            addOption("--no-mtime")
            addOption("--merge-output-format", "mp4/mkv")
            addOption("--newline")
            e.headers["User-Agent"]?.let { addOption("--user-agent", it) }
            e.headers["Referer"]?.let { addOption("--referer", it) }
            e.extraArgs?.let { addCommands(splitArgs(it)) }
        }
        val response = try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    YoutubeDL.getInstance().execute(request, processId) { percent, _, line -> onLine(percent, line) }
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { runCatching { YoutubeDL.getInstance().destroyProcessById(processId) } }
            throw c
        } catch (t: Throwable) {
            val msg = t.message.orEmpty().lines().lastOrNull { it.contains("ERROR") } ?: t.message ?: "yt-dlp failed"
            throw DownloadException(ErrorKind.HTTP, msg.substringAfter("ERROR: ").take(300), cause = t)
        }
        if (response.exitCode != 0) throw DownloadException(ErrorKind.HTTP, response.err.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed")
        val out = dir.listFiles { f -> f.name.contains(tag) && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() } ?: throw IOException("yt-dlp finished but the file is missing")
        val clean = com.aiyu.pull.download.uniqueFile(dir, out.name.replace(" $tag", "")).let { if (out.renameTo(it)) it else out }
        dao.setFile(e.id, Uri.fromFile(clean).toString(), clean.name, null)
        val size = clean.length()
        _progress.value = Progress(_progress.value.info?.copy(totalBytes = size), size, 0, emptyList())
        return size
    }

    /** yt-dlp progress lines: `[download]  42.1% of ~ 120.33MiB at  3.21MiB/s ETA 00:31`. */
    private fun onLine(percent: Float, line: String) {
        val m = Regex("""of\s+~?\s*([\d.]+)([KMGT]?i?B)\s+at\s+([\d.]+)([KMGT]?i?B)/s""").find(line)
        val total = m?.let { bytes(it.groupValues[1], it.groupValues[2]) } ?: _progress.value.info?.totalBytes ?: -1
        val speed = m?.let { bytes(it.groupValues[3], it.groupValues[4]) } ?: _progress.value.bytesPerSecond
        val done = if (total > 0 && percent >= 0) (total * percent / 100).toLong() else _progress.value.downloadedBytes
        _progress.value = Progress(RemoteInfo(e.url, total, true, null, null, e.fileName, null), done, speed, emptyList())
    }

    override suspend fun save(activeDelta: Long) {
        val p = _progress.value
        val total = p.info?.totalBytes ?: -1
        if (total > 0) dao.saveSimpleProgress(e.id, p.downloadedBytes, total, activeDelta) else dao.addActiveTime(e.id, activeDelta)
    }

    override suspend fun publish() {
        dao.get(e.id)?.fileUri?.let { Uri.parse(it).path }?.let { MediaScannerConnection.scanFile(context, arrayOf(it), null, null) }
    }

    private fun bytes(n: String, unit: String): Long {
        val v = n.toDoubleOrNull() ?: return -1
        val k = when (unit.first()) { 'K' -> 1024.0; 'M' -> 1024.0 * 1024; 'G' -> 1024.0 * 1024 * 1024; 'T' -> 1024.0 * 1024 * 1024 * 1024; else -> 1.0 }
        return (v * k).toLong()
    }
}

/** Shell-like split: spaces separate, quotes group. */
fun splitArgs(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    var any = false
    for (ch in s) {
        when {
            quote != null && ch == quote -> quote = null
            quote == null && (ch == '"' || ch == '\'') -> { quote = ch; any = true }
            quote == null && ch.isWhitespace() -> { if (cur.isNotEmpty() || any) out += cur.toString(); cur.clear(); any = false }
            else -> cur.append(ch)
        }
    }
    if (cur.isNotEmpty() || any) out += cur.toString()
    return out
}
