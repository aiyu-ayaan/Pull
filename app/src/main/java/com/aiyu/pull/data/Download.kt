package com.aiyu.pull.data

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.aiyu.pull.download.FilePriority
import com.aiyu.pull.engine.RemoteInfo
import com.aiyu.pull.engine.ResumeData
import com.aiyu.pull.engine.SegmentState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

enum class Status { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

/** What fetches the bytes: the segmented HTTP engine, libtorrent, or yt-dlp. */
enum class Kind { HTTP, TORRENT, VIDEO }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Link the user added. Expired signed links are re-resolved from it. */
    val url: String,
    /** Web page the link came from: where Pull sends the user to fetch a fresh link. */
    val pageUrl: String? = null,
    val headersJson: String = "{}",
    val fileName: String,
    val mimeType: String? = null,
    /** content:// (MediaStore) or file:// once the server answered; null before. */
    val fileUri: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Status = Status.QUEUED,
    val finalUrl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptRanges: Boolean = false,
    /** Unfinished ranges as `start-end-current;...`. Rewritten about once a second while running. */
    val segments: String = "",
    val maxConnections: Int = 8,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** Time spent actually downloading, for average speed. */
    val activeMillis: Long = 0,
    val errorKind: String? = null,
    val error: String? = null,
    @ColumnInfo(defaultValue = "HTTP") val kind: Kind = Kind.HTTP,
    /** Torrent: the .torrent file Pull keeps (magnets get one once metadata arrives). */
    val torrentPath: String? = null,
    val infoHash: String? = null,
    /** Torrent: chosen file indices, comma separated; empty = all. */
    @ColumnInfo(defaultValue = "") val selectedFiles: String = "",
    @ColumnInfo(defaultValue = "0") val sequential: Boolean = false,
    /** Torrent and video: folder the files go to. */
    val saveDir: String? = null,
    /** Video: yt-dlp format selector, e.g. `bv*+ba/b` (best) or a chosen format id. */
    val videoFormat: String? = null,
    /** Video: extra yt-dlp arguments from a custom command template. */
    val extraArgs: String? = null,
    /** Torrent: per-torrent speed limits in bytes per second; 0 = unlimited. */
    @ColumnInfo(defaultValue = "0") val downLimit: Int = 0,
    @ColumnInfo(defaultValue = "0") val upLimit: Int = 0,
    /** Torrent: trackers the user added, one URL per line. */
    @ColumnInfo(defaultValue = "") val extraTrackers: String = "",
    /** Torrent: per-file priority overrides as `index:PRIORITY,...` (e.g. `0:HIGH,2:SKIP`). */
    @ColumnInfo(defaultValue = "") val filePriorities: String = "",
) {
    val headers: Map<String, String> get() = runCatching { Json.decodeFromString<Map<String, String>>(headersJson) }.getOrDefault(emptyMap())

    val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /** Null until the server answered once: then a restart is a fresh start. */
    fun resumeData(): ResumeData? {
        val final = finalUrl ?: return null
        val info = RemoteInfo(final, totalBytes, acceptRanges, etag, lastModified, fileName, mimeType)
        return ResumeData(info, decodeSegments(segments))
    }

    companion object {
        fun encodeHeaders(headers: Map<String, String>) = Json.encodeToString(headers)
    }
}

fun encodeSegments(list: List<SegmentState>) = list.joinToString(";") { "${it.start}-${it.end}-${it.current}" }

fun decodeSegments(s: String): List<SegmentState> = s.split(';').mapNotNull { part ->
    val n = part.split('-').mapNotNull(String::toLongOrNull)
    if (n.size == 3) SegmentState(n[0], n[1], n[2]) else null
}

fun encodeFilePriorities(map: Map<Int, FilePriority>): String =
    map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.name}" }

