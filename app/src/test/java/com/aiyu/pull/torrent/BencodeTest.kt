package com.aiyu.pull.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class BencodeTest {
    @Test
    fun `webSeeds reads single string url-list`() {
        val raw = "d8:url-list20:https://a.example/f/e".encodeToByteArray()
        assertEquals(listOf("https://a.example/f/"), Bencode.webSeeds(raw))
    }

    @Test
    fun `webSeeds reads list url-list and filters non-http`() {
        val raw = "d8:url-listl19:http://a.example/1/19:ftp://b.example/bad20:https://c.example/2/ee".encodeToByteArray()
        assertEquals(listOf("http://a.example/1/", "https://c.example/2/"), Bencode.webSeeds(raw))
    }

    @Test
    fun `about extracts comment creator and creation date`() {
        val raw = "d7:comment5:hello10:created by4:Pull13:creation datei1700000000ee".encodeToByteArray()
        assertEquals(Triple("hello", "Pull", 1700000000L), Bencode.about(raw))
    }

    @Test
    fun `malformed bencode returns safe defaults`() {
        val bad = "not-bencode".encodeToByteArray()
        assertEquals(emptyList<String>(), Bencode.webSeeds(bad))
        assertEquals(Triple("", "", 0L), Bencode.about(bad))
    }
}
