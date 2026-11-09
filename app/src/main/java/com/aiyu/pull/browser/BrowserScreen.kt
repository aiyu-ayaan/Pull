package com.aiyu.pull.browser

import android.annotation.SuppressLint
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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import java.net.URLEncoder

private const val SEARCH_ENGINE = "https://duckduckgo.com/?q="

/**
 * Tabbed WebView browser with ad blocker, popup blocker, and download capture.
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
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val target by remember(refreshId) { vm.target(refreshId) }.collectAsStateWithLifecycle(null)

    val tabs = remember { mutableStateListOf<BrowserTab>() }
    var activeTabId by rememberSaveable { mutableLongStateOf(1L) }
    var nextTabId by rememberSaveable { mutableLongStateOf(2L) }

    var showShield by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }

    val activeTab: BrowserTab? = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

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

    var createTabRef: ((String, Boolean, Boolean) -> BrowserTab)? = null
    var closeTabRef: ((Long) -> Unit)? = null

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
    BackHandler(enabled = showTabs) { showTabs = false }
    BackHandler(enabled = showShield) { showShield = false }
    BackHandler(enabled = activeTab?.canBack == true) { activeTab?.webView?.goBack() }
    BackHandler(enabled = tabs.size > 1) { activeTab?.let { closeTab(it.id) } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
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
                            focus.clearFocus()
                            val targetUrl = toUrl(textFieldValue.text)
                            if (targetUrl.isNotBlank()) {
                                address = targetUrl
                                activeTab?.url = targetUrl
                                activeTab?.webView?.loadUrl(targetUrl)
                            }
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
}

/** Configures settings and clients for a [BrowserTab]'s WebView. */
@SuppressLint("SetJavaScriptEnabled")
private fun setupTabWebView(
    tab: BrowserTab,
    context: android.content.Context,
    adBlocker: AdBlocker,
    getSettings: () -> com.aiyu.pull.data.Settings,
    getLastPopupTime: () -> Long,
    setLastPopupTime: (Long) -> Unit,
    onCatch: (url: String, ua: String?, disposition: String?, mime: String?, page: String?) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCreateNewTab: () -> BrowserTab,
) {
    val wv = tab.webView
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
            if (currentSettings.browserAdBlock && adBlocker.isBlocked(dest)) {
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

            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val currentSettings = getSettings()
            if (!currentSettings.browserAdBlock) return null
            val reqUrl = request.url.toString()
            if (adBlocker.isBlocked(reqUrl)) {
                view.post { tab.blockedCount++ }
                return adBlocker.createBlockedResponse(reqUrl)
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val currentSettings = getSettings()
            if (url != "about:blank") {
                if (currentSettings.browserAdBlock && adBlocker.isBlocked(url)) {
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
                if (!extra.isNullOrBlank() && adBlocker.isBlocked(extra)) {
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
}

/** Address bar text to a URL: anything that looks like a host loads, the rest is a search. */
private fun toUrl(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    return when {
        t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
        !t.contains(' ') && t.contains('.') -> "https://$t"
        else -> SEARCH_ENGINE + URLEncoder.encode(t, "UTF-8")
    }
}
