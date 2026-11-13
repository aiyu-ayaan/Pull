package com.aiyu.pull.ui.settings

import com.aiyu.pull.data.BookmarkEntity
import com.aiyu.pull.data.Encryption
import com.aiyu.pull.data.SearchEngine
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.ShortcutEntity
import com.aiyu.pull.data.ThemeMode
import com.aiyu.pull.update.UpdateChannel
import com.aiyu.pull.video.AudioFormat
import com.aiyu.pull.video.Container
import com.aiyu.pull.video.VideoChoice
import com.aiyu.pull.video.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRestoreTest {
    @Test
    fun roundTripSettingsBookmarksAndShortcuts() {
        val originalSettings = Settings(
            themeMode = ThemeMode.DARK,
            dynamicColor = true,
            connections = 16,
            maxDownloads = 5,
            autoResume = false,
            scheduleEnabled = true,
            scheduleStartHour = 22,
            scheduleEndHour = 6,
            torrentSequential = true,
            ytdlpChannel = "NIGHTLY",
            ytdlpAutoUpdate = false,
            torrentDht = false,
            torrentLsd = true,
            torrentUtp = false,
            torrentPortMapping = true,
            torrentEncryption = Encryption.FORCED,
            torrentDownLimit = 1024,
            torrentUpLimit = 512,
            torrentConnections = 300,
            torrentSeed = true,
            torrentSeedRatio = 200,
            torrentDefaultTrackers = "udp://tracker.example.com:80",
            video = VideoChoice(
                maxRes = 1080,
                codec = VideoCodec.AV1,
                container = Container.MKV,
                audio = AudioFormat.OPUS,
                subtitles = true,
                subtitleLangs = "en.*",
                thumbnail = true,
                metadata = false,
                sponsorBlock = true,
            ),
            videoMultiConnection = false,
            updatesEnabled = false,
            updateChannel = UpdateChannel.BETA,
            browserAdBlock = true,
            browserPopupBlock = false,
            browserCosmetic = true,
            browserSearchEngine = SearchEngine.DUCKDUCKGO,
            browserInterceptByExtension = false,
        )
        val bookmarks = listOf(
            BookmarkEntity(id = 10, url = "https://example.com/releases", title = "Example Releases", createdAt = 1700000000000L),
            BookmarkEntity(id = 11, url = "https://kernel.org", title = "Linux Kernel", createdAt = 1700000050000L),
        )
        val shortcuts = listOf(
            ShortcutEntity(id = 5, url = "https://archive.org", title = "Internet Archive", createdAt = 1700000100000L),
        )

        val json = BackupRestore.serialize(originalSettings, bookmarks, shortcuts)
        val restored = BackupRestore.deserialize(json)

        assertEquals(1, restored.version)
        assertEquals(originalSettings, restored.settings)
        assertEquals(2, restored.bookmarks.size)
        assertEquals("https://example.com/releases", restored.bookmarks[0].url)
        assertEquals("Example Releases", restored.bookmarks[0].title)
        assertEquals(1700000000000L, restored.bookmarks[0].createdAt)
        assertEquals(1, restored.shortcuts.size)
        assertEquals("https://archive.org", restored.shortcuts[0].url)
        assertEquals("Internet Archive", restored.shortcuts[0].title)
    }

    @Test
    fun deserializesMinimalJsonWithDefaults() {
        val restored = BackupRestore.deserialize("""{"version":1}""")
        assertEquals(Settings(), restored.settings)
        assertEquals(emptyList<BookmarkEntity>(), restored.bookmarks)
        assertEquals(emptyList<ShortcutEntity>(), restored.shortcuts)
    }
}
