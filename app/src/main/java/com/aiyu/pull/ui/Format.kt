package com.aiyu.pull.ui

import java.util.Locale
import kotlin.math.abs

/** 1024-based sizes with two decimals under 100, like desktop download managers show them. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else if (v >= 10) "%.1f %s" else "%.2f %s", v, units[i])
}

fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

/** `1h 04m`, `3m 12s`, `45s`. */
fun formatDuration(seconds: Long): String {
    val s = abs(seconds)
    return when {
        s >= 86_400 -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        s >= 3600 -> String.format(Locale.getDefault(), "%dh %02dm", s / 3600, (s % 3600) / 60)
        s >= 60 -> String.format(Locale.getDefault(), "%dm %02ds", s / 60, s % 60)
        else -> "${s}s"
    }
}

fun etaSeconds(remaining: Long, bytesPerSecond: Long): Long? =
    if (remaining > 0 && bytesPerSecond > 0) remaining / bytesPerSecond else null

/** Spoken form for TalkBack: "4.2 megabytes per second". */
fun speakSpeed(bytesPerSecond: Long): String = formatSpeed(bytesPerSecond)
    .replace("KB/s", "kilobytes per second").replace("MB/s", "megabytes per second")
    .replace("GB/s", "gigabytes per second").replace("B/s", "bytes per second")
