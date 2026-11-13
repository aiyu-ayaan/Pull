package com.aiyu.pull.torrent

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Generates standard BEP 3 / BEP 12 Bencoded `.torrent` files from any [File] or [InputStream].
 */
object TorrentCreator {
    const val DEFAULT_CREATOR = "Pull"
    private const val MIN_PIECE_LENGTH = 16 * 1024

    /** Picks a power-of-two piece length keeping piece count around 500–2000 for typical files. */
    fun choosePieceLength(totalBytes: Long): Int = when {
        totalBytes < 16L shl 20 -> 16 * 1024
        totalBytes < 64L shl 20 -> 64 * 1024
        totalBytes < 256L shl 20 -> 256 * 1024
        totalBytes < 1024L shl 20 -> 512 * 1024
        else -> 1024 * 1024
    }

    /** Generates a single-file `.torrent` byte array from [file]. */
    fun create(
        file: File,
        trackers: List<String> = emptyList(),
        comment: String = "",
        pieceLength: Int = choosePieceLength(file.length()),
        createdBy: String = DEFAULT_CREATOR,
        creationDate: Long = System.currentTimeMillis() / 1000,
    ): ByteArray = file.inputStream().buffered().use { input ->
        create(
            input = input,
            name = file.name,
            size = file.length(),
            trackers = trackers,
            comment = comment,
            pieceLength = pieceLength,
            createdBy = createdBy,
            creationDate = creationDate,
        )
    }

    /** Generates a single-file `.torrent` byte array from [input]. */
    fun create(
        input: InputStream,
        name: String,
        size: Long = -1L,
        trackers: List<String> = emptyList(),
        comment: String = "",
        pieceLength: Int = choosePieceLength(size.coerceAtLeast(0L)),
        createdBy: String = DEFAULT_CREATOR,
        creationDate: Long = System.currentTimeMillis() / 1000,
    ): ByteArray {
        val safePieceLen = pieceLength.coerceAtLeast(MIN_PIECE_LENGTH)
        val piecesOut = ByteArrayOutputStream()
        val buf = ByteArray(safePieceLen)
        val sha1 = MessageDigest.getInstance("SHA-1")
        var totalRead = 0L

        while (true) {
            var offset = 0
            while (offset < safePieceLen) {
                val n = input.read(buf, offset, safePieceLen - offset)
                if (n <= 0) break
                offset += n
            }
            if (offset == 0) break
            totalRead += offset
            sha1.reset()
            sha1.update(buf, 0, offset)
            piecesOut.write(sha1.digest())
            if (offset < safePieceLen) break
        }

        val finalLength = if (totalRead > 0L || size < 0L) totalRead else size
        val cleanName = name.trim().substringAfterLast('/').ifBlank { "file" }
        val info = linkedMapOf<String, Any>(
            "length" to finalLength,
            "name" to cleanName,
            "piece length" to safePieceLen.toLong(),
            "pieces" to piecesOut.toByteArray(),
        )

        val cleanTrackers = trackers.map(String::trim).filter(String::isNotEmpty).distinct()
        val top = linkedMapOf<String, Any>()
        if (cleanTrackers.isNotEmpty()) {
            top["announce"] = cleanTrackers.first()
            top["announce-list"] = cleanTrackers.map { listOf(it) }
        }
        if (comment.isNotBlank()) {
            top["comment"] = comment.trim()
        }
        top["created by"] = createdBy
        top["creation date"] = creationDate
        top["info"] = info

        return Bencode.encode(top)
    }

    /** Computes the 40-char hex SHA-1 info-hash from generated `.torrent` bytes. */
    fun infoHash(torrentBytes: ByteArray): String {
        val top = Bencode.decode(torrentBytes) ?: return ""
        val info = top["info"] as? Map<*, *> ?: return ""
        val encodedInfo = Bencode.encode(info)
        val digest = MessageDigest.getInstance("SHA-1").digest(encodedInfo)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Writes [torrentBytes] into [torrentsDir] under `<fileName>.torrent` and returns the written [File]. */
    fun saveToFile(torrentsDir: File, fileName: String, torrentBytes: ByteArray): File {
        torrentsDir.mkdirs()
        val base = fileName.trim().substringAfterLast('/')
            .replace(Regex("""[\u0000-\u001f<>:"/\\|?*]"""), "_")
            .removeSuffix(".torrent")
            .ifBlank { "created" }
        val target = File(torrentsDir, "$base.torrent")
        target.writeBytes(torrentBytes)
        return target
    }
}
