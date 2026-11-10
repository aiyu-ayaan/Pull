package com.aiyu.pull.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DownloadTaskTest {
    private val data = Random(42).nextBytes(5 * 1024 * 1024 + 12_345)
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private val client = segmentClient()

    /** Knobs the dispatcher reads; tests flip them between runs. */
    @Volatile private var supportRanges = true
    @Volatile private var etag = "\"v1\""
    @Volatile private var signedVersion = 1
    @Volatile private var expireOldLinks = false
    @Volatile private var failNext503 = 0
    @Volatile private var throttle = false
    @Volatile private var chunked = false
    /** Like many file hosts: `bytes=0-` gets a plain 200, real offsets get 206. */
    @Volatile private var plain200ForWholeFile = false
    /** Cut this many responses off halfway, like a network switch. */
    @Volatile private var dropNext = 0

    @Before fun setUp() {
        file = File.createTempFile("pull", ".bin").apply { deleteOnExit() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/redirect") {
                    return MockResponse.Builder().code(302).addHeader("Location", "/signed?v=$signedVersion").build()
                }
                if (path == "/gone") return MockResponse.Builder().code(403).build()
                if (path == "/signed" && expireOldLinks && request.url.queryParameter("v") != "$signedVersion") {
                    return MockResponse.Builder().code(403).build()
                }
                ranges += request.headers["Range"]
                if (failNext503 > 0) { failNext503--; return MockResponse.Builder().code(503).build() }
                return serve(request.headers["Range"])
            }
        }
        server.start()
    }

    @After fun tearDown() { server.close(); file.delete() }

    private fun serve(range: String?): MockResponse {
        val b = MockResponse.Builder().addHeader("ETag", etag)
        if (throttle) b.throttleBody(16 * 1024, 20, TimeUnit.MILLISECONDS)
        val m = range?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
        if (dropNext > 0) { dropNext--; b.onResponseBody(SocketEffect.CloseSocket()) }
        if (!supportRanges || m == null || (plain200ForWholeFile && range == "bytes=0-")) {
            return if (chunked) b.code(200).chunkedBody(Buffer().write(data), 64 * 1024).build()
            else b.code(200).body(Buffer().write(data)).build()
        }
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[2].toIntOrNull()?.coerceAtMost(data.size - 1) ?: (data.size - 1)
        return b.code(206)
            .addHeader("Content-Range", "bytes $start-$end/${data.size}")
            .body(Buffer().write(data, start, end - start + 1))
            .build()
    }

    private fun opener(): suspend (RemoteInfo) -> FileChannel = {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    }

    private fun task(path: String, resume: ResumeData? = null, connections: Int = 8, minSplit: Long = 256 * 1024) = DownloadTask(
        client, DownloadRequest(server.url(path).toString(), maxConnections = connections, minSplitBytes = minSplit, resume = resume),
        opener(), retryBaseMillis = 10,
    )

    private fun assertFileMatches() {
        val got = RandomAccessFile(file, "r").use { f -> ByteArray(data.size).also { f.readFully(it) } }
        assertArrayEquals(data, got)
    }

    @Test fun segmentedDownloadWritesExactBytes() = runBlocking {
        val info = task("/f").run()
        assertEquals(data.size.toLong(), info.totalBytes)
        assertTrue(info.acceptRanges)
        assertFileMatches()
        assertTrue("expected parallel ranges, got $ranges", ranges.size >= 8)
    }

    @Test fun serverWithoutRangesFallsBackToOneConnection() = runBlocking {
        supportRanges = false
        val info = task("/f").run()
        assertTrue(!info.acceptRanges)
        assertFileMatches()
    }

    @Test fun plain200ForWholeFileStillSplits() = runBlocking {
        plain200ForWholeFile = true
        chunked = true // no Content-Length either: the size comes from the range check
        val info = task("/f").run()
        assertTrue(info.acceptRanges)
        assertEquals(data.size.toLong(), info.totalBytes)
        assertFileMatches()
        assertTrue("expected parallel ranges, got $ranges", ranges.size >= 8)
    }

    @Test fun droppedConnectionContinuesInsteadOfRestarting() = runBlocking {
        plain200ForWholeFile = true
        dropNext = 1
        // Too small to be range-checked up front, so it starts as "not resumable" on one connection.
        val info = withContext(Dispatchers.Default) { task("/f", minSplit = 64L shl 20).run() }
        assertTrue(info.acceptRanges)
        assertFileMatches()
        assertEquals("bytes=0-", ranges.first())
        assertTrue("must continue from the break, not from 0: $ranges", ranges.drop(1).none { it == "bytes=0-" })
    }

    @Test fun droppedConnectionFailsWhenServerCannotResume() = runBlocking {
        supportRanges = false
        dropNext = 1
        val t = task("/f")
        try {
            withContext(Dispatchers.Default) { t.run() }
            fail("expected a failure, not a silent restart")
        } catch (e: DownloadException) {
            assertEquals(ErrorKind.HTTP, e.kind)
        }
        assertTrue("progress must survive the failure", t.snapshot()!!.segments.single().current > 0)
    }

    @Test fun unknownLengthDownloadsToEof() = runBlocking {
        supportRanges = false
        chunked = true
        val t = task("/f")
        val info = t.run()
        assertEquals(data.size.toLong(), info.totalBytes)
        assertFileMatches()
    }

    @Test fun pauseAndResumeContinuesWithoutRestarting() = runBlocking {
        throttle = true
        val t = task("/f")
        val job = async(Dispatchers.Default) { t.run() }
        withTimeout(10_000) { while (t.progress.value.downloadedBytes < data.size / 4) delay(20) }
        job.cancelAndJoin()
        val saved = t.snapshot()!!
        assertTrue(saved.segments.isNotEmpty())
        val before = data.size - saved.segments.sumOf { it.end - it.current + 1 }
        assertTrue(before > 0)

        throttle = false
        ranges.clear()
        val resumed = task("/f", saved)
        assertEquals(before, resumed.progress.value.downloadedBytes)
        resumed.run()
        assertFileMatches()
        assertTrue("resume must not refetch from 0: $ranges", ranges.none { it == "bytes=0-" })
    }

    @Test fun resumeRejectsChangedFile() = runBlocking {
        throttle = true
        val t = task("/f")
        val job = async(Dispatchers.Default) { t.run() }
        withTimeout(10_000) { while (t.progress.value.downloadedBytes < 512 * 1024) delay(20) }
        job.cancelAndJoin()
        etag = "\"v2\""
        try {
            task("/f", t.snapshot()).run()
            fail("expected FILE_CHANGED")
        } catch (e: DownloadException) {
            assertEquals(ErrorKind.FILE_CHANGED, e.kind)
        }
    }

    @Test fun expiredSignedLinkIsRefreshedFromOriginalUrl() = runBlocking {
        throttle = true
        val t = task("/redirect")
        val job = async(Dispatchers.Default) { t.run() }
        withTimeout(10_000) { while (t.progress.value.downloadedBytes < 512 * 1024) delay(20) }
        job.cancelAndJoin()
        val saved = t.snapshot()!!
        assertTrue(saved.info.finalUrl.contains("v=1"))
        // The signed link dies; the page URL now redirects to a new one.
        signedVersion = 2
        expireOldLinks = true
        throttle = false
        val resumed = task("/redirect", saved)
        val info = resumed.run()
        assertTrue(info.finalUrl.contains("v=2"))
        assertFileMatches()
    }

    @Test fun deadLinkReportsLinkExpired() = runBlocking {
        try {
            task("/gone").run()
            fail("expected LINK_EXPIRED")
        } catch (e: DownloadException) {
            assertEquals(ErrorKind.LINK_EXPIRED, e.kind)
        }
    }

    @Test fun transientServerErrorsAreRetried() = runBlocking {
        failNext503 = 3
        withContext(Dispatchers.Default) { task("/f").run() }
        assertFileMatches()
    }

    @Test fun manyWorkersStealWithoutCorruption() = runBlocking {
        throttle = true
        task("/f", connections = 32).run()
        assertFileMatches()
    }
}
