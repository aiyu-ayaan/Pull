package com.aiyu.pull.browser

import com.aiyu.pull.browser.adblock.AdBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

class AdBlockerTest {
    private val adBlocker = AdBlocker {
        val txtFile = File("src/main/assets/adblock_domains.txt")
        if (txtFile.exists()) FileInputStream(txtFile) else null
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
        assertTrue(adBlocker.isBlocked("https://cdn.taboola.com/libtrc/unip/1/tfa.js"))
        assertTrue(adBlocker.isBlocked("https://widgets.outbrain.com/outbrain.js"))
        assertTrue(adBlocker.isBlocked("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
    }

    @Test
    fun allowsLegitimateSites() {
        assertFalse(adBlocker.isBlocked("https://example.com"))
        assertFalse(adBlocker.isBlocked("https://github.com/aiyu-ayaan/Pull"))
        assertFalse(adBlocker.isBlocked("https://en.wikipedia.org/wiki/Main_Page"))
        assertFalse(adBlocker.isBlocked("https://www.google.com/search?q=kotlin"))
        assertFalse(adBlocker.isBlocked("https://instagram.com/reels/123"))
        assertFalse(adBlocker.isBlocked("about:blank"))
    }

    @Test
    fun blocksThirdPartyAdSubdomains() {
        assertTrue(adBlocker.isBlocked("https://ads.partnercdn.com/script.js", "https://example.com"))
        assertTrue(adBlocker.isBlocked("https://tracker.analytics-vendor.org/collect", "https://news.com"))
        assertFalse(adBlocker.isBlocked("https://static.example.com/main.js", "https://example.com"))
    }

    @Test
    fun blocksAdPathAndQueryPatterns() {
        assertTrue(adBlocker.isBlocked("https://cdn.example.org/js/prebid.js"))
        assertTrue(adBlocker.isBlocked("https://cdn.example.org/adserver/loader"))
        assertTrue(adBlocker.isBlocked("https://somecdn.net/render?ad_unit=banner_300x250"))
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
        assertEquals("example.com", adBlocker.extractHost("https://example.com/?q=pull"))
        assertEquals("googleads.g.doubleclick.net", adBlocker.extractHost("https://googleads.g.doubleclick.net:443/pagead/ads"))
        assertEquals("/pagead/ads", adBlocker.extractPath("https://doubleclick.net/pagead/ads?foo=bar"))
        assertEquals("foo=bar", adBlocker.extractQuery("https://doubleclick.net/pagead/ads?foo=bar#hash"))
    }
}
