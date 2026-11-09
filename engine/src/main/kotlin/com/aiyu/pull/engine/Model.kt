package com.aiyu.pull.engine

/** Byte range `[start, end]` (end inclusive) owned by one connection; [current] is the next byte to write. */
data class SegmentState(val start: Long, val end: Long, val current: Long) {
    val done: Boolean get() = current > end
    val downloaded: Long get() = (minOf(current, end + 1) - start).coerceAtLeast(0)
}

/** What the server told us about the file. Compared on resume so bytes from two different files never mix. */
data class RemoteInfo(
    val finalUrl: String,
    /** -1 when the server sent no length (chunked). */
    val totalBytes: Long,
    val acceptRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val fileName: String?,
    val mimeType: String?,
)

/** Saved state of a paused or interrupted download. */
data class ResumeData(val info: RemoteInfo, val segments: List<SegmentState>)

data class DownloadRequest(
    /** The URL the user added. Kept stable: signed CDN links behind it are re-resolved from here when they expire. */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val maxConnections: Int = 8,
    val minSplitBytes: Long = 1L shl 20,
    val resume: ResumeData? = null,
)

enum class ErrorKind {
    /** 401/403/404/410 on the original URL too: the link needs refreshing from its web page. */
    LINK_EXPIRED,
    /** Size or ETag differs from the bytes already on disk. */
    FILE_CHANGED,
    NETWORK,
    HTTP,
    DISK,
}

class DownloadException(val kind: ErrorKind, message: String, val httpCode: Int = 0, cause: Throwable? = null) :
    Exception(message, cause)

data class SegmentProgress(
    val start: Long,
    val end: Long,
    val current: Long,
    val bytesPerSecond: Long,
    val active: Boolean,
    val retries: Int,
)

data class Progress(
    val info: RemoteInfo?,
    val downloadedBytes: Long,
    val bytesPerSecond: Long,
    val segments: List<SegmentProgress>,
)
