package com.aiyu.pull.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOptionsTest {
    @Test fun bestByDefault() {
        assertEquals("bv*+ba/b" to "--embed-metadata", VideoOptions.build(VideoChoice()))
    }

    @Test fun resolutionAndCodecSort() {
        val (f, a) = VideoOptions.build(VideoChoice(maxRes = 1080, codec = VideoCodec.AVC, container = Container.MP4, metadata = false))
        assertEquals("bv*+ba/b", f)
        assertEquals("-S res:1080,vcodec:h264 --merge-output-format mp4", a)
    }

    @Test fun audioConvertsAndSkipsVideoOptions() {
        val (f, a) = VideoOptions.build(VideoChoice(mode = VideoMode.AUDIO, maxRes = 720, audio = AudioFormat.MP3, subtitles = true, metadata = false))
        assertEquals("ba/b", f)
        assertEquals("-x --audio-format mp3", a)
    }

    @Test fun pickedFormatsReplaceSorting() {
        val (f, a) = VideoOptions.build(VideoChoice(maxRes = 720, sponsorBlock = true, metadata = false), "137+140")
        assertEquals("137+140", f)
        assertEquals("--sponsorblock-remove default", a)
    }

    @Test fun argsSurviveSplit() {
        val (_, a) = VideoOptions.build(VideoChoice(subtitles = true, subtitleLangs = "en.*,ja", thumbnail = true))
        assertEquals(
            listOf("--write-subs", "--write-auto-subs", "--sub-langs", "en.*,ja", "--embed-subs", "--embed-thumbnail", "--embed-metadata"),
            splitArgs(a),
        )
    }
}
