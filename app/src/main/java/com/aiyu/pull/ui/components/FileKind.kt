package com.aiyu.pull.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.graphics.shapes.RoundedPolygon

/** File type of a download: drives its icon, its Expressive container shape and its tone. */
enum class FileKind { VIDEO, AUDIO, IMAGE, DOCUMENT, ARCHIVE, APK, OTHER;

    val icon: ImageVector
        get() = when (this) {
            VIDEO -> Icons.Rounded.Movie
            AUDIO -> Icons.Rounded.Audiotrack
            IMAGE -> Icons.Rounded.Image
            DOCUMENT -> Icons.Rounded.Description
            ARCHIVE -> Icons.Rounded.Archive
            APK -> Icons.Rounded.Android
            OTHER -> Icons.Rounded.InsertDriveFile
        }

    val shape: RoundedPolygon
        get() = when (this) {
            VIDEO -> MaterialShapes.Cookie9Sided
            AUDIO -> MaterialShapes.Sunny
            IMAGE -> MaterialShapes.Clover4Leaf
            DOCUMENT -> MaterialShapes.Square
            ARCHIVE -> MaterialShapes.Cookie6Sided
            APK -> MaterialShapes.Pill
            OTHER -> MaterialShapes.Circle
        }

    companion object {
        fun of(fileName: String, mime: String?): FileKind {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val m = mime.orEmpty()
            return when {
                m.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "3gp") -> VIDEO
                m.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav") -> AUDIO
                m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg") -> IMAGE
                ext == "apk" || ext == "apks" || ext == "xapk" || m == "application/vnd.android.package-archive" -> APK
                ext in setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst", "iso") -> ARCHIVE
                m.startsWith("text/") || ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv") -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

/** Container/on-container pair. Tones 90/30 (light) and 30/90 (dark) of a fixed hue, as in Findex. */
data class KindTone(val container: Color, val onContainer: Color)

private val light = mapOf(
    FileKind.IMAGE to KindTone(Color(0xFF91F4E6), Color(0xFF005049)),
    FileKind.VIDEO to KindTone(Color(0xFFFFDBCE), Color(0xFF753317)),
    FileKind.AUDIO to KindTone(Color(0xFFFDD6FF), Color(0xFF62356D)),
    FileKind.DOCUMENT to KindTone(Color(0xFFD3E4FF), Color(0xFF0F487C)),
    FileKind.ARCHIVE to KindTone(Color(0xFFFFDDB8), Color(0xFF653E00)),
    FileKind.APK to KindTone(Color(0xFFCAEE9B), Color(0xFF334E0D)),
)

private val dark = mapOf(
    FileKind.IMAGE to KindTone(Color(0xFF005049), Color(0xFF91F4E6)),
    FileKind.VIDEO to KindTone(Color(0xFF753317), Color(0xFFFFDBCE)),
    FileKind.AUDIO to KindTone(Color(0xFF62356D), Color(0xFFFDD6FF)),
    FileKind.DOCUMENT to KindTone(Color(0xFF0F487C), Color(0xFFD3E4FF)),
    FileKind.ARCHIVE to KindTone(Color(0xFF653E00), Color(0xFFFFDDB8)),
    FileKind.APK to KindTone(Color(0xFF334E0D), Color(0xFFCAEE9B)),
)

/** Follows the app theme (not the system), so it stays right when the user forces light or dark. */
val FileKind.tone: KindTone
    @Composable @ReadOnlyComposable
    get() {
        val c = MaterialTheme.colorScheme
        val table = if (c.surface.luminance() < 0.5f) dark else light
        return table[this] ?: KindTone(c.secondaryContainer, c.onSecondaryContainer)
    }
