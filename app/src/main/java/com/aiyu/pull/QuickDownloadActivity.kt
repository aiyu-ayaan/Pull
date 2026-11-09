package com.aiyu.pull

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.aiyu.pull.download.DownloadManager
import com.aiyu.pull.download.DownloadManager.QuickResult
import com.aiyu.pull.ui.navigation.firstUrl
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * "Quick download" in the share sheet: no window, no questions. Pull works out whether the link is a file, a
 * torrent or a video page, starts it with the default choices and says so in a toast. Progress then shows in the
 * download notification and the app.
 */
@AndroidEntryPoint
class QuickDownloadActivity : ComponentActivity() {
    @Inject lateinit var manager: DownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> intent.dataString
        }
        val link = text?.trim()?.takeIf { it.startsWith("magnet:", ignoreCase = true) } ?: firstUrl(text)
        if (link == null) {
            Toast.makeText(this, R.string.quick_no_link, Toast.LENGTH_SHORT).show()
        } else {
            val app = applicationContext
            Toast.makeText(app, R.string.quick_adding, Toast.LENGTH_SHORT).show()
            manager.quickAdd(link) { result, detail ->
                val msg = when (result) {
                    QuickResult.FILE -> app.getString(R.string.quick_file, detail ?: link)
                    QuickResult.TORRENT -> app.getString(R.string.quick_torrent)
                    QuickResult.VIDEO -> app.getString(R.string.quick_video)
                    QuickResult.NOTHING -> app.getString(R.string.quick_nothing)
                    QuickResult.FAILED -> app.getString(R.string.quick_failed, detail ?: "")
                }
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
