package com.aiyu.pull.browser

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Encapsulates the runtime state of a single browser tab, holding its [WebView]
 * and reactive UI properties.
 */
class BrowserTab(
    val id: Long,
    val webView: WebView,
    initialUrl: String = "",
    initialTitle: String = "",
    var isPopupCandidate: Boolean = false,
    val isIncognito: Boolean = false,
    /** Tab that opened this one (popup, "open in new tab"). Back on its first page returns there. */
    val openerId: Long? = null,
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var favicon by mutableStateOf<Bitmap?>(null)
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    /** The new tab page covers the WebView. Never driven by about:blank, so redirects cannot flash it. */
    var showNtp by mutableStateOf(initialUrl.isBlank() || initialUrl == "about:blank")
    /** Small screenshot of the page for the tab switcher and the "continue" card. */
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    /** Last time this tab was shown, to pick the tab to continue with. */
    var lastShown by mutableStateOf(System.currentTimeMillis())
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blockedCount by mutableIntStateOf(0)
    val detectedMedia = mutableStateListOf<DetectedMedia>()
    var isDesktopMode by mutableStateOf(false)
        private set
    /** A link with a file extension that turned out to be a web page: the next navigation to it is not intercepted. */
    var passThroughUrl: String? = null

    private val defaultUserAgent: String = webView.settings.userAgentString

    /** Records a unique sniffed media URL on this tab. */
    fun recordMedia(mediaUrl: String, mimeType: String? = null) {
        if (!isSniffableMediaUrl(mediaUrl, mimeType)) return
        if (detectedMedia.any { it.url == mediaUrl }) return
        detectedMedia.add(
            DetectedMedia(
                url = mediaUrl,
                typeLabel = mediaTypeLabel(mediaUrl, mimeType),
                pageTitle = displayTitle,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    /** Clears sniffed media URLs (e.g. when navigating to a new page). */
    fun clearDetectedMedia() {
        detectedMedia.clear()
    }

    /** Switches this tab between mobile and desktop user-agent and viewport settings. */
    fun applyDesktopMode(enabled: Boolean) {
        if (isDesktopMode == enabled) return
        isDesktopMode = enabled
        webView.settings.apply {
            userAgentString = if (enabled) DESKTOP_USER_AGENT else defaultUserAgent
            useWideViewPort = enabled
            loadWithOverviewMode = enabled
        }
        if (url.isNotBlank() && url != "about:blank") {
            webView.reload()
        }
    }

    val hasPage: Boolean get() = url.isNotBlank() && url != "about:blank"

    /** Scaled screenshot of the WebView; keeps the old one when the view is not laid out. */
    fun captureThumbnail() {
        if (showNtp || !hasPage) return
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return
        runCatching {
            val scale = 0.35f
            val bmp = android.graphics.Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), android.graphics.Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
            webView.draw(canvas)
            thumbnail = bmp.asImageBitmap()
        }
    }

    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            url.isNotBlank() && url != "about:blank" -> runCatching {
                Uri.parse(url).host ?: url
            }.getOrDefault(url)
            else -> webView.context.getString(com.aiyu.pull.R.string.browser_new_tab)
        }

    fun destroy() {
        try {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }
}
