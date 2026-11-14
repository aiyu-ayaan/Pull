package com.aiyu.pull.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aiyu.pull.R
import com.aiyu.pull.data.BookmarkEntity
import com.aiyu.pull.data.HistoryEntity
import com.aiyu.pull.data.ShortcutEntity
import com.aiyu.pull.ui.components.MorphingShape
import com.aiyu.pull.ui.components.heroBrush
import com.aiyu.pull.ui.theme.Dimens
import kotlin.math.abs
import kotlin.math.roundToInt

/** Host and path without scheme or "www.", like Chrome's collapsed omnibox. */
internal fun displayUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/** The square with the tab count. */
@Composable
fun TabCountButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
        ) {
            Text(
                text = if (count > 99) ":D" else "$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Chrome-style bottom toolbar: home, collapsed omnibox, new tab, tab count, menu. A horizontal swipe on the omnibox
 * switches to the neighbouring tab. The page load progress runs along its top edge.
 */
@Composable
fun BrowserToolbar(
    tab: BrowserTab,
    tabCount: Int,
    onHome: () -> Unit,
    onOmnibox: () -> Unit,
    onSiteInfo: () -> Unit,
    onNewTab: () -> Unit,
    onTabs: () -> Unit,
    onSwipeTab: (direction: Int) -> Unit,
    menu: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            val progress by animateFloatAsState(tab.progress / 100f, label = "load")
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (tab.loading && tab.progress < 100) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), drawStopIndicator = {})
                }
            }
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onHome) { Icon(Icons.Rounded.Home, stringResource(R.string.cd_home)) }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .offset { IntOffset((drag / 3).roundToInt(), 0) }
                        .pointerInput(tabCount) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (abs(drag) > threshold) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        onSwipeTab(if (drag < 0) 1 else -1)
                                    }
                                    drag = 0f
                                },
                                onDragCancel = { drag = 0f },
                            ) { change, amount -> change.consume(); drag += amount }
                        }
                        .clickable(onClick = onOmnibox),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Dimens.md)) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSiteInfo),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (tab.url.startsWith("https://")) Icons.Rounded.Tune else Icons.Rounded.Language,
                                stringResource(R.string.cd_shield),
                                Modifier.size(20.dp),
                                tint = if (tab.blockedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            displayUrl(tab.url),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onNewTab) { Icon(Icons.Rounded.Add, stringResource(R.string.browser_new_tab)) }
                TabCountButton(tabCount, onTabs)
                menu()
            }
        }
    }
}

private data class Suggestion(val title: String, val url: String, val kind: Int) // 0 history, 1 bookmark, 2 search

/**
 * Focused omnibox: takes the whole screen like Chrome's, with the field on top and suggestions below. Empty input
 * lists recent history; typing filters bookmarks and history. The arrow on a row copies it into the field.
 */
@Composable
fun OmniboxEditor(
    initial: String,
    history: List<HistoryEntity>,
    bookmarks: List<BookmarkEntity>,
    onGo: (String) -> Unit,
    onAddShortcut: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val query = value.text.trim()
    val suggestions = remember(query, history, bookmarks) {
        if (query.isEmpty()) {
            history.distinctBy { it.url }.take(15).map { Suggestion(it.title, it.url, 0) }
        } else {
            val b = bookmarks.filter { it.url.contains(query, true) || it.title.contains(query, true) }.take(3).map { Suggestion(it.title, it.url, 1) }
            val seen = b.mapTo(HashSet()) { it.url }
            val h = history.asSequence()
                .filter { it.url !in seen && (it.url.contains(query, true) || it.title.contains(query, true)) }
                .distinctBy { it.url }.take(8).map { Suggestion(it.title, it.url, 0) }.toList()
            listOf(Suggestion(query, query, 2)) + b + h
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.xs, vertical = Dimens.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.weight(1f).height(44.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Dimens.lg)) {
                        Box(Modifier.weight(1f)) {
                            if (value.text.isEmpty()) {
                                Text(
                                    stringResource(R.string.browser_address_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BasicTextField(
                                value = value,
                                onValueChange = { value = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                                keyboardActions = KeyboardActions(onGo = { if (query.isNotEmpty()) onGo(query) }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        if (value.text.isNotEmpty()) {
                            IconButton(onClick = { value = TextFieldValue("") }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.action_clear), Modifier.size(20.dp))
                            }
                        } else {
                            Spacer(Modifier.width(Dimens.lg))
                        }
                    }
                }
                Spacer(Modifier.width(Dimens.sm))
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(suggestions, key = { "${it.kind}:${it.url}" }) { s ->
                    SuggestionRow(
                        s,
                        onClick = { onGo(s.url) },
                        onFill = { value = TextFieldValue(s.url, TextRange(s.url.length)) },
                        onAddShortcut = if (s.kind != 2) ({ onAddShortcut(s.url, s.title) }) else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionRow(s: Suggestion, onClick: () -> Unit, onFill: () -> Unit, onAddShortcut: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { if (onAddShortcut != null) menu = true })
                .padding(start = Dimens.lg, end = Dimens.xs, top = Dimens.sm, bottom = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                when (s.kind) {
                    2 -> Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    1 -> Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> SiteIcon(s.url, s.title, size = 24.dp)
                }
            }
            Spacer(Modifier.width(Dimens.lg))
            Column(Modifier.weight(1f)) {
                Text(s.title.ifBlank { s.url }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.kind != 2) {
                    Text(
                        displayUrl(s.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_add_to_shortcuts)) },
                leadingIcon = { Icon(Icons.Rounded.AddLink, null) },
                onClick = { menu = false; onAddShortcut?.invoke() },
            )
        }
    }
}

/** Chrome's ⋮ menu: an icon row (forward, bookmark, reload) above the list. */
@Composable
fun BrowserMenu(
    tab: BrowserTab?,
    bookmarked: Boolean,
    onForward: () -> Unit,
    onToggleBookmark: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onHistory: () -> Unit,
    onBookmarks: () -> Unit,
    onAddShortcut: () -> Unit,
    onShield: () -> Unit,
    onDesktop: () -> Unit,
    onCloseBrowser: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    val hasPage = tab != null && !tab.showNtp && tab.hasPage
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.browser_more_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasPage) {
                Row(Modifier.padding(horizontal = Dimens.xs)) {
                    IconButton(onClick = { open = false; onForward() }, enabled = tab?.canForward == true) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.cd_forward))
                    }
                    IconButton(onClick = { open = false; onToggleBookmark() }) {
                        Icon(
                            if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            stringResource(if (bookmarked) R.string.browser_bookmark_remove else R.string.browser_bookmark_add),
                            tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { open = false; onShield() }) {
                        Icon(Icons.Rounded.Shield, stringResource(R.string.cd_shield))
                    }
                    IconButton(onClick = { open = false; onReload() }) {
                        Icon(if (tab?.loading == true) Icons.Rounded.Close else Icons.Rounded.Refresh, stringResource(R.string.cd_reload))
                    }
                }
                HorizontalDivider()
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_new_tab)) }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { open = false; onNewTab() })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_history)) }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = { open = false; onHistory() })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_bookmarks)) }, leadingIcon = { Icon(Icons.Rounded.Bookmarks, null) }, onClick = { open = false; onBookmarks() })
            DropdownMenuItem(
                text = { Text(stringResource(if (hasPage) R.string.browser_add_to_shortcuts else R.string.browser_add_shortcut)) },
                leadingIcon = { Icon(Icons.Rounded.AddLink, null) },
                onClick = { open = false; onAddShortcut() },
            )
            if (!hasPage) {
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_shield_title)) }, leadingIcon = { Icon(Icons.Rounded.Shield, null) }, onClick = { open = false; onShield() })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_desktop_site)) },
                leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) },
                trailingIcon = if (tab?.isDesktopMode == true) ({ Icon(Icons.Rounded.Check, null) }) else null,
                onClick = { open = false; onDesktop() },
            )
            if (onCloseBrowser != null) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.action_close)) }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { open = false; onCloseBrowser() })
            }
        }
    }
}

