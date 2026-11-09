package com.aiyu.pull.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInNewOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiyu.pull.R
import com.aiyu.pull.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldSheet(
    host: String,
    blockedCount: Int,
    adBlockEnabled: Boolean,
    onAdBlockToggle: (Boolean) -> Unit,
    popupBlockEnabled: Boolean,
    onPopupBlockToggle: (Boolean) -> Unit,
    cosmeticEnabled: Boolean,
    onCosmeticToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.lg, vertical = Dimens.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(Dimens.md))
                Column {
                    Text(
                        stringResource(R.string.browser_shield_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        host.ifBlank { stringResource(R.string.browser_shield_subtitle) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Dimens.lg))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (blockedCount > 0) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "$blockedCount",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (blockedCount > 0) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.browser_blocked_stat, blockedCount, blockedCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (blockedCount > 0) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.md))

            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_ad_block)) },
                supportingContent = { Text(stringResource(R.string.browser_ad_block_desc)) },
                leadingContent = { Icon(Icons.Rounded.Block, null) },
                trailingContent = {
                    Switch(checked = adBlockEnabled, onCheckedChange = onAdBlockToggle)
                },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_popup_block)) },
                supportingContent = { Text(stringResource(R.string.browser_popup_block_desc)) },
                leadingContent = { Icon(Icons.Rounded.OpenInNewOff, null) },
                trailingContent = {
                    Switch(checked = popupBlockEnabled, onCheckedChange = onPopupBlockToggle)
                },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_cosmetic)) },
                supportingContent = { Text(stringResource(R.string.browser_cosmetic_desc)) },
                leadingContent = { Icon(Icons.Rounded.Layers, null) },
                trailingContent = {
                    Switch(checked = cosmeticEnabled, onCheckedChange = onCosmeticToggle)
                },
            )

            Spacer(Modifier.height(Dimens.xl))
        }
    }
}
