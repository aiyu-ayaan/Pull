package com.aiyu.pull.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aiyu.pull.R
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
@Composable
fun speakSpeed(bytesPerSecond: Long): String = formatSpeed(bytesPerSecond)
    .replace("KB/s", stringResource(R.string.speak_kb_s))
    .replace("MB/s", stringResource(R.string.speak_mb_s))
    .replace("GB/s", stringResource(R.string.speak_gb_s))
    .replace("B/s", stringResource(R.string.speak_b_s))
