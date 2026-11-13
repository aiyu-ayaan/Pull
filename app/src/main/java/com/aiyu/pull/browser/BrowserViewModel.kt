package com.aiyu.pull.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.pull.browser.adblock.AdBlocker
import com.aiyu.pull.data.BookmarkDao
import com.aiyu.pull.data.BookmarkEntity
import com.aiyu.pull.data.BrowserHistoryDao
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.data.HistoryEntity
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.data.ShortcutDao
import com.aiyu.pull.data.ShortcutEntity
import com.aiyu.pull.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val manager: DownloadManager,
    private val settingsRepo: SettingsRepository,
    private val historyDao: BrowserHistoryDao,
    private val bookmarkDao: BookmarkDao,
    private val shortcutDao: ShortcutDao,
    val adBlocker: AdBlocker,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Settings(),
    )

    val history: StateFlow<List<HistoryEntity>> = historyDao.observeRecent().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val bookmarks: StateFlow<List<BookmarkEntity>> = bookmarkDao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val shortcuts: StateFlow<List<ShortcutEntity>> = shortcutDao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val allowlistedHosts: StateFlow<Set<String>> = adBlocker.allowlistedHostsFlow

    fun setHostAllowlisted(host: String, allowlisted: Boolean) {
        adBlocker.setHostAllowlisted(host, allowlisted)
    }

    fun isHostAllowlisted(host: String): Boolean = adBlocker.isHostAllowlisted(host)

    fun target(id: Long): Flow<DownloadEntity?> = if (id >= 0) manager.download(id) else flowOf(null)

    fun refresh(id: Long, url: String, headers: Map<String, String>, pageUrl: String?) =
        manager.refreshLink(id, url, headers, pageUrl)

    fun setAdBlock(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setBrowserAdBlock(enabled) }
    }

    fun setPopupBlock(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setBrowserPopupBlock(enabled) }
    }

    fun setCosmetic(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setBrowserCosmetic(enabled) }
    }

    fun recordHistory(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        viewModelScope.launch {
            historyDao.insert(HistoryEntity(url = url, title = title.ifBlank { url }))
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { historyDao.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyDao.clearAll() }
    }

    fun isBookmarked(url: String): Flow<Boolean> =
        if (url.isBlank() || url == "about:blank") flowOf(false) else bookmarkDao.isBookmarked(url)

    fun toggleBookmark(url: String, title: String, currentlyBookmarked: Boolean) {
        if (url.isBlank() || url == "about:blank") return
        viewModelScope.launch {
            if (currentlyBookmarked) {
                bookmarkDao.deleteByUrl(url)
            } else {
                bookmarkDao.insert(BookmarkEntity(url = url, title = title.ifBlank { url }))
            }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookmarkDao.delete(id) }
    }

    fun addShortcut(url: String, title: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank() || cleanUrl == "about:blank") return
        val normalized = if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            cleanUrl
        } else {
            "https://$cleanUrl"
        }
        val cleanTitle = title.trim().ifBlank {
            runCatching { android.net.Uri.parse(normalized).host }.getOrNull() ?: normalized
        }
        viewModelScope.launch {
            shortcutDao.insert(ShortcutEntity(url = normalized, title = cleanTitle))
        }
    }

    fun deleteShortcut(id: Long) {
        viewModelScope.launch { shortcutDao.delete(id) }
    }
}
