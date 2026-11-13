package com.aiyu.pull.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.download.FileStore
import java.io.File

/** False when no installed app can handle the file. */
fun openFile(context: Context, files: FileStore, e: DownloadEntity): Boolean {
    val uri = files.shareable(files.parse(e.fileUri ?: return false))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, FileStore.mimeFor(e.fileName, e.mimeType))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent); true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

fun shareFile(context: Context, files: FileStore, e: DownloadEntity) {
    val uri = files.shareable(files.parse(e.fileUri ?: return))
    val send = Intent(Intent.ACTION_SEND)
        .setType(FileStore.mimeFor(e.fileName, e.mimeType))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(e.fileName, uri)
    context.startActivity(Intent.createChooser(send, e.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareTorrentFile(context: Context, path: String, name: String) {
    val file = File(path).takeIf { it.exists() } ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/x-bittorrent")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("$name.torrent", uri)
    context.startActivity(Intent.createChooser(send, "$name.torrent").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
