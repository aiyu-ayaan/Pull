package com.aiyu.pull.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiyu.pull.R
import com.aiyu.pull.ui.theme.Dimens

/** Window width from which the browser uses Chrome's tablet layout: tab strip and toolbar on top. */
val BrowserWideWidth = 600.dp

/**
 * Chrome tablet chrome: a strip of tabs above a toolbar with back, forward, reload, home, the address bar, the tab
 * grid and the menu. Replaces the phone's bottom toolbar, which would stretch across the whole screen.
 */
@Composable
fun BrowserWideBar(
    tabs: List<BrowserTab>,
    tab: BrowserTab,
    onSelect: (BrowserTab) -> Unit,
    onCloseTab: (Long) -> Unit,
    onNewTab: () -> Unit,
    onCloseBrowser: (() -> Unit)?,
    onBack: () -> Unit,
    canBack: Boolean,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onOmnibox: () -> Unit,
    onSiteInfo: () -> Unit,
    onTabs: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = Dimens.xs, end = Dimens.sm, top = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onCloseBrowser != null) {
                    IconButton(onClick = onCloseBrowser) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
                }
                Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { t -> TabChip(t, t.id == tab.id, tabs.size > 1, { onSelect(t) }, { onCloseTab(t.id) }) }
                }
                IconButton(onClick = onNewTab) { Icon(Icons.Rounded.Add, stringResource(R.string.browser_new_tab)) }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f)) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Dimens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, enabled = canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                    IconButton(onClick = onForward, enabled = tab.canForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.cd_forward)) }
                    IconButton(onClick = onReload) {
                        Icon(if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, stringResource(R.string.cd_reload))
                    }
                    IconButton(onClick = onHome) { Icon(Icons.Rounded.Home, stringResource(R.string.cd_home)) }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f).height(40.dp).clip(CircleShape).clickable(onClick = onOmnibox),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Dimens.lg)) {
                            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSiteInfo), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (tab.hasPage && tab.url.startsWith("https://")) Icons.Rounded.Tune else Icons.Rounded.Language,
                                    stringResource(R.string.cd_shield),
                                    Modifier.size(20.dp),
                                    tint = if (tab.blockedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (tab.showNtp || !tab.hasPage) stringResource(R.string.browser_address_hint) else displayUrl(tab.url),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (tab.showNtp || !tab.hasPage) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TabCountButton(tabs.size, onTabs)
                    menu()
                }
            }
            val progress by animateFloatAsState(tab.progress / 100f, label = "load")
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = tab.loading && tab.progress < 100,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
                }
            }
        }
    }
}

@Composable
private fun TabChip(tab: BrowserTab, active: Boolean, canClose: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(end = Dimens.xs).widthIn(min = 120.dp, max = 220.dp).height(38.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = Dimens.md), verticalAlignment = Alignment.CenterVertically) {
            if (tab.showNtp || !tab.hasPage) {
                Icon(Icons.Rounded.Language, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SiteIcon(tab.url, tab.title, size = 16.dp)
            }
            Spacer(Modifier.width(Dimens.sm))
            Text(
                if (tab.showNtp || !tab.hasPage) stringResource(R.string.browser_new_tab) else tab.displayTitle,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canClose) {
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.browser_close_tab), Modifier.size(16.dp))
                }
            } else {
                Spacer(Modifier.width(Dimens.md))
            }
        }
    }
}
