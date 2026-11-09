package com.aiyu.pull.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.aiyu.pull.engine.RemoteInfo
import com.aiyu.pull.engine.ResumeData
import com.aiyu.pull.engine.SegmentState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

enum class Status { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

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

    @Query("UPDATE downloads SET activeMillis = activeMillis + :activeDelta WHERE id = :id")
    suspend fun addActiveTime(id: Long, activeDelta: Long)

    @Query("UPDATE downloads SET status = :status, errorKind = :errorKind, error = :error, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, status: Status, errorKind: String?, error: String?, finishedAt: Long?)

    @Query("UPDATE downloads SET fileUri = :fileUri, fileName = :fileName, mimeType = :mimeType WHERE id = :id")
    suspend fun setFile(id: Long, fileUri: String, fileName: String, mimeType: String?)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = true)
abstract class PullDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
}
