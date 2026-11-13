package com.aiyu.pull.ui.add

import com.aiyu.pull.data.isWithinSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchUrlUtilsTest {
    @Test
    fun expandsZeroPaddedRange() {
        val urls = expandUrlPattern("https://example.com/part[01-05].zip")
        assertEquals(
            listOf(
                "https://example.com/part01.zip",
                "https://example.com/part02.zip",
                "https://example.com/part03.zip",
                "https://example.com/part04.zip",
                "https://example.com/part05.zip",
            ),
            urls,
        )
    }

    @Test
    fun expandsUnpaddedRange() {
        val urls = expandUrlPattern("https://cdn.example.org/img_[8-11].png")
        assertEquals(
            listOf(
                "https://cdn.example.org/img_8.png",
                "https://cdn.example.org/img_9.png",
                "https://cdn.example.org/img_10.png",
                "https://cdn.example.org/img_11.png",
            ),
            urls,
        )
    }

    @Test
    fun capsExpandedRangeAt500() {
        val urls = expandUrlPattern("https://example.com/chunk[0001-2000].bin")
        assertEquals(MAX_BATCH_URLS, urls.size)
        assertEquals("https://example.com/chunk0001.bin", urls.first())
        assertEquals("https://example.com/chunk0500.bin", urls.last())
    }

    @Test
    fun splitsMultiLineHttpListAndIgnoresComments() {
        val text = """
            # Mirrors list
            https://example.com/a.zip
            
            http://example.com/b[1-2].zip
            magnet:?xt=urn:btih:12345
        """.trimIndent()
        val urls = parseUrlListText(text)
        assertEquals(
            listOf(
                "https://example.com/a.zip",
                "http://example.com/b1.zip",
                "http://example.com/b2.zip",
            ),
            urls,
        )
    }

    @Test
    fun scheduleWindowChecksDaytimeAndOvernight() {
        // 00:00 -> 06:00
        assertTrue(isWithinSchedule(0, 0, 6))
        assertTrue(isWithinSchedule(5, 0, 6))
        assertFalse(isWithinSchedule(6, 0, 6))
        assertFalse(isWithinSchedule(14, 0, 6))

        // Overnight 22:00 -> 06:00
        assertTrue(isWithinSchedule(22, 22, 6))
        assertTrue(isWithinSchedule(23, 22, 6))
        assertTrue(isWithinSchedule(2, 22, 6))
        assertFalse(isWithinSchedule(6, 22, 6))
        assertFalse(isWithinSchedule(15, 22, 6))

        // Full day 00:00 -> 00:00
        assertTrue(isWithinSchedule(12, 0, 0))
    }
}
