package com.aiyu.pull.engine

import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Client for segment workers. HTTP/1.1 only: over HTTP/2 OkHttp would put every segment on one TCP connection,
 * which defeats the point of splitting. The pool keeps one idle socket per worker so a stolen range reuses it.
 */
fun segmentClient(maxIdle: Int = 64): OkHttpClient = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .connectionPool(ConnectionPool(maxIdle, 5, TimeUnit.MINUTES))
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

internal fun rangeRequest(url: String, headers: Map<String, String>, from: Long, to: Long?): Request =
    Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
        // Compressed bodies break byte offsets.
        header("Accept-Encoding", "identity")
        header("Range", if (to == null) "bytes=$from-" else "bytes=$from-$to")
    }.build()

/** `bytes 100-199/1000` -> (100, 199, 1000); total -1 for `*`. */
internal fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
    val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(value ?: return null) ?: return null
    val (s, e, t) = m.destructured
    return Triple(s.toLong(), e.toLong(), t.toLongOrNull() ?: -1)
}

internal fun Response.remoteInfo(): RemoteInfo {
    val range = parseContentRange(header("Content-Range"))
    val total = when {
        code == 206 && range != null -> range.third
        code == 200 -> body.contentLength()
        else -> -1
    }
    return RemoteInfo(
        finalUrl = request.url.toString(),
        totalBytes = total,
        acceptRanges = code == 206,
        etag = header("ETag"),
        lastModified = header("Last-Modified"),
        fileName = fileNameFrom(headers) ?: request.url.pathSegments.lastOrNull { it.isNotBlank() },
        mimeType = header("Content-Type")?.substringBefore(';')?.trim(),
    )
}

/** RFC 6266: prefers `filename*=UTF-8''...` over plain `filename=`. */
internal fun fileNameFrom(headers: Headers): String? {
    val cd = headers["Content-Disposition"] ?: return null
    Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let {
        val charset = it.groupValues[1].ifBlank { "UTF-8" }
        return runCatching { URLDecoder.decode(it.groupValues[2].trim(), charset) }.getOrNull()?.sanitizeFileName()
    }
    Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.let {
        return it.groupValues[1].trim().sanitizeFileName()
    }
    return null
}

internal fun String.sanitizeFileName(): String? =
    substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[\u0000-\u001f<>:"|?*]"""), "_").trim().ifBlank { null }

/** Weak and strong ETags of the same entity compare equal for our purpose (same bytes on the same server). */
internal fun sameEtag(a: String?, b: String?): Boolean =
    a == null || b == null || a.removePrefix("W/") == b.removePrefix("W/")

internal fun Int.isExpiredLinkCode() = this == 401 || this == 403 || this == 404 || this == 410

/** Asks for one byte to learn name, size and range support before the user commits to the download. */
suspend fun probe(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): RemoteInfo =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val call = client.newCall(rangeRequest(url, headers, 0, 0))
        call.execute().use { r ->
            when {
                r.code == 200 || r.code == 206 -> r.remoteInfo()
                r.code.isExpiredLinkCode() -> throw DownloadException(ErrorKind.LINK_EXPIRED, "Link expired (HTTP ${r.code})", r.code)
                else -> throw DownloadException(ErrorKind.HTTP, "HTTP ${r.code}", r.code)
            }
        }
    }
