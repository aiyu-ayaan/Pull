package com.aiyu.pull.torrent

/**
 * Minimal bencode reader for the top level of a .torrent. libtorrent4j's add path drops BEP 19 web seeds
 * (`url-list`), so Pull reads them here and adds them to the handle itself.
 */
object Bencode {
    fun webSeeds(bytes: ByteArray): List<String> = runCatching {
        val top = Reader(bytes).read() as? Map<*, *> ?: return emptyList()
        when (val v = top["url-list"]) {
            is ByteArray -> listOf(String(v))
            is List<*> -> v.filterIsInstance<ByteArray>().map { String(it) }
            else -> emptyList()
        }.filter { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrDefault(emptyList())

    /** Top-level `comment`, `created by` and `creation date` (seconds since epoch, 0 when absent). */
    fun about(bytes: ByteArray): Triple<String, String, Long> = runCatching {
        val top = Reader(bytes).read() as? Map<*, *> ?: return Triple("", "", 0)
        Triple(
            (top["comment"] as? ByteArray)?.let(::String).orEmpty(),
            (top["created by"] as? ByteArray)?.let(::String).orEmpty(),
            top["creation date"] as? Long ?: 0,
        )
    }.getOrDefault(Triple("", "", 0))

    private class Reader(val b: ByteArray) {
        var i = 0
        fun read(): Any = when (val c = b[i].toInt().toChar()) {
            'i' -> { i++; val end = indexOf('e'); String(b, i, end - i).toLong().also { i = end + 1 } }
            'l' -> { i++; buildList { while (b[i] != 'e'.code.toByte()) add(read()) }.also { i++ } }
            'd' -> {
                i++
                val m = LinkedHashMap<String, Any>()
                while (b[i] != 'e'.code.toByte()) m[String(read() as ByteArray)] = read()
                i++
                m
            }
            in '0'..'9' -> {
                val colon = indexOf(':')
                val len = String(b, i, colon - i).toInt()
                b.copyOfRange(colon + 1, colon + 1 + len).also { i = colon + 1 + len }
            }
            else -> error("bad bencode at $i: $c")
        }
        private fun indexOf(ch: Char): Int { var j = i; while (b[j] != ch.code.toByte()) j++; return j }
    }
}