fun decodeFilePriorities(s: String): Map<Int, FilePriority> =
    if (s.isBlank()) emptyMap()
    else s.split(',').mapNotNull { part ->
        val kv = part.split(':')
        val idx = kv.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val prio = kv.getOrNull(1)?.let { runCatching { FilePriority.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
        idx to prio
    }.toMap()

fun effectiveFilePriority(index: Int, selected: Set<Int>, overrides: Map<Int, FilePriority>): FilePriority =
    overrides[index] ?: if (selected.isEmpty() || index in selected) FilePriority.NORMAL else FilePriority.SKIP

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC")
    suspend fun withStatus(status: Status): List<DownloadEntity>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, errorKind = NULL, error = NULL WHERE id = :id")
    suspend fun setStatus(id: Long, status: Status)

    @Query("UPDATE downloads SET status = :to WHERE status = :from")
    suspend fun moveAll(from: Status, to: Status)

    /** Progress only: never touches name, file or status, so it cannot undo a concurrent status change. */
    @Query(
        """UPDATE downloads SET downloadedBytes = :downloaded, segments = :segments, finalUrl = :finalUrl,
        totalBytes = :total, etag = :etag, lastModified = :lastModified, acceptRanges = :acceptRanges,
        activeMillis = activeMillis + :activeDelta WHERE id = :id"""
    )
    suspend fun saveProgress(
        id: Long, downloaded: Long, segments: String, finalUrl: String, total: Long,
        etag: String?, lastModified: String?, acceptRanges: Boolean, activeDelta: Long,
    )

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, activeMillis = activeMillis + :activeDelta WHERE id = :id")
    suspend fun saveSimpleProgress(id: Long, downloaded: Long, total: Long, activeDelta: Long)

    @Query("UPDATE downloads SET torrentPath = :path, infoHash = :infoHash, fileName = :name, totalBytes = :total WHERE id = :id")
    suspend fun setTorrent(id: Long, path: String, infoHash: String, name: String, total: Long)

    /** The newest row for the same link, server link or torrent, to warn before downloading it twice. */
    @Query(
        """SELECT * FROM downloads WHERE url IN (:urls) OR finalUrl IN (:urls) OR (:infoHash IS NOT NULL AND infoHash = :infoHash)
        ORDER BY createdAt DESC LIMIT 1"""
    )
    suspend fun findDuplicate(urls: List<String>, infoHash: String?): DownloadEntity?

    @Query("UPDATE downloads SET fileName = :name WHERE id = :id")
    suspend fun setName(id: Long, name: String)

    @Query("UPDATE downloads SET selectedFiles = :selected WHERE id = :id")
    suspend fun setSelectedFiles(id: Long, selected: String)

    @Query("UPDATE downloads SET sequential = :sequential WHERE id = :id")
    suspend fun setSequential(id: Long, sequential: Boolean)

    @Query("UPDATE downloads SET downLimit = :down, upLimit = :up WHERE id = :id")
    suspend fun setLimits(id: Long, down: Int, up: Int)

    @Query("UPDATE downloads SET extraTrackers = :trackers WHERE id = :id")
    suspend fun setExtraTrackers(id: Long, trackers: String)

    @Query("UPDATE downloads SET filePriorities = :priorities WHERE id = :id")
    suspend fun setFilePriorities(id: Long, priorities: String)

    @Query("UPDATE downloads SET activeMillis = activeMillis + :activeDelta WHERE id = :id")
    suspend fun addActiveTime(id: Long, activeDelta: Long)

    @Query("UPDATE downloads SET status = :status, errorKind = :errorKind, error = :error, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, status: Status, errorKind: String?, error: String?, finishedAt: Long?)

    @Query("UPDATE downloads SET fileUri = :fileUri, fileName = :fileName, mimeType = :mimeType WHERE id = :id")
    suspend fun setFile(id: Long, fileUri: String, fileName: String, mimeType: String?)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [DownloadEntity::class, HistoryEntity::class, BookmarkEntity::class, ShortcutEntity::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class PullDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun history(): BrowserHistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun shortcuts(): ShortcutDao
}
