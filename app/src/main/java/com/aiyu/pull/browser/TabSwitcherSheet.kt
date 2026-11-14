package com.aiyu.pull.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aiyu.pull.R
import com.aiyu.pull.ui.theme.Dimens
import kotlin.math.abs
import kotlin.math.roundToInt

/** Chrome-style tab grid: page previews, tap to switch, swipe a card sideways or tap × to close it. */
@Composable
fun TabSwitcher(
    tabs: List<BrowserTab>,
    activeTabId: Long,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onNewTab: () -> Unit,
    onCloseAllTabs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val grid = rememberLazyGridState()
    LaunchedEffect(Unit) {
        val i = tabs.indexOfFirst { it.id == activeTabId }
        if (i > 0) grid.scrollToItem(i)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                Text(
                    stringResource(R.string.browser_tab_count, tabs.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (tabs.size > 1) {
                    IconButton(onClick = onCloseAllTabs) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.browser_close_all_tabs)) }
                }
                IconButton(onClick = { onNewTab(); onDismiss() }) { Icon(Icons.Rounded.Add, stringResource(R.string.browser_new_tab)) }
            }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(Dimens.md),
                horizontalArrangement = Arrangement.spacedBy(Dimens.md),
                verticalArrangement = Arrangement.spacedBy(Dimens.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    SwipeToClose(onClose = { onCloseTab(tab.id) }, modifier = Modifier.animateItem()) {
                        TabCard(tab, tab.id == activeTabId, onClick = { onSelectTab(tab.id); onDismiss() }, onClose = { onCloseTab(tab.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeToClose(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var dx by remember { mutableFloatStateOf(0f) }
    val limit = with(LocalDensity.current) { 120.dp.toPx() }
    Column(
        modifier
            .offset { IntOffset(dx.roundToInt(), 0) }
            .alpha((1f - abs(dx) / (limit * 2)).coerceIn(0.2f, 1f))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { dx += it },
                onDragStopped = { if (abs(dx) > limit) onClose() else dx = 0f },
            ),
    ) { content() }
}

@Composable
private fun TabCard(tab: BrowserTab, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (active) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = Dimens.md, top = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
            if (tab.showNtp) Spacer(Modifier.size(16.dp)) else SiteIcon(tab.url, tab.title, size = 16.dp)
            Spacer(Modifier.width(Dimens.sm))
            Text(
                if (tab.showNtp) stringResource(R.string.browser_new_tab) else tab.displayTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.browser_close_tab), Modifier.size(18.dp))
            }
        }
        TabPreview(tab, Modifier.fillMaxWidth().aspectRatio(0.78f).padding(start = Dimens.xs, end = Dimens.xs, bottom = Dimens.xs))
    }
}
