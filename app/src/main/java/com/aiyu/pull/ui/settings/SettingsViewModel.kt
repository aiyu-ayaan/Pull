package com.aiyu.pull.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.data.ThemeMode
import com.aiyu.pull.video.VideoEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val videos: dagger.Lazy<VideoEngine>,
) : ViewModel() {
    /** Null until DataStore answers: MainActivity keeps the splash up until then, so the theme never flips. */
    val settings: StateFlow<Settings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repo.setTheme(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { repo.setDynamicColor(on) }
    fun setConnections(n: Int) = viewModelScope.launch { repo.setConnections(n) }
    fun setMaxDownloads(n: Int) = viewModelScope.launch { repo.setMaxDownloads(n) }
    fun setAutoResume(on: Boolean) = viewModelScope.launch { repo.setAutoResume(on) }

    suspend fun ytdlpVersion(): String? = runCatching { videos.get().version() }.getOrNull()
    suspend fun updateYtdlp(): String? = videos.get().checkAndUpdate(force = true)

    /** Any other setter, so each new option does not need its own pass-through here. */
    fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { repo.block() }
}
