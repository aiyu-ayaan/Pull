package com.aiyu.pull.browser

import com.aiyu.pull.browser.adblock.AdBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class AdBlockerTest {
    private val adBlocker = AdBlocker {
        val assetFile = File("src/main/assets/adblock_domains.txt")
        if (assetFile.exists()) FileInputStream(assetFile) else null
    }

    @Test
    fun blocksKnownAdAndTrackerDomains() {
        assertTrue(adBlocker.isBlocked("https://googleads.g.doubleclick.net/pagead/ads?client=ca-pub-123"))
        assertTrue(adBlocker.isBlocked("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(adBlocker.isBlocked("https://adservice.google.com/adsid/integrator.js"))
        assertTrue(adBlocker.isBlocked("https://popads.net/serve/pop.js"))
        assertTrue(adBlocker.isBlocked("https://serve.popcash.net/click"))
        assertTrue(adBlocker.isBlocked("https://adsterra.com/direct/link"))
        assertTrue(adBlocker.isBlocked("https://c.exoclick.com/tag.js"))
    }

    @Test
    fun allowsLegitimateSites() {
        assertFalse(adBlocker.isBlocked("https://duckduckgo.com"))
        assertFalse(adBlocker.isBlocked("https://github.com/aiyu-ayaan/Pull"))
        assertFalse(adBlocker.isBlocked("https://en.wikipedia.org/wiki/Main_Page"))
        assertFalse(adBlocker.isBlocked("https://www.google.com/search?q=kotlin"))
        assertFalse(adBlocker.isBlocked("https://instagram.com/reels/123"))
        assertFalse(adBlocker.isBlocked("about:blank"))
    }

    @Test
    fun matchesSubdomainsRecursively() {
        assertTrue(adBlocker.isAdDomain("sub.doubleclick.net"))
        assertTrue(adBlocker.isAdDomain("deep.nested.sub.popads.net"))
        assertFalse(adBlocker.isAdDomain("google.com"))
        assertFalse(adBlocker.isAdDomain("github.io"))
    }

    @Test
    fun extractsHostAndPathCorrectly() {
        assertEquals("duckduckgo.com", adBlocker.extractHost("https://duckduckgo.com/?q=pull"))
        assertEquals("googleads.g.doubleclick.net", adBlocker.extractHost("https://googleads.g.doubleclick.net:443/pagead/ads"))
        assertEquals("/pagead/ads", adBlocker.extractPath("https://doubleclick.net/pagead/ads?foo=bar"))
    }
}
