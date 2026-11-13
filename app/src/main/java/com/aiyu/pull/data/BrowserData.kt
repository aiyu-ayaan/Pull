package com.aiyu.pull.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "browser_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "browser_bookmarks",
    indices = [Index(value = ["url"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "browser_shortcuts",
    indices = [Index(value = ["url"], unique = true)],
)
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface BrowserHistoryDao {
    @Query("SELECT * FROM browser_history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM browser_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM browser_history")
    suspend fun clearAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM browser_bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM browser_bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM browser_bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM browser_bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM browser_shortcuts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shortcut: ShortcutEntity)

    @Query("DELETE FROM browser_shortcuts WHERE id = :id")
    suspend fun delete(id: Long)
}
