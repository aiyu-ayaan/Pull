package com.aiyu.pull.browser

import com.aiyu.pull.data.SearchEngine
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Standard desktop Chrome User-Agent used when desktop mode is enabled on a tab. */
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val SCHEME_PREFIXES = listOf("http://", "https://", "about:", "file://", "data:", "javascript:")
private val IPV4_HOST_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""")
private val LOCALHOST_REGEX = Regex("""^localhost(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)

/** Extensions intercepted in `shouldOverrideUrlLoading` when intercept-by-extension is enabled. */
val DOWNLOADABLE_EXTENSIONS: Set<String> = setOf(
    // Archives
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz",
    // Installers & disk images
    "apk", "apkm", "xapk", "exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage", "iso", "img",
    // Documents
    "pdf", "epub", "mobi", "djvu", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv",
    // Media
    "mp4", "mkv", "webm", "avi", "mov", "flv", "mp3", "flac", "aac", "ogg", "wav", "m4a", "opus",
    // Torrents
    "torrent",
)

/** Extensions sniffed from network requests and subresources for media stream detection. */
val SNIFFABLE_MEDIA_EXTENSIONS: Set<String> = setOf(
    "m3u8", "mpd", "mp4", "webm", "mkv", "mp3", "m4a",
)

private val HLS_MIME_TYPES: Set<String> = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
)

private val DASH_MIME_TYPES: Set<String> = setOf(
    "application/dash+xml",
    "video/vnd.mpeg.dash.mpd",
)

private val DIRECT_MEDIA_MIME_TYPES: Map<String, String> = mapOf(
    "video/mp4" to "MP4",
    "video/webm" to "WEBM",
    "video/x-matroska" to "MKV",
    "audio/mpeg" to "MP3",
    "audio/mp3" to "MP3",
    "audio/mp4" to "M4A",
    "audio/x-m4a" to "M4A",
)

