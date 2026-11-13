package com.aiyu.pull.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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

private data class LongPressTarget(val url: String, val pageUrl: String?, val userAgent: String?)

/**
 * Tabbed WebView browser with ad blocker, popup blocker, and download capture.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val target by remember(refreshId) { vm.target(refreshId) }.collectAsStateWithLifecycle(null)

    val tabs = remember { mutableStateListOf<BrowserTab>() }
    var activeTabId by rememberSaveable { mutableLongStateOf(1L) }
    var nextTabId by rememberSaveable { mutableLongStateOf(2L) }

    var showShield by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showAddShortcut by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }

    val activeTab: BrowserTab? = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    val isCurrentBookmarked by remember(activeTab?.url) {
        vm.isBookmarked(activeTab?.url.orEmpty())
    }.collectAsStateWithLifecycle(false)

    var address by rememberSaveable { mutableStateOf(startUrl.orEmpty()) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(address)) }
    var isFocused by remember { mutableStateOf(false) }

    val onCatch by rememberUpdatedState { url: String, ua: String?, disposition: String?, mime: String?, page: String? ->
        val headers = buildMap {
            ua?.let { put("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
            page?.let { put("Referer", it) }
        }
        if (refreshId >= 0) {
            vm.refresh(refreshId, url, headers, page)
            onRefreshed()
        } else {
            onDownload(AddRequest(url, URLUtil.guessFileName(url, disposition, mime), page, headers))
        }
    }

    var lastPopupTime by remember { mutableLongStateOf(0L) }

    val onLongPress: (String, String?, String?) -> Unit = { url, pageUrl, ua ->
        longPressTarget = LongPressTarget(url, pageUrl, ua)
    }
    val onRecordHistory: (String, String) -> Unit = { url, title ->
        vm.recordHistory(url, title)
    }

    var createTabRef: ((String, Boolean, Boolean) -> BrowserTab)? = null
    var closeTabRef: ((Long) -> Unit)? = null

    fun navigateTo(input: String) {
        focus.clearFocus()
        val targetUrl = resolveAddressInput(input, settings.browserSearchEngine)
        if (targetUrl.isNotBlank()) {
            address = targetUrl
            textFieldValue = TextFieldValue(targetUrl)
            activeTab?.url = targetUrl
            activeTab?.webView?.loadUrl(targetUrl)
        }
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) {
            val tab = tabs[index]
            tab.destroy()
            tabs.removeAt(index)
            if (tabs.isEmpty()) {
                val newId = nextTabId++
                val fresh = BrowserTab(newId, WebView(context), "", "")
                setupTabWebView(
                    fresh,
                    context,
                    vm.adBlocker,
                    { settings },
                    { lastPopupTime },
                    { lastPopupTime = it },
                    onCatch,
                    onLongPress,
                    onRecordHistory,
                    { closeTabRef?.invoke(it) },
                    { createTabRef?.invoke("", true, true) ?: error("Not initialized") },
                )
                tabs.add(fresh)
                activeTabId = fresh.id
                address = ""
                textFieldValue = TextFieldValue("")
            } else if (activeTabId == id) {
                val newIndex = (index - 1).coerceAtLeast(0)
                val newTab = tabs[newIndex]
                activeTabId = newTab.id
                val tabUrl = if (newTab.url == "about:blank") "" else newTab.url
                address = tabUrl
                textFieldValue = TextFieldValue(tabUrl)
            }
        }
    }
    closeTabRef = ::closeTab

    fun createTab(url: String = "", isPopup: Boolean = false, select: Boolean = true): BrowserTab {
        val id = nextTabId++
        val webView = WebView(context)
        val tab = BrowserTab(id, webView, url, "", isPopupCandidate = isPopup)
        setupTabWebView(
            tab,
            context,
            vm.adBlocker,
            { settings },
            { lastPopupTime },
            { lastPopupTime = it },
            onCatch,
            onLongPress,
            onRecordHistory,
            { closeTabRef?.invoke(it) },
            { createTabRef?.invoke("", true, true) ?: error("Not initialized") },
        )
        tabs.add(tab)
        if (select) {
            activeTabId = tab.id
            val tabUrl = if (url == "about:blank") "" else url
            address = tabUrl
            textFieldValue = TextFieldValue(tabUrl)
        }
        if (url.isNotBlank() && url != "about:blank") {
            webView.loadUrl(url)
        }
        return tab
    }
    createTabRef = ::createTab

    // Initialize first tab if empty
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val initial = startUrl.orEmpty()
            val tab = BrowserTab(1L, WebView(context), initial, "")
            setupTabWebView(
                tab,
                context,
                vm.adBlocker,
                { settings },
                { lastPopupTime },
                { lastPopupTime = it },
                onCatch,
                onLongPress,
                onRecordHistory,
                { closeTabRef?.invoke(it) },
                { createTabRef?.invoke("", true, true) ?: error("Not initialized") },
            )
            tabs.add(tab)
            activeTabId = 1L
            if (initial.isNotBlank()) {
                tab.webView.loadUrl(initial)
            }
        }
    }

    // Sync active tab URL changes into address bar
    LaunchedEffect(activeTab?.url) {
        val u = activeTab?.url.orEmpty()
        val display = if (u == "about:blank") "" else u
        if (display != address && !isFocused) {
            address = display
            textFieldValue = TextFieldValue(display)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tabs.forEach { it.destroy() }
            tabs.clear()
        }
    }

    // Back handling priority
    BackHandler(enabled = longPressTarget != null) { longPressTarget = null }
    BackHandler(enabled = showBookmarks) { showBookmarks = false }
    BackHandler(enabled = showHistory) { showHistory = false }
    BackHandler(enabled = showTabs) { showTabs = false }
    BackHandler(enabled = showShield) { showShield = false }
    BackHandler(enabled = activeTab?.canBack == true) { activeTab?.webView?.goBack() }
    BackHandler(enabled = tabs.size > 1) { activeTab?.let { closeTab(it.id) } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // Same container as the bottom bar, drawn behind the status bar so both bars read as one chrome.
            Surface(color = BottomAppBarDefaults.containerColor) {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onClose != null) {
                        IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_close))
                        }
                    }
                    TextField(
                        value = textFieldValue,
                        onValueChange = {
                            textFieldValue = it
                            address = it.text
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                        leadingIcon = {
                            Icon(
                                if (textFieldValue.text.startsWith("https://")) Icons.Rounded.Lock else Icons.Rounded.Search,
                                null,
                            )
                        },
                        trailingIcon = {
                            if (textFieldValue.text.isNotEmpty()) {
                                IconButton(onClick = {
                                    textFieldValue = TextFieldValue("")
                                    address = ""
                                }) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.action_clear))
                                }
                            }
                        },
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            navigateTo(textFieldValue.text)
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                if (state.isFocused && !isFocused) {
                                    if (textFieldValue.text.isNotEmpty()) {
                                        textFieldValue = textFieldValue.copy(
                                            selection = TextRange(0, textFieldValue.text.length),
                                        )
                                        scope.launch {
                                            delay(50)
                                            textFieldValue = textFieldValue.copy(
                                                selection = TextRange(0, textFieldValue.text.length),
                                            )
                                        }
                                    }
                                }
                                isFocused = state.isFocused
                            },
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.browser_more_options))
                        }
                        val hasPage = activeTab?.url?.isNotBlank() == true && activeTab.url != "about:blank"
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_desktop_site)) },
                                leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) },
                                trailingIcon = if (activeTab?.isDesktopMode == true) {
                                    { Icon(Icons.Rounded.Check, null) }
                                } else null,
                                onClick = {
                                    showMenu = false
                                    activeTab?.let { it.applyDesktopMode(!it.isDesktopMode) }
                                },
                            )
                            if (hasPage) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (isCurrentBookmarked) R.string.browser_bookmark_remove
                                                else R.string.browser_bookmark_add,
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isCurrentBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                            null,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        val tab = activeTab ?: return@DropdownMenuItem
                                        vm.toggleBookmark(tab.url, tab.displayTitle, isCurrentBookmarked)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_add_to_shortcuts)) },
                                    leadingIcon = { Icon(Icons.Rounded.AddLink, null) },
                                    onClick = {
                                        showMenu = false
                                        val tab = activeTab ?: return@DropdownMenuItem
                                        vm.addShortcut(tab.url, tab.displayTitle)
                                        Toast.makeText(context, R.string.browser_shortcut_added, Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_bookmarks)) },
                                leadingIcon = { Icon(Icons.Rounded.Bookmarks, null) },
                                onClick = {
                                    showMenu = false
                                    showBookmarks = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_history)) },
                                leadingIcon = { Icon(Icons.Rounded.History, null) },
                                onClick = {
                                    showMenu = false
                                    showHistory = true
                                },
                            )
                        }
                    }
                }

                // Address bar suggestions from bookmarks & history while focused
                val query = textFieldValue.text.trim()
                if (isFocused && query.isNotEmpty()) {
                    val suggestions = remember(query, bookmarks, history) {
                        val bMatches = bookmarks
                            .filter { it.url.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }
                            .take(3)
                            .map { Triple(it.title, it.url, true) }
                        val seenUrls = bMatches.mapTo(HashSet()) { it.second }
                        val hMatches = history
                            .asSequence()
                            .filter { it.url !in seenUrls && (it.url.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true)) }
                            .distinctBy { it.url }
                            .take(5 - bMatches.size)
                            .map { Triple(it.title, it.url, false) }
                            .toList()
                        bMatches + hMatches
                    }
                    if (suggestions.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                suggestions.forEach { (title, url, isBookmark) ->
                                    ListItem(
                                        headlineContent = {
                                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                        supportingContent = {
                                            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                        leadingContent = {
                                            Icon(
                                                if (isBookmark) Icons.Rounded.Bookmark else Icons.Rounded.History,
                                                contentDescription = null,
                                            )
                                        },
                                        modifier = Modifier.clickable { navigateTo(url) },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                val currentProgress = activeTab?.progress ?: 100
                if (currentProgress in 0..99 && activeTab?.url?.isNotBlank() == true && activeTab.url != "about:blank") {
                    LinearProgressIndicator(
                        progress = { currentProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AnimatedVisibility(refreshId >= 0) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.browser_refresh_hint, target?.fileName.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = Dimens.lg, vertical = Dimens.md),
                        )
                    }
                }
            }
            }
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(
                        onClick = { activeTab?.webView?.goBack() },
                        enabled = activeTab?.canBack == true,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                    }
                    IconButton(
                        onClick = { activeTab?.webView?.goForward() },
                        enabled = activeTab?.canForward == true,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.cd_forward))
                    }
                    IconButton(onClick = { activeTab?.webView?.reload() }) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.cd_reload))
                    }
                    IconButton(onClick = {
                        activeTab?.webView?.loadUrl("about:blank")
                        activeTab?.url = ""
                        address = ""
                        textFieldValue = TextFieldValue("")
                    }) {
                        Icon(Icons.Rounded.Home, stringResource(R.string.cd_home))
                    }

                    Spacer(Modifier.weight(1f))

                    // Shield icon button with blocked counter badge
                    val blocked = activeTab?.blockedCount ?: 0
                    IconButton(onClick = { showShield = true }) {
                        BadgedBox(
                            badge = {
                                if (blocked > 0) {
                                    Badge {
                                        Text(if (blocked > 99) "99+" else "$blocked")
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.Shield, stringResource(R.string.cd_shield))
                        }
                    }

                    // Tab switcher button
                    IconButton(onClick = { showTabs = true }) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(6.dp),
                                ),
                        ) {
                            Text(
                                text = "${tabs.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            activeTab?.let { tab ->
                key(tab.id) {
                    AndroidView(
                        factory = {
                            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                            tab.webView.isHapticFeedbackEnabled = false
                            tab.webView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (activeTab == null || activeTab.url.isBlank() || activeTab.url == "about:blank") {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(Dimens.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MorphingShape(brush = heroBrush(), modifier = Modifier.size(96.dp))
                    Spacer(Modifier.height(Dimens.xl))
                    Text(
                        stringResource(R.string.browser_app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Dimens.sm))
                    Text(
                        stringResource(R.string.browser_address_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.xl))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.sm, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        shortcuts.forEach { shortcut ->
                            Surface(
                                onClick = { navigateTo(shortcut.url) },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = Dimens.md, end = Dimens.xs, top = Dimens.xs, bottom = Dimens.xs),
                                ) {
                                    Text(
                                        text = shortcut.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = { vm.deleteShortcut(shortcut.id) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.action_close),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Surface(
                            onClick = { showAddShortcut = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm),
                            ) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(Dimens.xs))
                                Text(
                                    text = stringResource(R.string.browser_add_shortcut),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showShield) {
        val host = activeTab?.url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }.orEmpty()
        ShieldSheet(
            host = host,
            blockedCount = activeTab?.blockedCount ?: 0,
            adBlockEnabled = settings.browserAdBlock,
            onAdBlockToggle = { vm.setAdBlock(it) },
            popupBlockEnabled = settings.browserPopupBlock,
            onPopupBlockToggle = { vm.setPopupBlock(it) },
            cosmeticEnabled = settings.browserCosmetic,
            onCosmeticToggle = { vm.setCosmetic(it) },
            onDismiss = { showShield = false },
        )
    }

    if (showTabs) {
        TabSwitcherSheet(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = { id ->
                activeTabId = id
                val tab = tabs.find { it.id == id }
                val u = if (tab?.url == "about:blank") "" else tab?.url.orEmpty()
                address = u
                textFieldValue = TextFieldValue(u)
            },
            onCloseTab = { id -> closeTab(id) },
            onNewTab = { createTab("", select = true) },
            onCloseAllTabs = {
                tabs.forEach { it.destroy() }
                tabs.clear()
                val fresh = createTab("", select = true)
                activeTabId = fresh.id
                address = ""
                textFieldValue = TextFieldValue("")
            },
            onDismiss = { showTabs = false },
        )
    }

    longPressTarget?.let { lp ->
        LongPressTargetSheet(
            url = lp.url,
            onDownloadWithPull = {
                onCatch(lp.url, lp.userAgent, null, null, lp.pageUrl)
            },
            onOpenInNewTab = {
                createTab(lp.url, select = true)
            },
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

    if (showAddShortcut) {
        AddShortcutDialog(
            onAdd = { url, title -> vm.addShortcut(url, title) },
            onDismiss = { showAddShortcut = false },
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
    onLongPress: (url: String, pageUrl: String?, userAgent: String?) -> Unit,
    onRecordHistory: (url: String, title: String) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCreateNewTab: () -> BrowserTab,
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
            if (currentSettings.browserInterceptByExtension && shouldInterceptByExtension(dest)) {
                onCatch(dest, view.settings.userAgentString, null, null, view.url)
                if (tab.isPopupCandidate) {
                    onCloseTab(tab.id)
                }
                return true
            }

            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val currentSettings = getSettings()
            if (!currentSettings.browserAdBlock) return null
            val reqUrl = request.url.toString()
            // Runs on a WebView IO thread: view.url would throw there, tab.url is safe to read.
            if (adBlocker.isBlocked(reqUrl, tab.url)) {
                view.post { tab.blockedCount++ }
                return adBlocker.createBlockedResponse(reqUrl)
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val currentSettings = getSettings()
            if (url != "about:blank") {
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
                tab.url = url
            }

            // Inject popup shield script to neutralize JS popup loops
            if (currentSettings.browserPopupBlock) {
                view.evaluateJavascript(AdBlocker.POPUP_SHIELD_SCRIPT, null)
            }
            // Inject cosmetic CSS
            if (currentSettings.browserAdBlock && currentSettings.browserCosmetic) {
                view.evaluateJavascript(AdBlocker.INJECT_COSMETIC_SCRIPT, null)
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val currentSettings = getSettings()
            if (currentSettings.browserAdBlock && currentSettings.browserCosmetic) {
                view.evaluateJavascript(AdBlocker.INJECT_COSMETIC_SCRIPT, null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!tab.isIncognito && url.isNotBlank() && url != "about:blank") {
                onRecordHistory(url, view.title.orEmpty().ifBlank { url })
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
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            if (resultMsg == null) return false
            val currentSettings = getSettings()

            if (currentSettings.browserPopupBlock) {
                // Block popups triggered without user gesture
                if (!isUserGesture) {
                    tab.blockedCount++
                    return false
                }

                // Check hit test result if available
                val hit = view?.hitTestResult
                val extra = hit?.extra
                if (!extra.isNullOrBlank() && adBlocker.isBlocked(extra, view?.url)) {
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
            val newTab = onCreateNewTab()
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
        onCatch(url, ua, disposition, mime, tab.url)
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
