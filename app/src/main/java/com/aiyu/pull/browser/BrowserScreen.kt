package com.aiyu.pull.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableIntStateOf
import com.aiyu.pull.ui.theme.clickHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.browser.adblock.AdBlocker
import com.aiyu.pull.ui.add.AddRequest
import com.aiyu.pull.ui.components.MorphingShape
import com.aiyu.pull.ui.components.heroBrush
import com.aiyu.pull.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private data class LongPressTarget(val url: String, val pageUrl: String?, val userAgent: String?)

/**
 * Tabbed WebView browser laid out like Chrome with the bottom toolbar: new tab page, full-screen omnibox with history,
 * tab grid with previews. Ad blocker, popup blocker and download capture run per tab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    startUrl: String?,
    refreshId: Long,
    vm: BrowserViewModel,
    onDownload: (AddRequest) -> Unit,
    onRefreshed: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val allowlistedHosts by vm.allowlistedHosts.collectAsStateWithLifecycle()
    val target by remember(refreshId) { vm.target(refreshId) }.collectAsStateWithLifecycle(null)

    val tabs = remember { mutableStateListOf<BrowserTab>() }
    var activeTabId by rememberSaveable { mutableLongStateOf(1L) }
    var nextTabId by rememberSaveable { mutableLongStateOf(1L) }

    var showShield by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var addShortcut by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showDetectedMedia by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var swipeDir by remember { mutableIntStateOf(0) }
    val motion = MaterialTheme.motionScheme
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= BrowserWideWidth.value
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }

    // WebView callbacks outlive the composition that created them; they reach closeTab through this holder.
    val closeTabRefHolder = remember { mutableStateOf<((Long) -> Unit)?>(null) }
    val activeTab: BrowserTab? = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    val isCurrentBookmarked by remember(activeTab?.url) {
        vm.isBookmarked(activeTab?.url.orEmpty())
    }.collectAsStateWithLifecycle(false)

    fun browserHeaders(url: String, ua: String?, page: String?) = buildMap {
        ua?.let { put("User-Agent", it) }
        val cookie = CookieManager.getInstance().getCookie(url)
            ?: page?.let { CookieManager.getInstance().getCookie(it) }
        cookie?.let { put("Cookie", it) }
        page?.let { put("Referer", it) }
    }

    val onCatch by rememberUpdatedState { url: String, ua: String?, disposition: String?, mime: String?, page: String? ->
        val headers = browserHeaders(url, ua, page)
        if (refreshId >= 0) {
            vm.refresh(refreshId, url, headers, page)
            onRefreshed()
        } else {
            onDownload(AddRequest(url, URLUtil.guessFileName(url, disposition, mime), page, headers, mimeHint = mime))
        }
    }

    // A link ending in .zip etc. is often a landing page. Ask the server first: a file goes to Pull, a page loads.
    val onExtensionHit by rememberUpdatedState { tab: BrowserTab, url: String, ua: String?, page: String? ->
        scope.launch {
            tab.loading = true
            tab.progress = 10
            val mime = vm.probeMime(url, browserHeaders(url, ua, page))
            tab.loading = false
            if (mime != null && !mime.startsWith("text/html")) {
                onCatch(url, ua, null, mime, page)
                if (tab.isPopupCandidate && !tab.hasPage) closeTabRefHolder.value?.invoke(tab.id)
            } else {
                tab.passThroughUrl = url
                tab.webView.loadUrl(url, page?.let { mapOf("Referer" to it) } ?: emptyMap())
            }
        }
        Unit
    }

    var lastPopupTime by remember { mutableLongStateOf(0L) }

    fun select(tab: BrowserTab, dir: Int = 0) {
        swipeDir = dir
        tabs.find { it.id == activeTabId }?.captureThumbnail()
        tab.lastShown = System.currentTimeMillis()
        activeTabId = tab.id
    }

    fun newTab(url: String, isPopup: Boolean = false, opener: Long? = null, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextTabId++, WebView(context), url, "", isPopupCandidate = isPopup, openerId = opener)
        setupTabWebView(
            tab, context, vm.adBlocker, { settings }, { lastPopupTime }, { lastPopupTime = it },
            onCatch = { u, ua, d, m, p -> onCatch(u, ua, d, m, p) },
            onExtensionHit = { t, u, ua, p -> onExtensionHit(t, u, ua, p) },
            onLongPress = { u, p, ua -> longPressTarget = LongPressTarget(u, p, ua) },
            onRecordHistory = { u, t -> vm.recordHistory(u, t) },
            onCloseTab = { closeTabRefHolder.value?.invoke(it) },
            onCreateNewTab = { openerId -> newTab("", isPopup = true, opener = openerId) },
        )
        val index = opener?.let { o -> tabs.indexOfFirst { it.id == o } }?.takeIf { it >= 0 }
        if (index != null) tabs.add(index + 1, tab) else tabs.add(tab)
        if (select) select(tab)
        if (tab.hasPage) tab.webView.loadUrl(url)
        return tab
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        tab.destroy()
        if (tabs.isEmpty()) {
            newTab("")
        } else if (activeTabId == id) {
            // Back to the tab that opened it, like Chrome; else the neighbour.
            select(tabs.find { it.id == tab.openerId } ?: tabs[(index - 1).coerceAtLeast(0)])
        }
    }
    closeTabRefHolder.value = ::closeTab

    fun navigateTo(input: String) {
        editing = false
        val url = resolveAddressInput(input, settings.browserSearchEngine)
        if (url.isBlank()) return
        val tab = activeTab ?: newTab("")
        tab.showNtp = false
        tab.url = url
        tab.webView.loadUrl(url)
    }

    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) newTab(startUrl.orEmpty())
    }

    DisposableEffect(Unit) {
        onDispose {
            tabs.forEach { it.destroy() }
            tabs.clear()
            closeTabRefHolder.value = null
        }
    }

    // Back: the last enabled handler wins, so page navigation comes first and overlays after it.
    fun goBack() {
        val tab = activeTab ?: return
        when {
            tab.showNtp && tab.hasPage -> tab.showNtp = false
            tab.canBack -> tab.webView.goBack()
            else -> closeTab(tab.id)
        }
    }
    BackHandler(enabled = activeTab != null && (activeTab.canBack || activeTab.openerId != null || (activeTab.showNtp && activeTab.hasPage))) { goBack() }
    BackHandler(enabled = showTabs) { showTabs = false }
    BackHandler(enabled = editing) { editing = false }

    val tab = activeTab
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding().clickHaptics()) {
        AnimatedVisibility(refreshId >= 0) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.browser_refresh_hint, target?.fileName.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.statusBarsPadding().padding(horizontal = Dimens.lg, vertical = Dimens.md),
                )
            }
        }
        val menu: @Composable () -> Unit = {
            BrowserMenu(
                tab = tab,
                bookmarked = isCurrentBookmarked,
                onForward = { tab?.webView?.goForward() },
                onToggleBookmark = { tab?.let { vm.toggleBookmark(it.url, it.displayTitle, isCurrentBookmarked) } },
                onReload = { tab?.let { if (it.loading) it.webView.stopLoading() else it.webView.reload() } },
                onNewTab = { newTab("") },
                onHistory = { showHistory = true },
                onBookmarks = { showBookmarks = true },
                onAddShortcut = {
                    addShortcut = if (tab != null && !tab.showNtp && tab.hasPage) tab.url to tab.displayTitle else "" to ""
                },
                onShield = { showShield = true },
                onDesktop = { tab?.let { it.applyDesktopMode(!it.isDesktopMode) } },
                onCloseBrowser = onClose,
            )
        }
        if (wide && tab != null) {
            BrowserWideBar(
                tabs = tabs,
                tab = tab,
                onSelect = { select(it, 0) },
                onCloseTab = { closeTab(it) },
                onNewTab = { newTab("") },
                onCloseBrowser = onClose,
                onBack = { goBack() },
                canBack = tab.canBack || (tab.showNtp && tab.hasPage),
                onForward = { tab.webView.goForward() },
                onReload = { if (tab.loading) tab.webView.stopLoading() else tab.webView.reload() },
                onHome = { tab.captureThumbnail(); tab.showNtp = true },
                onOmnibox = { editing = true },
                onSiteInfo = { showShield = true },
                onTabs = { tab.captureThumbnail(); showTabs = true },
                menu = menu,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Swiping the omnibox slides pages sideways like Chrome; other switches cross-fade.
            AnimatedContent(
                targetState = tab?.id,
                transitionSpec = {
                    val dir = swipeDir
                    if (dir == 0) {
                        (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f)) togetherWith fadeOut(motion.fastEffectsSpec())
                    } else {
                        slideInHorizontally(motion.defaultSpatialSpec()) { it * dir } togetherWith slideOutHorizontally(motion.defaultSpatialSpec()) { -it * dir }
                    }
                },
                label = "tab",
            ) { id ->
                val t = tabs.find { it.id == id } ?: return@AnimatedContent
                AndroidView(
                    factory = {
                        (t.webView.parent as? ViewGroup)?.removeView(t.webView)
                        t.webView.isHapticFeedbackEnabled = false
                        t.webView
                    },
                    modifier = Modifier.fillMaxSize().then(if (refreshId >= 0 || wide) Modifier else Modifier.statusBarsPadding()),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = tab == null || tab.showNtp,
                enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f),
                exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 1.04f),
            ) {
                NewTabPage(
                    shortcuts = shortcuts,
                    continueTab = tabs.filter { it.hasPage && !it.showNtp && it.id != tab?.id }.maxByOrNull { it.lastShown }
                        ?: tab?.takeIf { it.hasPage },
                    tabCount = tabs.size,
                    onSearch = { editing = true },
                    onOpen = { navigateTo(it) },
                    onOpenInNewTab = { url -> newTab(url) },
                    onDeleteShortcut = { vm.deleteShortcut(it) },
                    onAddShortcut = { addShortcut = "" to "" },
                    onContinue = { t -> if (t.id == tab?.id) t.showNtp = false else select(t) },
                    onTabs = { tab?.captureThumbnail(); showTabs = true },
                    onClose = onClose,
                    menu = menu,
                    showTopRow = !wide,
                )
            }
            val mediaCount = tab?.detectedMedia?.size ?: 0
            androidx.compose.animation.AnimatedVisibility(
                visible = tab != null && !tab.showNtp && mediaCount > 0,
                enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                Surface(
                    onClick = { showDetectedMedia = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier.padding(Dimens.lg),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm)) {
                        Icon(Icons.Rounded.PlayCircle, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Dimens.xs))
                        Text(
                            pluralStringResource(R.plurals.browser_media_count, mediaCount, mediaCount),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !wide && tab != null && !tab.showNtp,
            enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(),
            exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(),
        ) {
            if (tab == null) return@AnimatedVisibility
            BrowserToolbar(
                tab = tab,
                tabCount = tabs.size,
                onHome = { tab.captureThumbnail(); tab.showNtp = true },
                onOmnibox = { editing = true },
                onSiteInfo = { showShield = true },
                onNewTab = { newTab("") },
                onTabs = { tab.captureThumbnail(); showTabs = true },
                onSwipeTab = { dir ->
                    val i = tabs.indexOfFirst { it.id == tab.id } + dir
                    if (i in tabs.indices) {
                        select(tabs[i], dir)
                    }
                },
                menu = menu,
            )
        }
    }

    // Chrome zooms out of the page into the grid.
    AnimatedVisibility(
        visible = showTabs,
        enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 1.1f),
        exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 1.1f),
    ) {
        TabSwitcher(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = { id -> tabs.find { it.id == id }?.let(::select) },
            onCloseTab = { id -> closeTab(id) },
            onNewTab = { newTab("") },
            onCloseAllTabs = {
                tabs.toList().forEach { it.destroy() }
                tabs.clear()
                newTab("")
                showTabs = false
            },
            onDismiss = { showTabs = false },
        )
    }

    AnimatedVisibility(
        visible = editing,
        enter = fadeIn(motion.fastEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 12 },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it / 12 },
    ) {
        OmniboxEditor(
            initial = if (tab != null && !tab.showNtp && tab.hasPage) tab.url else "",
            history = history,
            bookmarks = bookmarks,
            onGo = { navigateTo(it) },
            onAddShortcut = { url, title -> vm.addShortcut(url, title); Toast.makeText(context, R.string.browser_shortcut_added, Toast.LENGTH_SHORT).show() },
            onDismiss = { editing = false },
        )
    }

    if (showShield) {
        val host = tab?.url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }.orEmpty()
        val isHostAllowlisted = remember(host, allowlistedHosts) { vm.isHostAllowlisted(host) }
        ShieldSheet(
            host = host,
            blockedCount = tab?.blockedCount ?: 0,
            adBlockEnabled = settings.browserAdBlock,
            onAdBlockToggle = { vm.setAdBlock(it) },
            popupBlockEnabled = settings.browserPopupBlock,
            onPopupBlockToggle = { vm.setPopupBlock(it) },
            cosmeticEnabled = settings.browserCosmetic,
            onCosmeticToggle = { vm.setCosmetic(it) },
            isHostAllowlisted = isHostAllowlisted,
            onHostAllowlistToggle = { allow ->
                vm.setHostAllowlisted(host, allow)
                if (allow) tab?.blockedCount = 0
                tab?.webView?.reload()
            },
            onDismiss = { showShield = false },
        )
    }

    if (showDetectedMedia) {
        DetectedMediaSheet(
            mediaList = tab?.detectedMedia.orEmpty(),
            onSelect = { item -> onCatch(item.url, tab?.webView?.settings?.userAgentString, null, null, tab?.url) },
            onClear = { tab?.clearDetectedMedia() },
            onDismiss = { showDetectedMedia = false },
        )
    }

    longPressTarget?.let { lp ->
        LongPressTargetSheet(
            url = lp.url,
            onDownloadWithPull = { onCatch(lp.url, lp.userAgent, null, null, lp.pageUrl) },
            onOpenInNewTab = { newTab(lp.url, opener = activeTabId) },
            onCopyLink = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("URL", lp.url))
                Toast.makeText(context, R.string.browser_link_copied, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { longPressTarget = null },
        )
    }

    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            onSelect = { navigateTo(it) },
            onDelete = { vm.deleteBookmark(it) },
            onDismiss = { showBookmarks = false },
        )
    }

    if (showHistory) {
        HistorySheet(
            history = history,
            onSelect = { navigateTo(it) },
            onDelete = { vm.deleteHistoryItem(it) },
            onClearAll = { vm.clearHistory() },
            onDismiss = { showHistory = false },
        )
    }

    addShortcut?.let { (url, title) ->
        AddShortcutDialog(
            initialUrl = url,
            initialTitle = title,
            onAdd = { u, t ->
                vm.addShortcut(u, t)
                Toast.makeText(context, R.string.browser_shortcut_added, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { addShortcut = null },
        )
    }
}

/** Configures settings and clients for a [BrowserTab]'s WebView. */
@SuppressLint("SetJavaScriptEnabled")
private fun setupTabWebView(
    tab: BrowserTab,
    context: Context,
    adBlocker: AdBlocker,
    getSettings: () -> com.aiyu.pull.data.Settings,
    getLastPopupTime: () -> Long,
    setLastPopupTime: (Long) -> Unit,
    onCatch: (url: String, ua: String?, disposition: String?, mime: String?, page: String?) -> Unit,
    onExtensionHit: (tab: BrowserTab, url: String, ua: String?, page: String?) -> Unit,
    onLongPress: (url: String, pageUrl: String?, userAgent: String?) -> Unit,
    onRecordHistory: (url: String, title: String) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCreateNewTab: (openerId: Long) -> BrowserTab,
) {
    val wv = tab.webView
    wv.isHapticFeedbackEnabled = false
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        builtInZoomControls = true
        displayZoomControls = false
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = false
        safeBrowsingEnabled = true
    }

    wv.addJavascriptInterface(
        PullBridge(
            context = context,
            webView = wv,
            getPageUrl = { tab.url },
            onCatch = onCatch,
        ),
        "PullBridge",
    )

    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val dest = request.url.toString()
            val currentSettings = getSettings()

            // 1. Block ad & tracker redirects
            if (currentSettings.browserAdBlock && adBlocker.isBlocked(dest, view.url)) {
                tab.blockedCount++
                if (tab.isPopupCandidate) {
                    onCloseTab(tab.id)
                }
                return true
            }

            // 2. Handle external non-web schemes
            if (!dest.startsWith("http://") && !dest.startsWith("https://") && !dest.startsWith("about:")) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                if (tab.isPopupCandidate) {
                    onCloseTab(tab.id)
                }
                return true
            }

            // 3. Intercept downloadable file extensions
            if (dest == tab.passThroughUrl) {
                tab.passThroughUrl = null
                return false
            }
            if (currentSettings.browserInterceptByExtension && request.isForMainFrame && shouldInterceptByExtension(dest)) {
                onExtensionHit(tab, dest, view.settings.userAgentString, view.url)
                return true
            }

            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val currentSettings = getSettings()
            val reqUrl = request.url.toString()
            // Runs on a WebView IO thread: view.url would throw there, tab.url is safe to read.
            if (currentSettings.browserAdBlock && adBlocker.isBlocked(reqUrl, tab.url)) {
                view.post { tab.blockedCount++ }
                return adBlocker.createBlockedResponse(reqUrl)
            }
            if (isSniffableMediaUrl(reqUrl, request.requestHeaders["Accept"])) {
                view.post { tab.recordMedia(reqUrl) }
            }
            return null
        }

        override fun onLoadResource(view: WebView, url: String) {
            val currentSettings = getSettings()
            if (!(currentSettings.browserAdBlock && adBlocker.isBlocked(url, tab.url))) {
                if (isSniffableMediaUrl(url)) {
                    tab.recordMedia(url)
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val currentSettings = getSettings()
            if (url != "about:blank") {
                tab.loading = true
                tab.progress = 5
                tab.showNtp = false
                if (currentSettings.browserAdBlock && adBlocker.isBlocked(url, view.url)) {
                    view.stopLoading()
                    tab.blockedCount++
                    if (tab.isPopupCandidate) {
                        onCloseTab(tab.id)
                    } else if (view.canGoBack()) {
                        view.goBack()
                    }
                    return
                }
                if (tab.url != url) {
                    tab.clearDetectedMedia()
                }
                tab.url = url
                if (isSniffableMediaUrl(url)) {
                    tab.recordMedia(url)
                }
            } else {
                tab.clearDetectedMedia()
            }

            val hostAllowlisted = adBlocker.isHostAllowlisted(url)
            if (!hostAllowlisted) {
                // Inject popup shield script to neutralize JS popup loops
                if (currentSettings.browserPopupBlock) {
                    view.evaluateJavascript(AdBlocker.POPUP_SHIELD_SCRIPT, null)
                }
                // Inject cosmetic CSS
                if (currentSettings.browserAdBlock && currentSettings.browserCosmetic) {
                    view.evaluateJavascript(AdBlocker.INJECT_COSMETIC_SCRIPT, null)
                }
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val currentSettings = getSettings()
            if (!adBlocker.isHostAllowlisted(url) && currentSettings.browserAdBlock && currentSettings.browserCosmetic) {
                view.evaluateJavascript(AdBlocker.INJECT_COSMETIC_SCRIPT, null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.loading = false
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
            view.postDelayed({ tab.captureThumbnail() }, 600)
            if (url.isNotBlank() && url != "about:blank") {
                view.evaluateJavascript(BLOB_POST_CAPTURE_SCRIPT, null)
                if (!tab.isIncognito) {
                    onRecordHistory(url, view.title.orEmpty().ifBlank { url })
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
            if (url != "about:blank") {
                tab.url = url
            }
        }
    }

    wv.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) tab.title = title
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tab.favicon = icon
            if (icon != null && !tab.isIncognito) Favicons.save(context, tab.url, icon)
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            if (resultMsg == null) return false
            val currentSettings = getSettings()
            val pageUrl = view?.url ?: tab.url

            if (currentSettings.browserPopupBlock && !adBlocker.isHostAllowlisted(pageUrl)) {
                // Block popups triggered without user gesture
                if (!isUserGesture) {
                    tab.blockedCount++
                    return false
                }

                // Check hit test result if available
                val hit = view?.hitTestResult
                val extra = hit?.extra
                if (!extra.isNullOrBlank() && adBlocker.isBlocked(extra, pageUrl)) {
                    tab.blockedCount++
                    return false
                }

                // Throttling rapid-fire popups (< 800 ms)
                val now = System.currentTimeMillis()
                if (now - getLastPopupTime() < 800) {
                    tab.blockedCount++
                    return false
                }
                setLastPopupTime(now)
            }

            // Allowed new tab requested: open in a new tab
            val newTab = onCreateNewTab(tab.id)
            val transport = resultMsg.obj as? WebView.WebViewTransport
            if (transport != null) {
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }
            return false
        }

        override fun onCloseWindow(window: WebView?) {
            onCloseTab(tab.id)
        }
    }

    wv.setDownloadListener { url, ua, disposition, mime, _ ->
        if (url.startsWith("blob:")) {
            val guessed = URLUtil.guessFileName(url, disposition, mime)
            val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val escapedName = guessed.replace("\\", "\\\\").replace("'", "\\'")
            wv.evaluateJavascript(
                """
                (function() {
                    fetch('$escapedUrl').then(function(r) { return r.blob(); }).then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (window.PullBridge && reader.result) {
                                window.PullBridge.onBlobDownload(reader.result, '$escapedName', blob.type || '');
                            }
                        };
                        reader.readAsDataURL(blob);
                    }).catch(function() {});
                })();
                """.trimIndent(),
                null,
            )
        } else {
            onCatch(url, ua, disposition, mime, tab.url)
        }
    }

    wv.setOnLongClickListener { view ->
        val hit = (view as? WebView)?.hitTestResult ?: return@setOnLongClickListener false
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
            WebView.HitTestResult.IMAGE_TYPE -> {
                val extra = hit.extra
                if (!extra.isNullOrBlank() && (extra.startsWith("http://") || extra.startsWith("https://"))) {
                    onLongPress(extra, tab.url, wv.settings.userAgentString)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
}

private class PullBridge(
    private val context: Context,
    private val webView: WebView,
    private val getPageUrl: () -> String,
    private val onCatch: (url: String, ua: String?, disposition: String?, mime: String?, page: String?) -> Unit,
) {
    @JavascriptInterface
    fun onBlobDownload(dataUrl: String, filename: String?, mimeType: String?) {
        val decoded = decodeDataUrl(dataUrl)
        if (decoded != null) {
            val (resolvedMime, bytes) = decoded
            val effectiveMime = mimeType?.takeIf { it.isNotBlank() } ?: resolvedMime
            val safeName = filename?.trim()?.takeIf { it.isNotBlank() }
                ?.replace(Regex("""[\\/:*?"<>|]"""), "_")
                ?: URLUtil.guessFileName("https://localhost/download", null, effectiveMime)
            val dir = runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?.apply { mkdirs() }
            }.getOrNull()?.takeIf { it.canWrite() }
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
                ?: context.filesDir
            val outFile = File(dir, safeName)
            runCatching { outFile.writeBytes(bytes) }
            webView.post {
                Toast.makeText(context, "Saved $safeName (${bytes.size} B)", Toast.LENGTH_SHORT).show()
            }
        } else if (dataUrl.startsWith("http://") || dataUrl.startsWith("https://")) {
            webView.post {
                onCatch(dataUrl, webView.settings.userAgentString, null, mimeType, getPageUrl())
            }
        }
    }

    @JavascriptInterface
    fun onPostDownload(actionUrl: String, formData: String?) {
        if (actionUrl.startsWith("http://") || actionUrl.startsWith("https://")) {
            webView.post {
                onCatch(actionUrl, webView.settings.userAgentString, null, null, getPageUrl())
            }
        }
    }
}
