package com.aiyu.pull.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiyu.pull.BuildConfig
import com.aiyu.pull.R
import com.aiyu.pull.ui.components.MorphingShape
import com.aiyu.pull.ui.components.heroBrush

private const val GITHUB_URL = "https://github.com/aiyu-ayaan"
private const val SOURCE_URL = "https://github.com/aiyu-ayaan/Pull"
private const val PORTFOLIO_URL = "https://aiyu.co.in"

private fun Context.open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

/** Settings → About: the app, the developer, and the libraries it stands on. */
internal fun LazyListScope.about(onOpenAcknowledgements: () -> Unit) {
    item {
        Section(R.string.about_app) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.SUPPORTED_ABIS.firstOrNull().orEmpty())) },
                leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
                colors = itemColors(),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_privacy)) },
                supportingContent = { Text(stringResource(R.string.about_privacy_desc)) },
                leadingContent = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                colors = itemColors(),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_license)) },
                supportingContent = { Text(stringResource(R.string.settings_license_summary)) },
                leadingContent = { Icon(Icons.Rounded.Gavel, contentDescription = null) },
                colors = itemColors(),
            )
        }
    }
    item {
        val context = LocalContext.current
        Section(R.string.about_developer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    MorphingShape(brush = heroBrush(), modifier = Modifier.size(96.dp))
                    Text("A", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 38.sp)
                }
                Text("aiyu-ayaan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.about_built), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            LinkRow(Icons.Rounded.Code, "GitHub", GITHUB_URL) { context.open(GITHUB_URL) }
            LinkRow(Icons.Rounded.Language, stringResource(R.string.about_portfolio), "aiyu.co.in") { context.open(PORTFOLIO_URL) }
        }
    }
    item {
        val context = LocalContext.current
        Section(R.string.about_open_source) {
            LinkRow(Icons.Rounded.Code, stringResource(R.string.about_source), SOURCE_URL.removePrefix("https://")) { context.open(SOURCE_URL) }
            LinkRow(Icons.Rounded.Description, stringResource(R.string.about_acknowledgements), stringResource(R.string.about_acknowledgements_desc), external = false, onClick = onOpenAcknowledgements)
        }
    }
}

/** Name, project URL, license. Keep in sync with gradle/libs.versions.toml. */
private data class Library(val name: String, val url: String, val license: String)

private const val APACHE = "Apache License 2.0"

private val libraries = listOf(
    Library("Kotlin", "https://github.com/JetBrains/kotlin", APACHE),
    Library("kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines", APACHE),
    Library("kotlinx.serialization", "https://github.com/Kotlin/kotlinx.serialization", APACHE),
    Library("Jetpack Compose", "https://developer.android.com/jetpack/compose", APACHE),
    Library("Material 3 for Compose", "https://developer.android.com/jetpack/androidx/releases/compose-material3", APACHE),
    Library("Material Icons Extended", "https://developer.android.com/jetpack/androidx/releases/compose-material", APACHE),
    Library("AndroidX Core KTX", "https://developer.android.com/jetpack/androidx/releases/core", APACHE),
    Library("AndroidX Core SplashScreen", "https://developer.android.com/jetpack/androidx/releases/core", APACHE),
    Library("AndroidX Lifecycle", "https://developer.android.com/jetpack/androidx/releases/lifecycle", APACHE),
    Library("AndroidX Activity Compose", "https://developer.android.com/jetpack/androidx/releases/activity", APACHE),
    Library("AndroidX Navigation Compose", "https://developer.android.com/jetpack/androidx/releases/navigation", APACHE),
    Library("AndroidX Room", "https://developer.android.com/jetpack/androidx/releases/room", APACHE),
    Library("AndroidX DataStore", "https://developer.android.com/jetpack/androidx/releases/datastore", APACHE),
    Library("AndroidX WorkManager", "https://developer.android.com/jetpack/androidx/releases/work", APACHE),
    Library("AndroidX Graphics Shapes", "https://developer.android.com/jetpack/androidx/releases/graphics", APACHE),
    Library("Hilt", "https://github.com/google/dagger", APACHE),
    Library("OkHttp", "https://github.com/square/okhttp", APACHE),
    Library("libtorrent4j", "https://github.com/aldenml/libtorrent4j", "MIT License"),
    Library("libtorrent", "https://github.com/arvidn/libtorrent", "BSD 3-Clause License"),
    Library("youtubedl-android", "https://github.com/JunkFood02/youtubedl-android", "GNU General Public License v3.0"),
    Library("yt-dlp", "https://github.com/yt-dlp/yt-dlp", "The Unlicense"),
    Library("FFmpeg", "https://ffmpeg.org", "GNU General Public License v3.0"),
    Library("aria2", "https://github.com/aria2/aria2", "GNU General Public License v2.0"),
    Library("Python", "https://www.python.org", "Python Software Foundation License"),
)

/** Settings → About → Acknowledgements. */
internal fun LazyListScope.acknowledgements() {
    item {
        val context = LocalContext.current
        Group {
            libraries.forEach { lib ->
                ListItem(
                    headlineContent = { Text(lib.name) },
                    supportingContent = { Text("${lib.license}\n${lib.url.removePrefix("https://")}") },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                    colors = itemColors(),
                    modifier = Modifier.clickable(role = Role.Button) { context.open(lib.url) },
                )
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, external: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(if (external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = itemColors(),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}
