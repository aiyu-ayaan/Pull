package com.aiyu.pull.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.pull.browser.adblock.AdBlocker
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.SettingsRepository
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
    val adBlocker: AdBlocker,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Settings(),
    )

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
}