/** Represents a media stream or video/audio asset sniffed on a browser tab. */
data class DetectedMedia(
    val url: String,
    val typeLabel: String,
    val pageTitle: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Returns true if [url] or [mimeType] represents a sniffable media stream or file
 * (`m3u8`, `mpd`, `.mp4`, `.webm`, `.mkv`, `.mp3`, `.m4a`).
 */
fun isSniffableMediaUrl(url: String, mimeType: String? = null): Boolean {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false

    val ext = extractFileExtension(trimmed)
    // Ignore HLS/DASH segment chunks (.ts, .m4s) even if served with video MIME types
    if (ext == "ts" || ext == "m4s") return false
    if (ext != null && ext in SNIFFABLE_MEDIA_EXTENSIONS) return true

    val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (!cleanMime.isNullOrEmpty()) {
        if (cleanMime in HLS_MIME_TYPES || cleanMime in DASH_MIME_TYPES || cleanMime in DIRECT_MEDIA_MIME_TYPES) {
            return true
        }
    }
    return false
}

/** Returns a short display label (e.g. `HLS`, `DASH`, `MP4`) for a sniffed media URL. */
fun mediaTypeLabel(url: String, mimeType: String? = null): String {
    val ext = extractFileExtension(url)
    when (ext) {
        "m3u8" -> return "HLS"
        "mpd" -> return "DASH"
        "mp4", "webm", "mkv", "mp3", "m4a" -> return ext.uppercase()
    }
    val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (cleanMime != null) {
        if (cleanMime in HLS_MIME_TYPES) return "HLS"
        if (cleanMime in DASH_MIME_TYPES) return "DASH"
        DIRECT_MEDIA_MIME_TYPES[cleanMime]?.let { return it }
    }
    return "MEDIA"
}

/**
 * Decodes a `data:[<mimeType>][;base64],<payload>` URI into `(mimeType, bytes)`,
 * or returns null if [dataUrl] is not a valid `data:` URI.
 */
fun decodeDataUrl(dataUrl: String): Pair<String, ByteArray>? {
    val trimmed = dataUrl.trim()
    if (!trimmed.startsWith("data:", ignoreCase = true)) return null
    val commaIndex = trimmed.indexOf(',')
    if (commaIndex < 5) return null
    val header = trimmed.substring(5, commaIndex)
    val payload = trimmed.substring(commaIndex + 1)
    val parts = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    val isBase64 = parts.any { it.equals("base64", ignoreCase = true) }
    val mime = parts.firstOrNull { !it.equals("base64", ignoreCase = true) && !it.startsWith("charset=", ignoreCase = true) }
        ?.ifBlank { null }
        ?: "application/octet-stream"
    val bytes = runCatching {
        if (isBase64) {
            Base64.getDecoder().decode(payload.replace("\\s".toRegex(), ""))
        } else {
            URLDecoder.decode(payload, StandardCharsets.UTF_8.name()).toByteArray(StandardCharsets.UTF_8)
        }
    }.getOrNull() ?: return null
    return mime to bytes
}

/**
 * Lightweight script injected in `onPageFinished` to intercept `<a download href="blob:...">`
 * clicks and `<form method="post">` download submissions via `window.PullBridge`.
 */
val BLOB_POST_CAPTURE_SCRIPT = """
    (function() {
        try {
            if (window.__pull_bridge_installed) return;
            window.__pull_bridge_installed = true;
            document.addEventListener('click', function(e) {
                var a = e.target && e.target.closest ? e.target.closest('a') : null;
                if (!a) return;
                var href = a.href || a.getAttribute('href') || '';
                if (href.indexOf('blob:') === 0) {
                    e.preventDefault();
                    var filename = a.getAttribute('download') || 'download.bin';
                    fetch(href).then(function(r) { return r.blob(); }).then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (window.PullBridge && reader.result) {
                                window.PullBridge.onBlobDownload(reader.result, filename, blob.type || '');
                            }
                        };
                        reader.readAsDataURL(blob);
                    }).catch(function() {});
                }
            }, true);
            document.addEventListener('submit', function(e) {
                var form = e.target;
                if (!form || !form.method || form.method.toLowerCase() !== 'post') return;
                var action = form.action || '';
                if (form.hasAttribute('data-download') || /\.(zip|rar|7z|tar|gz|apk|iso|pdf|mp4|mkv|webm|mp3|m4a|torrent)(\?|$)/i.test(action)) {
                    e.preventDefault();
                    var data = new URLSearchParams(new FormData(form)).toString();
                    if (window.PullBridge) {
                        window.PullBridge.onPostDownload(action, data);
                    }
                }
            }, true);
        } catch(err) {}
    })();
""".trimIndent()

/**
 * Resolves address bar input into either a navigable URL or a search query URL
 * using the selected [SearchEngine].
 */
fun resolveAddressInput(input: String, engine: SearchEngine): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    val lower = t.lowercase()
    if (SCHEME_PREFIXES.any { lower.startsWith(it) }) return t

    if (!t.contains(' ')) {
        if (LOCALHOST_REGEX.matches(t) || IPV4_HOST_REGEX.matches(t)) {
            return "http://$t"
        }
        val authority = t.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringBefore(':')
        if (host.contains('.') && !host.startsWith('.') && !host.endsWith('.') && !host.contains("..")) {
            return "https://$t"
        }
    }

    return engine.searchUrl + URLEncoder.encode(t, StandardCharsets.UTF_8.name())
}

/** Extracts the lowercase file extension from a URL's path, or null if none exists. */
fun extractFileExtension(url: String): String? {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null

    val path = runCatching { URI(trimmed).path }.getOrNull()
        ?: trimmed.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
    if (path.isBlank() || path.endsWith('/')) return null

    val fileName = path.substringAfterLast('/')
    val dotIndex = fileName.lastIndexOf('.')
    if (dotIndex <= 0 || dotIndex == fileName.lastIndex) return null
    return fileName.substring(dotIndex + 1).lowercase()
}

/** Returns true if [url] points to a file with a known downloadable extension. */
fun shouldInterceptByExtension(url: String): Boolean {
    val ext = extractFileExtension(url) ?: return false
    return ext in DOWNLOADABLE_EXTENSIONS
}
