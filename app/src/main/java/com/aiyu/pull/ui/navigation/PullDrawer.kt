package com.aiyu.pull.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.aiyu.pull.R
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.theme.Dimens

/** Everything Pull can do, in one place (Findex-style drawer). Lives under the push-aside card. */
@Composable
fun PullDrawerSheet(
    settingsOpen: Boolean,
    onAddLink: () -> Unit,
    onPaste: () -> Unit,
    onOpenTorrent: () -> Unit,
    onBrowser: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.systemBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(Dimens.xl)) {
            ShapeIcon(
                icon = Icons.Rounded.Download,
                shape = MaterialShapes.Cookie9Sided,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                size = Dimens.xxxl + Dimens.xs,
                iconSize = Dimens.xl + Dimens.xs,
            )
            Spacer(Modifier.width(Dimens.lg))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(stringResource(R.string.drawer_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Only what the tab bar does not have: the tabs live in the bar, never twice.
        Section(R.string.drawer_section_add)
        Item(Icons.Rounded.AddLink, stringResource(R.string.fab_add_url), onClick = onAddLink)
        Item(Icons.Rounded.ContentPaste, stringResource(R.string.fab_paste), onClick = onPaste)
        Item(Icons.Rounded.FileOpen, stringResource(R.string.drawer_open_torrent), onClick = onOpenTorrent)
        Item(
            Icons.Rounded.Public, stringResource(R.string.nav_browser), onClick = onBrowser,
            trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.cd_opens_separately)) },
        )

        HorizontalDivider(Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.sm))
        Item(Icons.Rounded.Settings, stringResource(R.string.nav_settings), selected = settingsOpen, onClick = onSettings)
        Spacer(Modifier.height(Dimens.lg))
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.sm),
    )
}

@Composable
private fun Item(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    badge: Int = 0,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        badge = when {
            badge > 0 -> ({ Text("$badge", style = MaterialTheme.typography.labelLarge) })
            trailing != null -> trailing
            else -> null
        },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
