package com.aiyu.pull.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.ui.add.AddRequest
import com.aiyu.pull.ui.theme.Dimens
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.aiyu.pull.ui.components.MorphingShape
import com.aiyu.pull.ui.components.heroBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEARCH_ENGINE = "https://duckduckgo.com/?q="

/**
 * Minimal WebView browser that hands downloads to Pull with the page's cookies, user agent and referer.
 * With [refreshId] set it is in "get a new link" mode: the next download it catches replaces that download's
 * expired link and the download resumes where it stopped. SSL errors are never bypassed (default WebViewClient).
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
    // Saveable: after process death the page comes back (not its scroll or form state).
    var current by rememberSaveable { mutableStateOf(startUrl.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(current) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(address)) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(address) {
        if (address != textFieldValue.text) {
            textFieldValue = TextFieldValue(address)
        }
    }

    var progress by remember { mutableIntStateOf(100) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    val target by remember(refreshId) { vm.target(refreshId) }.collectAsStateWithLifecycle(null)
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

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (url != "about:blank") {
                        current = url
                        address = url
                    }
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    canBack = view.canGoBack()
                    canForward = view.canGoForward()
                    if (url != "about:blank") {
                        current = url
                        address = url
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            }
            setDownloadListener { url, ua, disposition, mime, _ -> onCatch(url, ua, disposition, mime, this.url) }
            if (current.isNotBlank()) {
                loadUrl(current)
            }
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }
    BackHandler(canBack) { webView.goBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (onClose != null) IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_close))
                    }
                    TextField(
                        value = textFieldValue,
                        onValueChange = {
                            textFieldValue = it
                            address = it.text
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                        leadingIcon = { Icon(if (textFieldValue.text.startsWith("https://")) Icons.Rounded.Lock else Icons.Rounded.Search, null) },
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
                                current = targetUrl
                                webView.loadUrl(targetUrl)
                            }
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                if (state.isFocused && !isFocused) {
                                    if (textFieldValue.text.isNotEmpty()) {
                                        textFieldValue = textFieldValue.copy(
                                            selection = TextRange(0, textFieldValue.text.length)
                                        )
                                        scope.launch {
                                            delay(50)
                                            textFieldValue = textFieldValue.copy(
                                                selection = TextRange(0, textFieldValue.text.length)
                                            )
                                        }
                                    }
                                }
                                isFocused = state.isFocused
                            },
                    )
                }
                if (progress in 0..99 && current.isNotBlank()) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
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
                    IconButton(onClick = { webView.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                    IconButton(onClick = { webView.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.cd_forward)) }
                    IconButton(onClick = { webView.reload() }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.cd_reload)) }
                    Spacer(Modifier.width(Dimens.sm))
                    IconButton(onClick = {
                        webView.loadUrl("about:blank")
                        current = ""
                        address = ""
                        textFieldValue = TextFieldValue("")
                    }) { Icon(Icons.Rounded.Home, stringResource(R.string.cd_home)) }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (current.isBlank()) {
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
}

/** Address bar text to a URL: anything that looks like a host loads, the rest is a search. */
private fun toUrl(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        !t.contains(' ') && t.contains('.') -> "https://$t"
        else -> SEARCH_ENGINE + java.net.URLEncoder.encode(t, "UTF-8")
    }
}
