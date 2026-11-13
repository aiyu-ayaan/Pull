package com.aiyu.pull.torrent

import com.aiyu.pull.data.decodeFilePriorities
import com.aiyu.pull.data.effectiveFilePriority
import com.aiyu.pull.data.encodeFilePriorities
import com.aiyu.pull.download.FilePriority
import org.junit.Assert.assertEquals
import org.junit.Test

class FilePriorityTest {
    @Test
    fun `encode and decode round-trips sorted map`() {
        val map = mapOf(3 to FilePriority.SKIP, 0 to FilePriority.HIGH, 1 to FilePriority.LOW)
        val encoded = encodeFilePriorities(map)
        assertEquals("0:HIGH,1:LOW,3:SKIP", encoded)
        assertEquals(map, decodeFilePriorities(encoded))
    }

    @Test
    fun `decode ignores blank or invalid entries`() {
        assertEquals(emptyMap<Int, FilePriority>(), decodeFilePriorities(""))
        assertEquals(mapOf(2 to FilePriority.HIGH), decodeFilePriorities("bad,2:HIGH,3:NOPE"))
    }

    @Test
    fun `effectiveFilePriority combines selectedFiles and overrides`() {
        val overrides = mapOf(0 to FilePriority.HIGH, 2 to FilePriority.SKIP)
        assertEquals(FilePriority.HIGH, effectiveFilePriority(0, emptySet(), overrides))
        assertEquals(FilePriority.NORMAL, effectiveFilePriority(1, emptySet(), overrides))
        assertEquals(FilePriority.SKIP, effectiveFilePriority(2, emptySet(), overrides))

        val selected = setOf(0, 1)
        assertEquals(FilePriority.HIGH, effectiveFilePriority(0, selected, overrides))
        assertEquals(FilePriority.NORMAL, effectiveFilePriority(1, selected, overrides))
        assertEquals(FilePriority.SKIP, effectiveFilePriority(3, selected, overrides))
    }
}
