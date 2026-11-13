package com.aiyu.pull.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class TorrentCreatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `create from File generates valid bencoded torrent parsed by Bencode about`() {
        val sampleFile = tempFolder.newFile("sample.bin")
        val content = ByteArray(40_000) { (it * 31).toByte() }
        sampleFile.writeBytes(content)

        val torrentBytes = TorrentCreator.create(
            file = sampleFile,
            trackers = listOf("udp://tracker.opentrackr.org:1337/announce", "https://tracker.example.com/announce"),
            comment = "Created by unit test",
            pieceLength = 16 * 1024,
            createdBy = "Pull",
            creationDate = 1_730_000_000L,
        )

        val (comment, creator, createdAt) = Bencode.about(torrentBytes)
        assertEquals("Created by unit test", comment)
        assertEquals("Pull", creator)
        assertEquals(1_730_000_000L, createdAt)

        val decoded = Bencode.decode(torrentBytes)
        assertNotNull(decoded)
        assertEquals("udp://tracker.opentrackr.org:1337/announce", String(decoded!!["announce"] as ByteArray))

        val info = decoded["info"] as Map<*, *>
        assertEquals("sample.bin", String(info["name"] as ByteArray))
        assertEquals(40_000L, info["length"])
        assertEquals(16_384L, info["piece length"])

        val pieces = info["pieces"] as ByteArray
        // 40,000 bytes with 16,384 piece length = 3 pieces -> 60 bytes of SHA-1 hashes
        assertEquals(60, pieces.size)

        val sha1 = MessageDigest.getInstance("SHA-1")
        val p0 = sha1.digest(content.copyOfRange(0, 16_384))
        val p1 = sha1.digest(content.copyOfRange(16_384, 32_768))
        val p2 = sha1.digest(content.copyOfRange(32_768, 40_000))
        assertArrayEquals(p0 + p1 + p2, pieces)
    }

    @Test
    fun `create from InputStream without optional fields parses cleanly via Bencode about`() {
        val data = "Hello Pull Torrent Creator!".encodeToByteArray()
        val bytes = TorrentCreator.create(
            input = ByteArrayInputStream(data),
            name = "hello.txt",
            size = data.size.toLong(),
            creationDate = 1_700_000_123L,
        )

        val about = Bencode.about(bytes)
        assertEquals(Triple("", "Pull", 1_700_000_123L), about)

        val hash = TorrentCreator.infoHash(bytes)
        assertEquals(40, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })

        val outDir = tempFolder.newFolder("torrents")
        val saved = TorrentCreator.saveToFile(outDir, "hello.txt", bytes)
        assertTrue(saved.exists())
        assertEquals("hello.txt.torrent", saved.name)
        assertEquals(about, Bencode.about(saved.readBytes()))
    }

    @Test
    fun `firstAndLastPieces selects first and last 1 percent of wanted files`() {
        // 200 pieces of 1000 bytes each; single wanted file spanning all 200 pieces -> 1% = 2 pieces at each end
        val pieces = firstAndLastPieces(
            numPieces = 200,
            pieceLength = 1000,
            files = listOf(Triple(0L, 200_000L, true)),
        )
        assertEquals(setOf(0, 1, 198, 199), pieces)

        // Small file spanning 10 pieces -> at least 1 piece at each end, plus skipped file ignored
        val multi = firstAndLastPieces(
            numPieces = 20,
            pieceLength = 1000,
            files = listOf(
                Triple(0L, 10_000L, true),
                Triple(10_000L, 10_000L, false),
            ),
        )
        assertEquals(setOf(0, 9), multi)
    }

    @Test
    fun `extractPeerIp and peer flag source formatters handle IPv4 IPv6 and bitmasks`() {
        assertEquals("192.168.1.42", extractPeerIp("192.168.1.42:51413"))
        assertEquals("192.168.1.42", extractPeerIp("192.168.1.42"))
        assertEquals("2001:db8::1", extractPeerIp("[2001:db8::1]:6881"))

        assertEquals("Tracker/DHT/PEX", formatPeerSource(0x01 or 0x02 or 0x04))
        assertEquals("DUSEO", formatPeerFlags(0x01 or 0x04 or 0x400 or 0x100000 or 0x20))
    }
}
