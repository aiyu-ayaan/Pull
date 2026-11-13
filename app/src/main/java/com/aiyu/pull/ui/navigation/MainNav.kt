package com.aiyu.pull.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiyu.pull.R
import com.aiyu.pull.data.Status
import com.aiyu.pull.ui.theme.rememberReducedMotion

/** Bottom tabs. Active is home: the live view of everything not finished. Downloaded is the finished library. */
enum class Tab(val label: Int, val title: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    ACTIVE(R.string.tab_active, R.string.title_active, Icons.Outlined.Downloading, Icons.Rounded.Downloading),
    DONE(R.string.tab_done, R.string.title_done, Icons.Outlined.DownloadDone, Icons.Rounded.DownloadDone);

    /** Failed downloads stay in Active: they still need an action (retry, new link). */
    fun matches(status: Status) = (status == Status.COMPLETED) == (this == DONE)
}

/** Space the floating pill covers at the bottom: [content] for scroll padding, [fab] follows the pill as it hides. */
data class BottomBarInsets(val content: Dp = 0.dp, val fab: Dp = 0.dp)

val LocalBottomBarInsets = compositionLocalOf { BottomBarInsets() }

/**
 * Phones: a floating pill (as in Rewire). The selected tab grows into icon + label; the others stay icon-only.
 * [badges] puts a count on a tab (running downloads, failures).
 */
@Composable
fun FloatingNavBar(tab: Tab, badges: Map<Tab, Int>, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Row(Modifier.padding(6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Tab.entries.forEach { t -> PillItem(t, t == tab, badges[t] ?: 0) { onSelect(t) } }
            }
        }
    }
}

@Composable
private fun PillItem(t: Tab, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val bg by animateColorAsState(if (selected) c.primaryContainer else Color.Transparent, effects, label = "pillBg")
    val fg by animateColorAsState(if (selected) c.onPrimaryContainer else c.onSurfaceVariant, effects, label = "pillFg")
    val label = stringResource(t.label)
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .selectable(selected, onClick = onClick, role = Role.Tab)
            .semantics(mergeDescendants = true) { contentDescription = if (badge > 0) "$label, $badge" else label }
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(t, selected, badge, tint = fg)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
        }
    }
}

/** Tab icon that pops in with a small bouncy twist when its tab gets selected (not on first show, not with reduced motion). */
@Composable
private fun NavIcon(t: Tab, selected: Boolean, badge: Int, tint: Color = LocalContentColor.current) {
    val reduced = rememberReducedMotion()
    val pop = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (selected && shown && !reduced) {
            pop.snapTo(0.6f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow))
        } else {
            pop.snapTo(1f) // a fast tab switch cancels the pop mid-way; never leave the icon shrunk
        }
        shown = true
    }
    val icon = @Composable {
        Icon(
            if (selected) t.selectedIcon else t.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = (1f - pop.value) * -40f
            },
        )
    }
    if (badge > 0) {
        BadgedBox(badge = {
            Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$badge") }
        }) { icon() }
    } else icon()
}

/** Tablets / landscape: the same pill stood upright on the left edge. */
@Composable
fun FloatingNavRail(tab: Tab, badges: Map<Tab, Int>, onSelect: (Tab) -> Unit) {
    Box(
        Modifier.fillMaxHeight().systemBarsPadding().displayCutoutPadding().padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Column(
                Modifier.width(88.dp).padding(vertical = 12.dp).verticalScroll(rememberScrollState()).selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = t == tab,
                        onClick = { onSelect(t) },
                        icon = { NavIcon(t, t == tab, badges[t] ?: 0) },
                        label = { Text(stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationRailItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
    }
}
