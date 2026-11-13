package com.aiyu.pull.browser

import com.aiyu.pull.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUtilsTest {

    @Test
    fun resolveAddressInputPreservesExplicitSchemes() {
        assertEquals("https://example.com", resolveAddressInput("https://example.com", SearchEngine.GOOGLE))
        assertEquals("http://example.com/path", resolveAddressInput("  http://example.com/path  ", SearchEngine.DUCKDUCKGO))
        assertEquals("about:blank", resolveAddressInput("about:blank", SearchEngine.BING))
    }

    @Test
    fun resolveAddressInputDetectsBareHostsAndLocalAddresses() {
        assertEquals("https://example.com", resolveAddressInput("example.com", SearchEngine.GOOGLE))
        assertEquals("https://github.com/aiyu-ayaan/Pull", resolveAddressInput("github.com/aiyu-ayaan/Pull", SearchEngine.GOOGLE))
        assertEquals("http://localhost:8080/test", resolveAddressInput("localhost:8080/test", SearchEngine.GOOGLE))
        assertEquals("http://192.168.1.1", resolveAddressInput("192.168.1.1", SearchEngine.GOOGLE))
    }

    @Test
    fun resolveAddressInputUsesSelectedSearchEngineForQueries() {
        assertEquals(
            "https://www.google.com/search?q=kotlin+coroutines",
            resolveAddressInput("kotlin coroutines", SearchEngine.GOOGLE),
        )
        assertEquals(
            "https://duckduckgo.com/?q=pull+downloader",
            resolveAddressInput("pull downloader", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "https://www.bing.com/search?q=android",
            resolveAddressInput("android", SearchEngine.BING),
        )
        assertEquals(
            "https://search.brave.com/search?q=open+source",
            resolveAddressInput("open source", SearchEngine.BRAVE),
        )
        assertEquals(
            "https://www.startpage.com/sp/search?q=privacy",
            resolveAddressInput("privacy", SearchEngine.STARTPAGE),
        )
    }

    @Test
    fun resolveAddressInputReturnsEmptyForBlank() {
        assertEquals("", resolveAddressInput("   ", SearchEngine.GOOGLE))
    }

    @Test
    fun shouldInterceptByExtensionMatchesDownloadableFiles() {
        assertTrue(shouldInterceptByExtension("https://example.com/archive.zip"))
        assertTrue(shouldInterceptByExtension("https://example.com/app-release.APK?dl=1#top"))
        assertTrue(shouldInterceptByExtension("https://cdn.example.org/ubuntu-24.04.iso"))
        assertTrue(shouldInterceptByExtension("https://example.com/video.mp4"))
        assertTrue(shouldInterceptByExtension("https://example.com/paper.pdf"))
        assertTrue(shouldInterceptByExtension("https://example.com/linux.torrent"))
    }

    @Test
    fun shouldInterceptByExtensionIgnoresWebPagesAndNonHttpSchemes() {
        assertFalse(shouldInterceptByExtension("https://example.com/index.html"))
        assertFalse(shouldInterceptByExtension("https://example.com/page.php?id=10"))
        assertFalse(shouldInterceptByExtension("https://example.com/downloads/"))
        assertFalse(shouldInterceptByExtension("https://example.com"))
        assertFalse(shouldInterceptByExtension("file:///sdcard/archive.zip"))
        assertFalse(shouldInterceptByExtension("about:blank"))
    }

    @Test
    fun extractFileExtensionHandlesEdgeCases() {
        assertEquals("tar", extractFileExtension("https://example.com/source.tar"))
        assertEquals("gz", extractFileExtension("https://example.com/source.tar.gz?v=2"))
        assertNull(extractFileExtension("https://example.com/.hidden"))
        assertNull(extractFileExtension("https://example.com/trailing."))
        assertNull(extractFileExtension("https://example.com/noext"))
    }
}
