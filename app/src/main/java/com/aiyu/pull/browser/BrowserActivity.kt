package com.aiyu.pull.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.ui.add.AddDownloadSheet
import com.aiyu.pull.ui.add.AddRequest
import com.aiyu.pull.ui.settings.SettingsViewModel
import com.aiyu.pull.ui.theme.PullTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json

/**
 * The browser runs as its own task (own taskAffinity), so it sits in Recents next to Pull like a separate app.
 * Downloads it catches open the add sheet right here; in refresh mode the caught link goes to that download.
 */
@AndroidEntryPoint
class BrowserActivity : ComponentActivity() {
    private val settingsVm: SettingsViewModel by viewModels()
    private var url by mutableStateOf<String?>(null)
    private var refreshId by mutableStateOf(-1L)
    /** Bumped by every new launch intent so the page restarts at the requested URL. */
    private var launch by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        read(intent)
        if (savedInstanceState != null) {
            refreshId = savedInstanceState.getLong(EXTRA_REFRESH, -1)
            launch = savedInstanceState.getInt(KEY_LAUNCH)
        }
        setContent {
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            var adding by rememberSaveable(stateSaver = addSaver) { mutableStateOf(null) }
            PullTheme(s.themeMode, s.dynamicColor) {
                key(launch) {
                    BrowserScreen(
                        startUrl = url,
                        refreshId = refreshId,
                        vm = hiltViewModel(),
                        onDownload = { adding = it },
                        onRefreshed = {
                            Toast.makeText(this, R.string.browser_link_updated, Toast.LENGTH_LONG).show()
                            finish()
                        },
                        onClose = { finish() },
                    )
                }
                adding?.let { request ->
                    AddDownloadSheet(
                        request = request,
                        vm = hiltViewModel(),
                        onDismiss = { adding = null },
                        onAdded = {
                            adding = null
                            Toast.makeText(this, R.string.browser_added, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
        launch++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(EXTRA_REFRESH, refreshId)
        outState.putInt(KEY_LAUNCH, launch)
    }

    private fun read(intent: Intent?) {
        url = intent?.getStringExtra(EXTRA_URL) ?: intent?.dataString
        refreshId = intent?.getLongExtra(EXTRA_REFRESH, -1) ?: -1
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_REFRESH = "refresh_id"
        private const val KEY_LAUNCH = "launch"

        private val addSaver = Saver<AddRequest?, String>(
            save = { it?.let { r -> Json.encodeToString(r) } ?: "" },
            restore = { if (it.isEmpty()) null else Json.decodeFromString<AddRequest>(it) },
        )

        /** [refreshId] >= 0: the next download caught replaces that download's expired link. */
        fun start(context: Context, url: String? = null, refreshId: Long = -1) {
            context.startActivity(
                Intent(context, BrowserActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_REFRESH, refreshId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
