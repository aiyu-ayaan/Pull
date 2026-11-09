package com.aiyu.pull.ui.navigation

import android.content.ClipboardManager
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.aiyu.pull.R
import com.aiyu.pull.ui.add.AddDownloadSheet
import com.aiyu.pull.ui.add.AddRequest
import com.aiyu.pull.ui.browser.BrowserScreen
import com.aiyu.pull.ui.components.MorphingShape
import com.aiyu.pull.ui.components.PredictiveBack
import com.aiyu.pull.ui.components.heroBrush
import com.aiyu.pull.ui.components.isExpandedWidth
import com.aiyu.pull.ui.details.DetailsScreen
import com.aiyu.pull.ui.downloads.DownloadsScreen
import com.aiyu.pull.ui.downloads.DownloadsViewModel
import com.aiyu.pull.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data object DownloadsRoute
@Serializable data class BrowserRoute(val url: String? = null, val refreshId: Long = -1)
@Serializable data object SettingsRoute
@Serializable data class DetailsRoute(val id: Long)

/** Something an Intent asked for: open a download (notification tap) or add a shared link. */
sealed interface AppEvent {
    data class OpenDownload(val id: Long) : AppEvent
    data class Add(val url: String) : AppEvent
}

private val addSaver = Saver<AddRequest?, String>(
    save = { it?.let { r -> Json.encodeToString(r) } ?: "" },
    restore = { if (it.isEmpty()) null else Json.decodeFromString<AddRequest>(it) },
)

@Composable
fun PullAppShell(event: AppEvent?, onEventHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val context = LocalContext.current
    // App-level so the browser and a share can open it over any tab; saveable across process death.
    var adding by rememberSaveable(stateSaver = addSaver) { mutableStateOf(null) }

    LaunchedEffect(event) {
        when (event) {
            is AppEvent.OpenDownload -> nav.navigate(DetailsRoute(event.id)) { launchSingleTop = true }
            is AppEvent.Add -> adding = AddRequest(event.url)
            null -> return@LaunchedEffect
        }
        onEventHandled()
    }

    val refreshing = dest?.hasRoute<BrowserRoute>() == true && entry?.toRoute<BrowserRoute>()?.refreshId?.let { it >= 0 } == true
    val fullScreen = dest?.hasRoute<DetailsRoute>() == true || refreshing
    val tabs = listOf(
        Triple(DownloadsRoute, Icons.Rounded.Download, R.string.nav_downloads),
        Triple(BrowserRoute(), Icons.Rounded.Public, R.string.nav_browser),
        Triple(SettingsRoute, Icons.Rounded.Settings, R.string.nav_settings),
    )
    NavigationSuiteScaffold(
        layoutType = if (fullScreen) NavigationSuiteType.None
        else NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()),
        navigationSuiteItems = {
            tabs.forEach { (route, icon, label) ->
                item(
                    selected = dest?.hierarchy?.any { it.hasRoute(route::class) } == true,
                    onClick = { nav.navigateTop(route) },
                    icon = { Icon(icon, null) },
                    label = { Text(stringResource(label)) },
                )
            }
        },
    ) {
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        NavHost(
            navController = nav,
            startDestination = DownloadsRoute,
            // Pop transitions follow the predictive back gesture (enableOnBackInvokedCallback in the manifest).
            enterTransition = { slideInHorizontally(spatial) { it / 4 } + fadeIn(effects) },
            exitTransition = { slideOutHorizontally(spatial) { -it / 8 } + fadeOut(effects) },
            popEnterTransition = { slideInHorizontally(spatial) { -it / 8 } + fadeIn(effects) },
            popExitTransition = { slideOutHorizontally(spatial) { it / 4 } + fadeOut(effects) },
        ) {
            composable<DownloadsRoute> {
                val vm: DownloadsViewModel = hiltViewModel()
                DownloadsHome(
                    vm = vm,
                    onAdd = { fromClipboard -> adding = AddRequest(if (fromClipboard) clipboardUrl(context).orEmpty() else "") },
                    onBrowser = { nav.navigateTop(BrowserRoute()) },
                    onOpenDetails = { nav.navigate(DetailsRoute(it)) },
                    onOpenPage = { url, id -> nav.navigate(BrowserRoute(url, id)) },
                )
            }
            composable<DetailsRoute> { back ->
                DetailsScreen(
                    id = back.toRoute<DetailsRoute>().id,
                    vm = hiltViewModel(),
                    onBack = { nav.popBackStack() },
                    onOpenPage = { url, id -> nav.navigate(BrowserRoute(url, id)) },
                )
            }
            composable<BrowserRoute> { back ->
                val route = back.toRoute<BrowserRoute>()
                BrowserScreen(
                    startUrl = route.url,
                    refreshId = route.refreshId,
                    vm = hiltViewModel(),
                    onDownload = { adding = it },
                    onRefreshed = { nav.popBackStack() },
                    onClose = if (route.refreshId >= 0) ({ nav.popBackStack() }) else null,
                )
            }
            composable<SettingsRoute> { SettingsScreen(hiltViewModel()) }
        }
    }

    adding?.let { request ->
        AddDownloadSheet(
            request = request,
            vm = hiltViewModel(),
            onDismiss = { adding = null },
            onAdded = { adding = null },
        )
    }
}

/** Phones: list, details pushed on top. Expanded windows: list and details side by side. */
@Composable
private fun DownloadsHome(
    vm: DownloadsViewModel,
    onAdd: (Boolean) -> Unit,
    onBrowser: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onOpenPage: (String, Long) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    if (!isExpandedWidth()) {
        DownloadsScreen(vm, onOpen = onOpenDetails, onAdd = onAdd, onBrowser = onBrowser, selectedId = null)
        return
    }
    Row(Modifier.fillMaxSize()) {
        DownloadsScreen(vm, onOpen = { selected = it }, onAdd = onAdd, onBrowser = onBrowser, selectedId = selected, modifier = Modifier.width(420.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp),
        ) {
            val id = selected
            if (id == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MorphingShape(brush = heroBrush(), modifier = Modifier.size(96.dp).alpha(0.4f))
                }
            } else {
                // Back closes the pane first, following the predictive gesture.
                PredictiveBack(onBack = { selected = null }) {
                    DetailsScreen(id, vm, onBack = { selected = null }, onOpenPage = onOpenPage)
                }
            }
        }
    }
}

private fun NavHostController.navigateTop(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private val urlPattern = Regex("""https?://\S+""")

fun firstUrl(text: CharSequence?): String? = text?.let { urlPattern.find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }

private fun clipboardUrl(context: android.content.Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.let(::firstUrl)