/**
 * New tab page: logo, a big search box that opens the omnibox, shortcut tiles in a rounded box (long-press to
 * remove), and a "continue with this tab" card with the page preview.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewTabPage(
    shortcuts: List<ShortcutEntity>,
    continueTab: BrowserTab?,
    tabCount: Int,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onDeleteShortcut: (Long) -> Unit,
    onAddShortcut: () -> Unit,
    onContinue: (BrowserTab) -> Unit,
    onTabs: () -> Unit,
    onClose: (() -> Unit)?,
    menu: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
                if (onClose != null) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
                }
                Spacer(Modifier.weight(1f))
                TabCountButton(tabCount, onTabs)
                menu()
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(Dimens.xl))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MorphingShape(brush = heroBrush(), modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(Dimens.md))
                    Text("Pull", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(Dimens.xl))
                Surface(
                    onClick = onSearch,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Dimens.lg)) {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Dimens.md))
                        Text(
                            stringResource(R.string.browser_address_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.lg))
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = Dimens.md, horizontal = Dimens.xs)) {
                        shortcuts.forEach { s ->
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                ShortcutTile(
                                    label = s.title,
                                    icon = { SiteIcon(s.url, s.title, size = 32.dp) },
                                    onClick = { onOpen(s.url) },
                                    onLongClick = { menuOpen = true },
                                )
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.browser_open_new_tab)) },
                                        onClick = { menuOpen = false; onOpenInNewTab(s.url) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.browser_shortcut_remove)) },
                                        onClick = { menuOpen = false; onDeleteShortcut(s.id) },
                                    )
                                }
                            }
                        }
                        ShortcutTile(
                            label = stringResource(R.string.browser_add_shortcut),
                            icon = { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = onAddShortcut,
                            onLongClick = null,
                        )
                    }
                }
                if (continueTab != null) {
                    Spacer(Modifier.height(Dimens.lg))
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Dimens.lg)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.browser_continue_tab),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.browser_see_more),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clip(CircleShape).clickable(onClick = onTabs).padding(Dimens.xs),
                                )
                            }
                            Spacer(Modifier.height(Dimens.md))
                            Row(
                                Modifier.clip(RoundedCornerShape(16.dp)).clickable { onContinue(continueTab) },
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                TabPreview(continueTab, Modifier.width(112.dp).aspectRatio(0.8f))
                                Spacer(Modifier.width(Dimens.md))
                                Column(Modifier.weight(1f)) {
                                    Text(continueTab.displayTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        android.net.Uri.parse(continueTab.url).host.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.xxl))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(label: String, icon: @Composable () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Dimens.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(Dimens.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dimens.xs),
        )
    }
}

/** The page screenshot, or the site icon on a plain card before one exists. */
@Composable
fun TabPreview(tab: BrowserTab, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        val thumb = tab.thumbnail
        when {
            tab.showNtp -> Box(contentAlignment = Alignment.Center) {
                MorphingShape(brush = heroBrush(), modifier = Modifier.size(40.dp))
            }
            thumb != null -> Image(thumb, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            else -> Box(contentAlignment = Alignment.Center) { SiteIcon(tab.url, tab.title, size = 40.dp) }
        }
    }
}
