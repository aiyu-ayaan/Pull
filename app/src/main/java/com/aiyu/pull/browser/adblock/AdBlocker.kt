package com.aiyu.pull.browser.adblock

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebResourceResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdBlocker(
    private val assetLoader: (() -> InputStream?)?,
    private val prefs: SharedPreferences? = null,
) {
    constructor(assetLoader: (() -> InputStream?)?) : this(assetLoader, null)

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        assetLoader = {
            runCatching { context.assets.open("adblock_domains.txt") }.getOrNull()
        },
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val domains = HashSet<String>(300_000)
    private val allowlistSet: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val _allowlistedHosts = MutableStateFlow<Set<String>>(emptySet())
    val allowlistedHostsFlow: StateFlow<Set<String>> = _allowlistedHosts.asStateFlow()

    val allowlistedHosts: Set<String>
        get() = _allowlistedHosts.value

    init {
        loadAllowlistedHosts()
        loadStarterDomains()
        loadAssetDomains()
    }

    private fun loadAllowlistedHosts() {
        val saved = runCatching {
            prefs?.getStringSet(KEY_ALLOWLISTED_HOSTS, emptySet()).orEmpty()
        }.getOrDefault(emptySet())
        synchronized(allowlistSet) {
            allowlistSet.clear()
            saved.mapNotNullTo(allowlistSet) { normalizeHost(it) }
            _allowlistedHosts.value = allowlistSet.toSet()
        }
    }

    /** Normalizes a host or URL into a lowercase hostname without port or trailing dot. */
    fun normalizeHost(hostOrUrl: String): String? {
        val trimmed = hostOrUrl.trim()
        if (trimmed.isEmpty() || trimmed == "about:blank") return null
        if (trimmed.contains("://")) {
            return extractHost(trimmed)
        }
        val clean = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':').lowercase().trimEnd('.')
        return clean.ifEmpty { null }
    }

    /** Returns true if [hostOrPageUrl] (or its parent domain) is in [allowlistedHosts]. */
    fun isHostAllowlisted(hostOrPageUrl: String?): Boolean {
        if (hostOrPageUrl.isNullOrBlank()) return false
        var h = normalizeHost(hostOrPageUrl) ?: return false
        val current = _allowlistedHosts.value
        if (current.isEmpty()) return false
        while (h.isNotEmpty()) {
            if (current.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        return false
    }

    /** Adds or removes [host] from the per-site allowlist and persists the change. */
    fun setHostAllowlisted(host: String, allowlisted: Boolean) {
        val normalized = normalizeHost(host) ?: return
        val snapshot: Set<String>
        synchronized(allowlistSet) {
            if (allowlisted) {
                allowlistSet.add(normalized)
            } else {
                allowlistSet.remove(normalized)
            }
            snapshot = allowlistSet.toSet()
            _allowlistedHosts.value = snapshot
        }
        runCatching {
            prefs?.edit()?.putStringSet(KEY_ALLOWLISTED_HOSTS, snapshot)?.apply()
        }
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
                BufferedReader(InputStreamReader(s), 65536).forEachLine { line ->
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
     * Returns true if [url] matches any known ad, tracker, popup, or banner patterns.
     * Optionally takes [pageUrl] to evaluate third-party ad heuristics.
     */
    fun isBlocked(url: String, pageUrl: String? = null): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        if (pageUrl != null && isHostAllowlisted(pageUrl)) return false
        val host = extractHost(url) ?: return false
        if (isHostAllowlisted(host)) return false

        // 1. Direct and hierarchical suffix domain matching against 260,000+ domains
        if (isAdDomain(host)) return true

        // 2. Known ad network substrings in host
        if (host.contains(".doubleclick.") ||
            host.contains(".googlesyndication.") ||
            host.contains(".adnxs.") ||
            host.contains(".criteo.") ||
            host.contains(".taboola.") ||
            host.contains(".outbrain.") ||
            host.contains(".popads.") ||
            host.contains(".exoclick.") ||
            host.contains(".adsterra.") ||
            host.contains(".smartadserver.") ||
            host.contains(".rubiconproject.") ||
            host.contains(".pubmatic.")
        ) {
            return true
        }

        // 3. Ad path and query pattern heuristics
        val path = extractPath(url)
        val query = extractQuery(url)
        if (path.contains("/pagead/") ||
            path.contains("/adservice/") ||
            path.contains("/ad-delivery/") ||
            path.contains("/adserver/") ||
            path.contains("/adsystem/") ||
            path.contains("/gpt.js") ||
            path.contains("/prebid.js") ||
            path.contains("/pubads") ||
            path.contains("/adsbygoogle") ||
            path.contains("/ad-frame") ||
            path.contains("/adbanner") ||
            path.contains("/adsbox") ||
            path.contains("/popunder") ||
            path.contains("/popup.php") ||
            path.contains("/ad-unit") ||
            path.contains("/banner-ad")
        ) {
            return true
        }

        if (query.contains("ad_unit=") ||
            query.contains("ad_slot=") ||
            query.contains("ad_client=") ||
            query.contains("goog_param=") ||
            query.contains("googleads") ||
            query.contains("ad_type=")
        ) {
            return true
        }

        // 4. Third-party ad subdomain heuristics
        if (pageUrl != null) {
            val pageHost = extractHost(pageUrl)
            if (pageHost != null && !isSameDomain(host, pageHost)) {
                if (host.startsWith("ad.") ||
                    host.startsWith("ads.") ||
                    host.startsWith("adserver.") ||
                    host.startsWith("track.") ||
                    host.startsWith("tracker.") ||
                    host.startsWith("analytics.") ||
                    host.startsWith("pixel.") ||
                    host.startsWith("telemetry.")
                ) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Checks if [host] matches any blocked domain or parent domain.
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

    fun extractQuery(url: String): String {
        val clean = url.trim()
        val q = clean.substringAfter('?', "")
        return if (q.isEmpty()) "" else q.substringBefore('#').lowercase()
    }

    fun isSameDomain(host1: String, host2: String): Boolean {
        if (host1 == host2) return true
        val p1 = host1.split('.')
        val p2 = host2.split('.')
        if (p1.size >= 2 && p2.size >= 2) {
            val root1 = "${p1[p1.size - 2]}.${p1[p1.size - 1]}"
            val root2 = "${p2[p2.size - 2]}.${p2[p2.size - 1]}"
            return root1 == root2
        }
        return false
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
        private const val PREFS_NAME = "pull_adblocker_prefs"
        private const val KEY_ALLOWLISTED_HOSTS = "allowlisted_hosts"
        private val EMPTY_BYTES = ByteArray(0)
        private val TRANSPARENT_1X1_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        )

        /**
         * Comprehensive CSS rules to hide ad banners, sponsored sections, sticky ads,
         * recommendation grids, video ad modules, and transparent click-jack overlays.
         */
        const val COSMETIC_CSS = """
            ins.adsbygoogle, [id^="google_ads_"], [id^="div-gpt-ad"],
            [data-ad-client], [data-ad-slot], [data-google-query-id],
            [class*="leaderboard"], [id*="leaderboard"],
            [class*="ad-banner"], [class*="ad_banner"], [id*="ad-banner"], [id*="ad_banner"],
            [class*="banner-ad"], [class*="ad-container"], [class*="ad_container"],
            [class*="ad-wrapper"], [class*="ad_wrapper"], [class*="ad-slot"], [class*="ad_slot"],
            [class*="ad-box"], [class*="ad_box"], [class*="ad-holder"], [class*="ad-zone"],
            [class*="ad-unit"], [class*="ad-card"], [class*="ad-item"], [class*="ad-placement"],
            [class*="sponsored"], [id*="sponsored"], [class*="promoted-post"],
            [class*="promoted-content"], [class*="promoted-link"],
            [class*="taboola"], [id*="taboola"], [class*="outbrain"], [id*="outbrain"],
            [class*="mgid"], [id*="mgid"], [class*="revcontent"], [id*="revcontent"],
            [class*="zergnet"], [id*="zergnet"], [class*="sticky-ad"], [id*="sticky-ad"],
            [class*="floating-ad"], [id*="floating-ad"], [class*="bottom-ad"], [class*="top-ad"],
            [class*="anchor-ad"], [class*="ad-sticky"], [class*="ad-float"], [id*="ad-sticky"],
            .ad-overlay, .popunder, .popup-overlay, #popup-overlay, .ad-modal,
            [class*="click-jack"], [class*="popup-wrapper"], [class*="interstitial"], [id*="interstitial"],
            [class*="ad-curtain"], [id*="ad-curtain"],
            .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-player-overlay, .video-ads,
            [class*="video-ad-overlay"], [class*="ad-interstitial"], [class*="vpaid-ad"],
            iframe[src*="doubleclick"], iframe[src*="googlesyndication"], iframe[src*="adservice"],
            iframe[src*="adserver"], iframe[src*="adsystem"], iframe[src*="adnxs"],
            iframe[src*="amazon-adsystem"], iframe[src*="smartadserver"], iframe[src*="criteo"],
            iframe[src*="openx"], iframe[src*="rubicon"], iframe[src*="pubmatic"],
            iframe[src*="adsterra"], iframe[src*="exoclick"], iframe[src*="popads"] {
                display: none !important;
                visibility: hidden !important;
                opacity: 0 !important;
                pointer-events: none !important;
                height: 0 !important;
                min-height: 0 !important;
                max-height: 0 !important;
                width: 0 !important;
                min-width: 0 !important;
                max-width: 0 !important;
                position: absolute !important;
                top: -9999px !important;
                left: -9999px !important;
            }
        """

        /**
         * Injected JavaScript for cosmetic CSS and auto-skip helpers.
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
                    if (document.body && document.body.style.overflow === 'hidden') {
                        document.body.style.overflow = '';
                    }
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton');
                    if (skipBtn) skipBtn.click();
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
