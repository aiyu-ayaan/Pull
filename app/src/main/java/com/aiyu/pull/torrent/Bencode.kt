package com.aiyu.pull.torrent

import java.io.ByteArrayOutputStream

/**
 * Minimal bencode reader and writer for `.torrent` files. libtorrent4j's add path drops BEP 19 web seeds
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

    /** Decodes a bencoded top-level dictionary, or null if malformed. */
    @Suppress("UNCHECKED_CAST")
    fun decode(bytes: ByteArray): Map<String, Any>? = runCatching {
        Reader(bytes).read() as? Map<String, Any>
    }.getOrNull()

    /** Encodes a Bencode value (`Long`, `Int`, `String`, `ByteArray`, `List`, or `Map` with string keys). */
    fun encode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        writeTo(out, value)
        return out.toByteArray()
    }

    private fun writeTo(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Int -> writeTo(out, value.toLong())
            is Long -> {
                out.write('i'.code)
                out.write(value.toString().encodeToByteArray())
                out.write('e'.code)
            }
            is String -> writeTo(out, value.encodeToByteArray())
            is ByteArray -> {
                out.write(value.size.toString().encodeToByteArray())
                out.write(':'.code)
                out.write(value)
            }
            is List<*> -> {
                out.write('l'.code)
                value.forEach { item -> if (item != null) writeTo(out, item) }
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                val sorted = value.entries
                    .mapNotNull { (k, v) -> if (k != null && v != null) (k.toString() to v) else null }
                    .sortedWith { a, b -> compareUtf8(a.first, b.first) }
                for ((k, v) in sorted) {
                    writeTo(out, k.encodeToByteArray())
                    writeTo(out, v)
                }
                out.write('e'.code)
            }
            else -> error("Unsupported bencode type: ${value::class.java}")
        }
    }

    private fun compareUtf8(a: String, b: String): Int {
        val ab = a.encodeToByteArray()
        val bb = b.encodeToByteArray()
        val min = minOf(ab.size, bb.size)
        for (i in 0 until min) {
            val cmp = (ab[i].toInt() and 0xff).compareTo(bb[i].toInt() and 0xff)
            if (cmp != 0) return cmp
        }
        return ab.size.compareTo(bb.size)
    }

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
