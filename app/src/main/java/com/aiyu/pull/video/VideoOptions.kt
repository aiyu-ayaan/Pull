package com.aiyu.pull.video

/** Codec to prefer (yt-dlp `-S vcodec:`); ANY keeps yt-dlp's own order (best quality first). */
enum class VideoCodec(val sort: String?) { ANY(null), AV1("vcodec:av01"), VP9("vcodec:vp9"), AVC("vcodec:h264") }

/** Container for merged video+audio; AUTO lets yt-dlp pick MP4 when the codecs allow it, else MKV. */
enum class Container(val merge: String?) { AUTO(null), MP4("mp4"), MKV("mkv") }

/** Audio-only output; BEST keeps the original stream without converting. */
enum class AudioFormat(val ytdlp: String?) { BEST(null), M4A("m4a"), MP3("mp3"), OPUS("opus") }

enum class VideoMode { VIDEO, AUDIO }

/** Everything that decides how yt-dlp fetches one video: the saved defaults, changed per download in the sheet. */
data class VideoChoice(
    val mode: VideoMode = VideoMode.VIDEO,
    /** Shorter side in pixels (720 = 720p, also for vertical video); 0 = best available. */
    val maxRes: Int = 0,
    val codec: VideoCodec = VideoCodec.ANY,
    val container: Container = Container.AUTO,
    val audio: AudioFormat = AudioFormat.BEST,
    val subtitles: Boolean = false,
    val subtitleLangs: String = "en.*",
    val thumbnail: Boolean = false,
    val metadata: Boolean = true,
    val sponsorBlock: Boolean = false,
)

object VideoOptions {
    /**
     * The yt-dlp `-f` selector and extra arguments for [c]. [formatIds] (e.g. `137+140`) are formats picked by hand,
     * which replace the quality and codec preferences.
     */
    fun build(c: VideoChoice, formatIds: String? = null): Pair<String, String> {
        val args = mutableListOf<String>()
        val format = formatIds ?: if (c.mode == VideoMode.AUDIO) VideoEngine.AUDIO_ONLY else VideoEngine.BEST
        if (formatIds == null && c.mode == VideoMode.VIDEO) {
            // `res` sorts by the shorter side, so "1080p" means the same for landscape and vertical video.
            val sort = listOfNotNull(c.maxRes.takeIf { it > 0 }?.let { "res:$it" }, c.codec.sort)
            if (sort.isNotEmpty()) args += listOf("-S", sort.joinToString(","))
        }
        if (c.mode == VideoMode.AUDIO) {
            args += "-x"
            c.audio.ytdlp?.let { args += listOf("--audio-format", it) }
        } else {
            c.container.merge?.let { args += listOf("--merge-output-format", it) }
            if (c.subtitles) args += listOf("--write-subs", "--write-auto-subs", "--sub-langs", c.subtitleLangs, "--embed-subs")
        }
        if (c.thumbnail) args += "--embed-thumbnail"
        if (c.metadata) args += "--embed-metadata"
        if (c.sponsorBlock) args += listOf("--sponsorblock-remove", "default")
        return format to args.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
    }
}
