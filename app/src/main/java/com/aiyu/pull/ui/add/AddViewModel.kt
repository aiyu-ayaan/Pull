package com.aiyu.pull.ui.add

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.pull.R
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.download.DownloadManager
import com.aiyu.pull.engine.DownloadException
import com.aiyu.pull.engine.ErrorKind
import com.aiyu.pull.engine.RemoteInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProbeState {
    data object Idle : ProbeState
    data object Loading : ProbeState
    data class Ready(val info: RemoteInfo) : ProbeState
    data class Failed(val message: String) : ProbeState
}

@HiltViewModel
class AddViewModel @Inject constructor(
    private val manager: DownloadManager,
    settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe.asStateFlow()
    val connections: StateFlow<Int> = settings.settings.map { it.connections }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    private var job: Job? = null
    private var probedUrl: String? = null

    fun probe(url: String, headers: Map<String, String>) {
        if (url == probedUrl) return
        probedUrl = url
        job?.cancel()
        if (!url.startsWith("http://") && !url.startsWith("https://")) { _probe.value = ProbeState.Idle; return }
        _probe.value = ProbeState.Loading
        job = viewModelScope.launch {
            _probe.value = try {
                ProbeState.Ready(manager.probe(url, headers))
            } catch (c: CancellationException) {
                throw c
            } catch (e: DownloadException) {
                ProbeState.Failed(if (e.kind == ErrorKind.LINK_EXPIRED) context.getString(R.string.probe_expired, e.httpCode) else e.message.orEmpty())
            } catch (e: Exception) {
                ProbeState.Failed(e.message ?: context.getString(R.string.probe_failed))
            }
        }
    }

    suspend fun add(url: String, name: String?, pageUrl: String?, headers: Map<String, String>, connections: Int, start: Boolean): Long {
        val id = manager.add(url, name, headers, pageUrl, connections.coerceIn(1, 32), start)
        reset()
        return id
    }

    fun reset() {
        job?.cancel()
        probedUrl = null
        _probe.value = ProbeState.Idle
    }
}
