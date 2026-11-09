package com.aiyu.pull

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.ui.add.AddDownloadSheet
import com.aiyu.pull.ui.add.AddRequest
import com.aiyu.pull.ui.navigation.firstUrl
import com.aiyu.pull.ui.settings.SettingsViewModel
import com.aiyu.pull.ui.theme.PullTheme
import com.aiyu.pull.ui.theme.WithClickHaptics
import dagger.hilt.android.AndroidEntryPoint

/**
 * The share-sheet target. It has no window of its own: only the add bottom sheet (file, torrent or video, with all
 * its options) floats over the app the link was shared from, and closing it returns there. Pull itself never opens.
 */
@AndroidEntryPoint
class QuickDownloadActivity : ComponentActivity() {
    private val settingsVm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val link = text?.trim()?.takeIf { it.startsWith("magnet:", ignoreCase = true) } ?: firstUrl(text)
        if (link == null) {
            finish()
            return
        }
        setContent {
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            PullTheme(s.themeMode, s.dynamicColor) {
                WithClickHaptics {
                    AddDownloadSheet(
                        request = AddRequest(link),
                        vm = hiltViewModel(),
                        onDismiss = { finish() },
                        onAdded = { finish() },
                        // Only now does the full app open: the user asked to see the earlier download.
                        onOpenExisting = { id ->
                            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOWNLOAD_ID, id))
                            finish()
                        },
                    )
                }
            }
        }
    }
}
