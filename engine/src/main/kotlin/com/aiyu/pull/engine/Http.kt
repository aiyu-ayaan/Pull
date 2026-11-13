package com.aiyu.pull.engine

import kotlinx.coroutines.delay
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves all IPv4/IPv6 addresses for a hostname and rotates the returned order on each lookup so concurrent
 * HTTP/1.1 segment connections spread across multiple server IPs when present.
 */
class RoundRobinDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.size <= 1) return addresses
        val offset = cursors.computeIfAbsent(hostname) { AtomicInteger(0) }.getAndIncrement().mod(addresses.size)
        if (offset == 0) return addresses
        return addresses.subList(offset, addresses.size) + addresses.subList(0, offset)
    }
}

/**
 * Client for segment workers. HTTP/1.1 only: over HTTP/2 OkHttp would put every segment on one TCP connection,
 * which defeats the point of splitting. The pool keeps one idle socket per worker so a stolen range reuses it.
 */
fun segmentClient(maxIdle: Int = 64, dns: Dns = RoundRobinDns()): OkHttpClient = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .dns(dns)
    .dispatcher(
        Dispatcher().apply {
            maxRequests = maxOf(128, maxIdle * 2)
            maxRequestsPerHost = maxIdle
        },
    )
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
    val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).find(value ?: return null) ?: return null
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

/**
 * A 200 does not prove the server ignores ranges: one single-byte request at a real offset does. On a valid 206 the
 * result accepts ranges and has the size, even when the 200 came without a length. Retries transient 429/5xx/network
 * blips so a brief throttle does not force a single-connection download.
 */
internal suspend fun RemoteInfo.verifyRanges(
    headers: Map<String, String>,
    execute: (Request) -> Response,
): RemoteInfo {
    val at = if (totalBytes > 1) totalBytes / 2 else 1L
    var range: Triple<Long, Long, Long>? = null
    for (attempt in 0..2) {
        if (attempt > 0) delay(150L * attempt)
        val retryable = try {
            execute(rangeRequest(finalUrl, headers, at, at)).use { r ->
                when {
                    r.code == 206 -> { range = parseContentRange(r.header("Content-Range")); false }
                    r.code == 429 || r.code >= 500 -> true
                    else -> false
                }
            }
        } catch (_: IOException) {
            true
        }
        if (range != null || !retryable) break
    }
    val parsed = range ?: return this
    val total = parsed.third
    val valid = parsed.first == at && total > at && (totalBytes < 0 || total == totalBytes)
    return if (valid) copy(acceptRanges = true, totalBytes = total) else this
}

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
                r.code == 206 -> r.remoteInfo()
                r.code == 200 -> r.remoteInfo().verifyRanges(headers) { client.newCall(it).execute() }
                r.code.isExpiredLinkCode() -> throw DownloadException(ErrorKind.LINK_EXPIRED, "Link expired (HTTP ${r.code})", r.code)
                else -> throw DownloadException(ErrorKind.HTTP, "HTTP ${r.code}", r.code)
            }
        }
    }
