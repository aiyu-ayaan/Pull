package com.aiyu.pull.ui.navigation

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.aiyu.pull.browser.BrowserActivity
import com.aiyu.pull.data.Status
import com.aiyu.pull.ui.add.AddDownloadSheet
import com.aiyu.pull.ui.add.AddRequest
import com.aiyu.pull.ui.components.MorphingShape
import com.aiyu.pull.ui.components.PredictiveBack
import com.aiyu.pull.ui.components.PushDrawer
import com.aiyu.pull.ui.components.heroBrush
import com.aiyu.pull.ui.components.rememberPushDrawerState
import com.aiyu.pull.ui.details.DetailsScreen
import com.aiyu.pull.ui.downloads.DownloadsScreen
import com.aiyu.pull.ui.downloads.DownloadsViewModel
import com.aiyu.pull.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data object MainRoute
@Serializable data object SettingsRoute
@Serializable data class DetailsRoute(val id: Long)

/** Something an Intent asked for: open a download (notification tap) or add a shared link. */
sealed interface AppEvent {
    data class OpenDownload(val id: Long) : AppEvent
    data class Add(val request: AddRequest) : AppEvent
}

private val addSaver = Saver<AddRequest?, String>(
    save = { it?.let { r -> Json.encodeToString(r) } ?: "" },
    restore = { if (it.isEmpty()) null else Json.decodeFromString<AddRequest>(it) },
)

/**
 * App frame: Findex-style push drawer (everything Pull can do) around the nav host; the main screen carries
 * Rewire's floating tab pill. The browser is a separate activity in its own task, so it behaves like its own app.
 */
@Composable
fun PullAppShell(event: AppEvent?, onEventHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberPushDrawerState()
    val vm: DownloadsViewModel = hiltViewModel()
    val rows by vm.downloads.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.ACTIVE) }
    // App-level so a share can open it over any screen; saveable across process death.
    var adding by rememberSaveable(stateSaver = addSaver) { mutableStateOf(null) }

    val onMain = entry?.destination?.hasRoute<MainRoute>() != false
    val onSettings = entry?.destination?.hasRoute<SettingsRoute>() == true
    val badges = rows.orEmpty().let { list ->
        mapOf(Tab.ACTIVE to list.count { it.status == Status.RUNNING || it.status == Status.FAILED })
    }

    LaunchedEffect(event) {
        when (event) {
            is AppEvent.OpenDownload -> nav.navigate(DetailsRoute(event.id)) { launchSingleTop = true }
            is AppEvent.Add -> adding = event.request
            null -> return@LaunchedEffect
        }
        onEventHandled()
    }

    val closeDrawer = { scope.launch { drawer.close() } }
    val openBrowser = { url: String?, refreshId: Long -> BrowserActivity.start(context, url, refreshId) }
    val addFromClipboard = { adding = AddRequest(clipboardUrl(context).orEmpty()) }

    PushDrawer(
        state = drawer,
        gesturesEnabled = onMain || drawer.isOpen,
        drawerContent = {
            PullDrawerSheet(
                settingsOpen = onSettings,
                onAddLink = { closeDrawer(); adding = AddRequest() },
                onPaste = { closeDrawer(); addFromClipboard() },
                onBrowser = { closeDrawer(); openBrowser(null, -1) },
                onSettings = { closeDrawer(); nav.navigate(SettingsRoute) { launchSingleTop = true } },
            )
        },
    ) {
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        NavHost(
            navController = nav,
            startDestination = MainRoute,
            enterTransition = { slideInHorizontally(spatial) { it / 4 } + fadeIn(effects) },
            exitTransition = { slideOutHorizontally(spatial) { -it / 8 } + fadeOut(effects) },
            popEnterTransition = { slideInHorizontally(spatial) { -it / 8 } + fadeIn(effects) },
            // Predictive back: the outgoing screen shrinks while the gesture is dragged (enableOnBackInvokedCallback).
            popExitTransition = { scaleOut(targetScale = 0.9f, animationSpec = effects) + fadeOut(effects) },
        ) {
            composable<MainRoute> {
                MainScreen(
                    vm = vm,
                    tab = tab,
                    badges = badges,
                    onTab = { tab = it },
                    onOpenDrawer = { scope.launch { drawer.open() } },
                    drawerProgress = { drawer.fraction },
                    onAdd = { fromClipboard -> if (fromClipboard) addFromClipboard() else adding = AddRequest() },
                    onBrowser = { openBrowser(null, -1) },
                    onOpenDetails = { nav.navigate(DetailsRoute(it)) },
                    onOpenPage = openBrowser,
                )
            }
            composable<DetailsRoute> { back ->
                DetailsScreen(
                    id = back.toRoute<DetailsRoute>().id,
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenPage = openBrowser,
                )
            }
            composable<SettingsRoute> { SettingsScreen(hiltViewModel(), onBack = { nav.popBackStack() }) }
        }
    }

    adding?.let { request ->
        AddDownloadSheet(request = request, vm = hiltViewModel(), onDismiss = { adding = null }, onAdded = { adding = null })
    }
}

