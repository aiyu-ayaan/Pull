package com.aiyu.pull.browser

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var favicon by mutableStateOf<Bitmap?>(null)
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blockedCount by mutableIntStateOf(0)

    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            url.isNotBlank() && url != "about:blank" -> runCatching {
                Uri.parse(url).host ?: url
            }.getOrDefault(url)
            else -> "New tab"
        }

    fun destroy() {
        try {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }
}
