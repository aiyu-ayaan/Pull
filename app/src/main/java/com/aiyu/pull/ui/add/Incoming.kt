package com.aiyu.pull.ui.add

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Copies a .torrent the user opened (content:// from a file manager, a browser download, the picker) into the
 * cache, so the add sheet can read it again after rotation or process death. Null when unreadable or too big.
 */
fun copyTorrent(context: Context, uri: Uri): String? = runCatching {
    val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
    val out = File(dir, "${System.currentTimeMillis()}.torrent")
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(20 shl 20)
        out.writeBytes(bytes)
    } ?: return null
    out.path
}.getOrNull()

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        buf.write(chunk, 0, n)
        if (buf.size() > max) throw java.io.IOException("Torrent file too large")
    }
    return buf.toByteArray()
}