/** Tabs with the floating pill (rail on wide windows), as in Rewire. Expanded windows add a details pane. */
@Composable
private fun MainScreen(
    vm: DownloadsViewModel,
    tab: Tab,
    badges: Map<Tab, Int>,
    onTab: (Tab) -> Unit,
    onOpenDrawer: () -> Unit,
    drawerProgress: () -> Float,
    onAdd: (Boolean) -> Unit,
    onBrowser: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onOpenPage: (String, Long) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val motion = MaterialTheme.motionScheme
    val effects = motion.defaultEffectsSpec<Float>()
    val fastEffects = motion.fastEffectsSpec<Float>()
    val barSpatial = motion.defaultSpatialSpec<IntOffset>()
    val barSize = motion.defaultSpatialSpec<IntSize>()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val pane = maxWidth >= 920.dp
        val list: @Composable (Modifier) -> Unit = { m ->
            // M3 "fade through" between tabs.
            AnimatedContent(tab, transitionSpec = { (fadeIn(effects) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f)) togetherWith fadeOut(fastEffects) }, modifier = m, label = "tab") { t ->
                DownloadsScreen(
                    vm = vm, tab = t,
                    onOpen = { if (pane) selected = it else onOpenDetails(it) },
                    onAdd = onAdd, onBrowser = onBrowser,
                    onOpenDrawer = onOpenDrawer, drawerProgress = drawerProgress,
                    selectedId = if (pane) selected else null,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                FloatingNavRail(tab, badges, onTab)
                if (pane) {
                    list(Modifier.width(420.dp).fillMaxHeight())
                    DetailPane(selected, vm, onClose = { selected = null }, onOpenPage = onOpenPage, modifier = Modifier.weight(1f))
                } else list(Modifier.weight(1f).fillMaxHeight())
            }
            return@BoxWithConstraints
        }
        // Phones: the pill floats over content. Scrolling down tucks it away, scrolling up brings it back.
        var scrolledAway by remember { mutableStateOf(false) }
        LaunchedEffect(tab) { scrolledAway = false }
        val hideOnScroll = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (consumed.y < -1f) scrolledAway = true else if (consumed.y > 1f) scrolledAway = false
                    return Offset.Zero
                }
            }
        }
        val density = LocalDensity.current
        var pill by remember { mutableStateOf(0.dp) }
        val fab by animateDpAsState(if (!scrolledAway) pill else 0.dp, motion.defaultSpatialSpec(), label = "fabLift")
        Box(Modifier.fillMaxSize().nestedScroll(hideOnScroll)) {
            CompositionLocalProvider(LocalBottomBarInsets provides BottomBarInsets(content = pill, fab = fab.coerceAtLeast(0.dp))) {
                list(Modifier.fillMaxSize())
            }
            AnimatedVisibility(
                !scrolledAway,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(barSpatial) { it } + fadeIn(effects),
                exit = slideOutVertically(barSpatial) { it } + fadeOut(fastEffects),
            ) {
                FloatingNavBar(tab, badges, onTab, Modifier.onSizeChanged { pill = with(density) { it.height.toDp() } })
            }
        }
    }
}

@Composable
private fun DetailPane(id: Long?, vm: DownloadsViewModel, onClose: () -> Unit, onOpenPage: (String, Long) -> Unit, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxHeight().padding(8.dp),
    ) {
        if (id == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MorphingShape(brush = heroBrush(), modifier = Modifier.size(96.dp).alpha(0.4f))
            }
        } else {
            // Back closes the pane first, following the predictive gesture.
            PredictiveBack(onBack = onClose) { DetailsScreen(id, vm, onBack = onClose, onOpenPage = onOpenPage) }
        }
    }
}

private val urlPattern = Regex("""https?://\S+""")

fun firstUrl(text: CharSequence?): String? = text?.let { urlPattern.find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }

private fun clipboardUrl(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.let(::firstUrl)
