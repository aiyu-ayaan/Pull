package com.aiyu.pull.ui.add

const val MAX_BATCH_URLS = 500

private val RANGE_PATTERN = Regex("""\[(\d+)-(\d+)]""")

/**
 * Expands numeric `[start-end]` ranges in HTTP/HTTPS URLs (preserving leading zero padding,
 * e.g. `https://example.com/part[01-05].zip` -> 5 URLs) and splits multi-line URL lists.
 * Capped at [MAX_BATCH_URLS] URLs.
 */
fun expandUrlPattern(input: String): List<String> {
    if (input.isBlank()) return emptyList()
    val out = LinkedHashSet<String>()
    for (rawLine in input.lineSequence()) {
        if (out.size >= MAX_BATCH_URLS) break
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (!line.startsWith("http://", ignoreCase = true) && !line.startsWith("https://", ignoreCase = true)) continue
        expandSingleLine(line, out)
    }
    return out.toList()
}

private fun expandSingleLine(url: String, out: MutableSet<String>) {
    if (out.size >= MAX_BATCH_URLS) return
    val match = RANGE_PATTERN.find(url)
    if (match == null) {
        out.add(url)
        return
    }
    val startStr = match.groupValues[1]
    val endStr = match.groupValues[2]
    val start = startStr.toLongOrNull()
    val end = endStr.toLongOrNull()
    if (start == null || end == null) {
        out.add(url)
        return
    }
    val padWidth = when {
        startStr.length > 1 && startStr.startsWith('0') -> startStr.length
        endStr.length > 1 && endStr.startsWith('0') -> endStr.length
        else -> 0
    }
    val prefix = url.substring(0, match.range.first)
    val suffix = url.substring(match.range.last + 1)
    if (start <= end) {
        var current = start
        while (current <= end && out.size < MAX_BATCH_URLS) {
            val formatted = current.toString().padStart(padWidth, '0')
            expandSingleLine(prefix + formatted + suffix, out)
            if (current == Long.MAX_VALUE) break
            current++
        }
    } else {
        var current = start
        while (current >= end && out.size < MAX_BATCH_URLS) {
            val formatted = current.toString().padStart(padWidth, '0')
            expandSingleLine(prefix + formatted + suffix, out)
            if (current == Long.MIN_VALUE) break
            current--
        }
    }
}

/**
 * Parses an imported `.txt` URL list (one URL or `[start-end]` pattern per line, ignoring blank
 * lines and `#` comments), capped at [MAX_BATCH_URLS].
 */
fun parseUrlListText(text: String): List<String> = expandUrlPattern(text)

object BatchUrlUtils {
    fun expandUrlPattern(input: String): List<String> = com.aiyu.pull.ui.add.expandUrlPattern(input)
    fun parseUrlListText(text: String): List<String> = com.aiyu.pull.ui.add.parseUrlListText(text)
}
