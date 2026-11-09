package com.aiyu.pull.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where bytes land. API 29+: the public Downloads/Pull folder through MediaStore, hidden (IS_PENDING) until done.
 * API 26-28: the app's own Downloads folder, which needs no storage permission.
 * shortcut: no SAF folder picker yet; add it when users ask for custom folders.
 */
@Singleton
class FileStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Creates the file for a new download. Returns its uri and the name it really got (duplicates get renamed). */
    fun create(fileName: String, mimeType: String?): Pair<Uri, String> {
        val mime = mimeFor(fileName, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Pull")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create $fileName in Downloads")
            val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: fileName
            return uri to name
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("Storage unavailable")
        dir.mkdirs()
        val file = uniqueFile(dir, fileName)
        file.createNewFile()
        return Uri.fromFile(file) to file.name
    }

    /** Opens for random-access writes without truncating, so a resume keeps the bytes already there. */
    fun open(uri: Uri, totalBytes: Long): FileChannel {
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw") ?: throw FileNotFoundException(uri.toString())
        } catch (e: FileNotFoundException) {
            throw IOException("The file was moved or deleted", e)
        } catch (e: SecurityException) {
            throw IOException("No access to the file", e)
        }
        if (totalBytes > 0) reserve(pfd, totalBytes)
        return ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
    }

    /**
     * Reserves the whole size up front so "disk full" shows before any byte is fetched, not at 97%.
     * shortcut: runs inline; fallocate is a metadata update on ext4/f2fs. Move it off the start path if it shows in traces.
     */
    private fun reserve(pfd: ParcelFileDescriptor, size: Long) {
        try {
            if (Os.fstat(pfd.fileDescriptor).st_size >= size) return
            Os.posix_fallocate(pfd.fileDescriptor, 0, size)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOSPC) {
                pfd.close()
                throw IOException("Not enough storage space", e)
            }
            // FUSE and some filesystems do not support fallocate: the file grows as bytes arrive instead.
        }
    }

    /** Makes the file visible to other apps. */
    fun publish(uri: Uri) {
        if (uri.scheme == "content") {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    fun delete(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).deleteRecursively() else resolver.delete(uri, null, null)
        }
    }

    fun exists(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") File(uri.path!!).exists()
        else resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** A uri other apps can open (file:// is not allowed across apps). */
    fun shareable(uri: Uri): Uri =
        if (uri.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.files", File(uri.path!!)) else uri

    fun parse(uri: String): Uri = uri.toUri()

    companion object {
        fun mimeFor(fileName: String, serverMime: String?): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: serverMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: "application/octet-stream"
        }
    }
}

/** [name] in [dir], or `name (1).ext`, `name (2).ext`… when taken, like Android and Windows do. */
fun uniqueFile(dir: File, name: String): File {
    var file = File(dir, name)
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var n = 1
    while (file.exists()) file = File(dir, "$base (${n++})$ext")
    return file
}
