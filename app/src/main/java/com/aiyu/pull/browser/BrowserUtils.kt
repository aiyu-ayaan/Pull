package com.aiyu.pull.browser

import com.aiyu.pull.data.SearchEngine
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
