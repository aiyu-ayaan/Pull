package com.aiyu.pull.browser

import androidx.lifecycle.ViewModel
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(private val manager: DownloadManager) : ViewModel() {
    fun target(id: Long): Flow<DownloadEntity?> = if (id >= 0) manager.download(id) else flowOf(null)

    fun refresh(id: Long, url: String, headers: Map<String, String>, pageUrl: String?) = manager.refreshLink(id, url, headers, pageUrl)
}
