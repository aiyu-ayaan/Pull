package com.aiyu.pull.browser.adblock

import android.content.Context
import java.util.Base64
import android.webkit.WebResourceResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdBlocker(
    private val assetLoader: (() -> InputStream?)?,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        assetLoader = {
            runCatching { context.assets.open("adblock_domains.txt") }.getOrNull()
        },
    )

    private val domains = HashSet<String>(4096)

    init {
        loadStarterDomains()
        loadAssetDomains()
    }

    private fun loadStarterDomains() {
        domains.addAll(
            listOf(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com",
                "criteo.com", "outbrain.com", "taboola.com", "amazon-adsystem.com", "rubiconproject.com",
                "pubmatic.com", "openx.net", "casalemedia.com", "advertising.com", "moatads.com",
                "bidswitch.net", "contextweb.com", "smartadserver.com", "revcontent.com", "mgid.com",
                "zergnet.com", "scorecardresearch.com", "quantserve.com", "chartbeat.com", "hotjar.com",
                "google-analytics.com", "analytics.google.com", "popads.net", "popcash.net",
                "propellerads.com", "adsterra.com", "exoclick.com", "trafficjunky.com", "juicyads.com",
                "hilltopads.com", "clickadu.com", "monetag.com", "onclickads.net", "ad-delivery.net",
                "onclickalgo.com", "admaven.com", "adxprts.com", "directrev.com", "adtrue.com",
                "ad-maven.com", "popunder.net", "popunder.com", "clickorcontinue.com", "pushassist.com",
                "propu.sh", "propush.me", "adpushup.com", "richpush.com", "evadav.com", "rollerads.com",
                "clickaine.com", "bidvertiser.com", "clicksor.com", "adsupply.com", "adbuffs.com",
                "propellerclick.com", "bet365.com", "1xbet.com", "stake.com", "coinhive.com",
                "vdo.ai", "yieldmo.com", "indexexchange.com", "triplelift.com", "gumgum.com",
            ),
        )
    }

    private fun loadAssetDomains() {
        try {
            val stream = assetLoader?.invoke() ?: return
            stream.use { s ->
                BufferedReader(InputStreamReader(s)).forEachLine { line ->
                    val trimmed = line.trim().lowercase()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                        domains.add(trimmed)
                    }
                }
            }
        } catch (_: Exception) {
            // Keep starter domains if assets are unavailable
        }
    }

    /**
     * Returns true if [url] points to a known ad, tracker, popup, or malicious redirect domain.
     */
    fun isBlocked(url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        val host = extractHost(url) ?: return false
        if (isAdDomain(host)) return true

        val path = extractPath(url)
        return path.contains("/pagead/") ||
            path.contains("/adservice/") ||
            path.contains("/ad-delivery/") ||
            path.contains("/popunder") ||
            path.contains("/popup.php")
    }

    /**
     * Checks if [host] matches any blocked domain or subdomain.
     */
    fun isAdDomain(host: String): Boolean {
        var h = host.trimEnd('.').substringBefore(':')
        while (h.isNotEmpty()) {
            if (domains.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        return false
    }

    fun extractHost(url: String): String? {
        val clean = url.trim()
        val afterScheme = clean.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPort.substringBefore(':').lowercase().trimEnd('.')
        return host.ifEmpty { null }
    }

    fun extractPath(url: String): String {
        val clean = url.trim()
        val afterScheme = clean.substringAfter("://", "")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return ""
        return afterScheme.substring(slash).substringBefore('?').substringBefore('#').lowercase()
    }

    /**
     * Generates a 200 OK empty response with the appropriate MIME type.
     */
    fun createBlockedResponse(url: String): WebResourceResponse {
        val lower = url.lowercase().substringBefore('?')
        val mime = when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".json") -> "application/json"
            else -> "text/plain"
        }
        val data = if (mime.startsWith("image/")) TRANSPARENT_1X1_PNG else EMPTY_BYTES
        return WebResourceResponse(
            mime,
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(data),
        )
    }

    companion object {
        private val EMPTY_BYTES = ByteArray(0)
        private val TRANSPARENT_1X1_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        )

        /**
         * CSS rules to hide ad containers, sponsored elements, and transparent click-jack overlays.
         */
        const val COSMETIC_CSS = """
            [id*="google_ads"], [id*="banner"], [class*="ad-banner"], [class*="adsbox"],
            [class*="ad_box"], [class*="ad-container"], [class*="ad-wrapper"],
            [class*="advertisement"], [class*="sponsor"], [class*="taboola"],
            [class*="outbrain"], [class*="mgid"], ins.adsbygoogle,
            iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
            iframe[src*="adsterra"], iframe[src*="exoclick"], iframe[src*="popads"],
            div[data-ad-unit], div[data-ad-client], .ad-overlay, .popunder,
            .popup-overlay, #popup-overlay, .ad-modal {
                display: none !important;
                visibility: hidden !important;
                opacity: 0 !important;
                pointer-events: none !important;
                height: 0 !important;
                max-height: 0 !important;
                width: 0 !important;
                max-width: 0 !important;
            }
        """

        /**
         * JavaScript snippet injected into the page to install cosmetic CSS and suppress popup bursts.
         */
        val INJECT_COSMETIC_SCRIPT = """
            (function() {
                try {
                    if (!document.getElementById('pull-adblock-style')) {
                        var style = document.createElement('style');
                        style.id = 'pull-adblock-style';
                        style.textContent = ${"\"" + COSMETIC_CSS.replace("\n", " ").replace("\"", "\\\"") + "\""};
                        (document.head || document.documentElement).appendChild(style);
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        /**
         * JavaScript snippet to throttle and block aggressive window.open popup loops.
         */
        val POPUP_SHIELD_SCRIPT = """
            (function() {
                try {
                    if (window.__pull_popup_shield_installed) return;
                    window.__pull_popup_shield_installed = true;
                    var origOpen = window.open;
                    var lastOpenTime = 0;
                    window.open = function(url, target, features) {
                        var now = Date.now();
                        if (now - lastOpenTime < 1000) {
                            return null;
                        }
                        lastOpenTime = now;
                        return origOpen.apply(this, arguments);
                    };
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
