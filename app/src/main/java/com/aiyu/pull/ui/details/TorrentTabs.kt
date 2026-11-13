package com.aiyu.pull.ui.details

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiyu.pull.R
import com.aiyu.pull.download.FilePriority
import com.aiyu.pull.download.PeerLive
import com.aiyu.pull.download.TorrentCommand
import com.aiyu.pull.download.TorrentLive
import com.aiyu.pull.torrent.TorrentCreator
import com.aiyu.pull.torrent.extractPeerIp
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.PieceMap
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.SpeedGraph
import com.aiyu.pull.ui.components.shareTorrentFile
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.downloads.DownloadView
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.formatSpeed
import com.aiyu.pull.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Tabs like other torrent clients (Flud, LibreTorrent, µTorrent): one subject per tab. */
enum class TorrentTab(val label: Int) {
    STATUS(R.string.tab_status), FILES(R.string.tab_files), TRACKERS(R.string.tab_trackers),
    PEERS(R.string.tab_peers), PIECES(R.string.tab_pieces), INFO(R.string.tab_info),
}

@Composable
fun TorrentTabRow(tab: TorrentTab, onTab: (TorrentTab) -> Unit) {
    // Opaque so the list scrolls under the pinned tabs.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.surface) {
            TorrentTab.entries.forEach { t ->
                Tab(selected = t == tab, onClick = { onTab(t) }, text = { Text(stringResource(t.label)) })
            }
        }
    }
}

/** The selected tab's items. Live numbers come from [view]; without them (paused) each tab says so. */
fun LazyListScope.torrentTab(
    tab: TorrentTab,
    view: DownloadView,
    onSelectFiles: () -> Unit,
    onCommand: (TorrentCommand) -> Unit,
    onAddTrackers: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val t = view.live?.torrent
    when (tab) {
        TorrentTab.STATUS -> {
            item(key = "stats") { Stats(view) }
            if (t != null) {
                if (t.pieces.isNotEmpty()) item(key = "bar") {
                    Card(stringResource(R.string.details_pieces)) { PieceMap(t.pieces, false) }
                }
                item(key = "transfer") { TransferCard(view, t, onCommand) }
            } else {
                item(key = "transfer-idle") { TorrentOptionsCard(view, onCommand) }
            }
            view.live?.let { live ->
                item(key = "graph") {
                    Card(stringResource(R.string.details_speed_graph)) {
                        SpeedGraph(live.history)
                        Text(
                            stringResource(R.string.details_peak, formatSpeed(live.peakBytesPerSecond)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        TorrentTab.FILES -> {
            item(key = "files-head") { FilesCard(view, onSelectFiles) }
            val files = t?.files.orEmpty()
            items(files.size, key = { "file$it" }) { i ->
                FileRow(files[i]) { p -> onCommand(TorrentCommand.SetFilePriority(i, p)) }
            }
        }
        TorrentTab.TRACKERS -> {
            val custom = view.entity.extraTrackers.lines().map(String::trim).filter(String::isNotEmpty).toSet()
            val list = t?.trackers.orEmpty()
            val allUrls = if (list.isNotEmpty()) list.map { it.url } else custom.toList()
            item(key = "tr-actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    FilledTonalButton(onClick = onAddTrackers, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                        Text(stringResource(R.string.trackers_add))
                    }
                    OutlinedButton(onClick = { onCommand(TorrentCommand.Reannounce) }, enabled = view.running, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Campaign, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                        Text(stringResource(R.string.trackers_reannounce))
                    }
                    if (allUrls.isNotEmpty()) {
                        IconButton(onClick = { onCopy("Trackers", allUrls.joinToString("\n")) }) {
                            Icon(Icons.Rounded.ContentCopy, stringResource(R.string.trackers_copy_all))
                        }
                    }
                }
            }
            if (list.isNotEmpty()) {
                items(list, key = { "tr" + it.url }) { tr ->
                    val isCustom = tr.url in custom
                    ListRow(
                        title = tr.url,
                        subtitle = buildString {
                            append(stringResource(R.string.tracker_tier, tr.tier))
                            append(" · ")
                            append(
                                stringResource(
                                    when {
                                        tr.updating -> R.string.tracker_updating
                                        tr.working -> R.string.tracker_working
                                        tr.fails > 0 -> R.string.tracker_error
                                        else -> R.string.tracker_not_contacted
                                    },
                                ),
                            )
                            if (isCustom) append(" · ").append(stringResource(R.string.tracker_custom))
                            if (tr.message.isNotBlank()) append(" · ").append(tr.message)
                        },
                        onLongClick = { onCopy(tr.url, tr.url) },
                        onRemove = if (isCustom) ({ onCommand(TorrentCommand.RemoveTracker(tr.url)) }) else null,
                    )
                }
            } else if (custom.isNotEmpty()) {
                items(custom.toList(), key = { "tr-custom-$it" }) { url ->
                    ListRow(
                        title = url,
                        subtitle = stringResource(R.string.tracker_custom),
                        onLongClick = { onCopy(url, url) },
                        onRemove = { onCommand(TorrentCommand.RemoveTracker(url)) },
                    )
                }
            } else {
                item(key = "tr-empty") { Empty(if (t == null) R.string.torrent_not_running else R.string.trackers_none) }
            }
        }
        TorrentTab.PEERS -> {
            val list = t?.peerList.orEmpty()
            if (list.isEmpty()) item(key = "peer-empty") { Empty(if (t == null) R.string.torrent_not_running else R.string.peers_none) }
            items(list, key = { "peer" + it.ip }) { p ->
                PeerRow(
                    peer = p,
                    onCopyIp = { onCopy("IP", extractPeerIp(p.ip)) },
                    onBanPeer = { onCommand(TorrentCommand.BanPeer(p.ip)) },
                )
            }
        }
        TorrentTab.PIECES -> {
            if (t == null || t.pieceCount == 0) item(key = "pc-empty") { Empty(R.string.torrent_not_running) }
            else item(key = "pieces") {
                Card(stringResource(R.string.details_pieces)) {
                    Text(
                        stringResource(R.string.pieces_summary, t.piecesHave, t.pieceCount, formatBytes(t.pieceSize.toLong())),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PieceGrid(t.pieces)
                    Text(
                        stringResource(R.string.details_pieces_hint),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TorrentTab.INFO -> {
            item(key = "info") { Info(view, onCopy) }
            if (t != null) item(key = "about") { AboutCard(t) }
        }
    }
}

@Composable
private fun TransferCard(view: DownloadView, t: TorrentLive, onCommand: (TorrentCommand) -> Unit) {
    val ratio = if (t.downloadedTotal > 0) t.uploaded.toDouble() / t.downloadedTotal else 0.0
    var firstLastLocal by remember(t.prioritizeFirstLast) { mutableStateOf(t.prioritizeFirstLast) }
    val rows = listOf(
        stringResource(R.string.torrent_swarm) to stringResource(
            R.string.torrent_swarm_value, t.seeds, t.swarmSeeds.takeIf { it >= 0 }?.toString() ?: "?",
            t.peers, t.swarmPeers.takeIf { it >= 0 }?.toString() ?: "?",
        ),
        stringResource(R.string.torrent_ratio) to String.format(Locale.getDefault(), "%.3f", ratio),
        stringResource(R.string.torrent_availability) to if (t.availability < 0) "—" else String.format(Locale.getDefault(), "%.2f", t.availability),
        stringResource(R.string.torrent_downloaded) to formatBytes(t.downloadedTotal),
        stringResource(R.string.torrent_limits) to "↓ ${limitText(t.downLimit)} · ↑ ${limitText(t.upLimit)}",
    )
    Card(stringResource(R.string.torrent_transfer)) {
        rows.forEach { (label, value) -> KeyValue(label, value) }
        HorizontalDivider(Modifier.padding(vertical = Dimens.xs), color = MaterialTheme.colorScheme.outlineVariant)
        ToggleOptionRow(
            label = stringResource(R.string.torrent_sequential),
            checked = t.sequential,
            onCheckedChange = { onCommand(TorrentCommand.Sequential(it)) },
        )
        ToggleOptionRow(
            label = stringResource(R.string.torrent_first_last),
            checked = firstLastLocal,
            onCheckedChange = {
                firstLastLocal = it
                onCommand(TorrentCommand.PrioritizeFirstLast(it))
            },
        )
    }
}

@Composable
private fun TorrentOptionsCard(view: DownloadView, onCommand: (TorrentCommand) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pull_torrent_opts", android.content.Context.MODE_PRIVATE) }
    var firstLast by rememberSaveable(view.id) { mutableStateOf(prefs.getBoolean("first_last_${view.id}", false)) }
    Card(stringResource(R.string.torrent_transfer)) {
        ToggleOptionRow(
            label = stringResource(R.string.torrent_sequential),
            checked = view.entity.sequential,
            onCheckedChange = { onCommand(TorrentCommand.Sequential(it)) },
        )
        ToggleOptionRow(
            label = stringResource(R.string.torrent_first_last),
            checked = firstLast,
            onCheckedChange = {
                firstLast = it
                onCommand(TorrentCommand.PrioritizeFirstLast(it))
            },
        )
    }
}

@Composable
private fun ToggleOptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Dimens.sm))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PeerRow(
    peer: PeerLive,
    onCopyIp: () -> Unit,
    onBanPeer: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val subtitle = buildList {
        add(peer.client.ifBlank { stringResource(R.string.peer_unknown_client) })
        add("↓ ${formatSpeed(peer.down)}")
        add("↑ ${formatSpeed(peer.up)}")
        add(String.format(Locale.getDefault(), "%.1f%%", (peer.progress * 100f).coerceIn(0f, 100f)))
        if (peer.source.isNotBlank()) add(peer.source)
        if (peer.flags.isNotBlank()) add(peer.flags)
    }.joinToString(" · ")

    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { menu = true }, onLongClick = onCopyIp)
            .padding(vertical = Dimens.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(peer.ip, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.peer_copy_ip)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onCopyIp() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.peer_ban_ip)) },
                        leadingIcon = { Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onBanPeer() },
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { peer.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        HorizontalDivider(Modifier.padding(top = Dimens.sm), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AboutCard(t: TorrentLive) {
    val context = LocalContext.current
    val rows = listOfNotNull(
        stringResource(R.string.info_save_path) to t.savePath,
        t.comment.takeIf { it.isNotBlank() }?.let { stringResource(R.string.info_comment) to it },
        t.creator.takeIf { it.isNotBlank() }?.let { stringResource(R.string.info_creator) to it },
        t.createdAt.takeIf { it > 0 }?.let {
            stringResource(R.string.info_created) to DateUtils.formatDateTime(context, it * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
        },
        stringResource(R.string.info_pieces) to "${t.pieceCount} × ${formatBytes(t.pieceSize.toLong())}",
        stringResource(R.string.info_files_count) to t.files.size.toString(),
    )
    Card(stringResource(R.string.torrent_about)) { rows.forEach { (l, v) -> KeyValue(l, v) } }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun FileRow(f: com.aiyu.pull.download.TorrentFileLive, onPriority: (FilePriority) -> Unit) {
    val kind = FileKind.of(f.path, null)
    var menu by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    val (badgeContainer, badgeText) = when (f.priority) {
        FilePriority.SKIP -> c.surfaceContainerHighest to c.onSurfaceVariant
        FilePriority.LOW -> c.surfaceContainerHigh to c.onSurfaceVariant
        FilePriority.NORMAL -> c.secondaryContainer to c.onSecondaryContainer
        FilePriority.HIGH -> c.primaryContainer to c.onPrimaryContainer
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
        ShapeIcon(kind.icon, kind.shape, kind.tone.container, kind.tone.onContainer, size = 36.dp, iconSize = 18.dp)
        Spacer(Modifier.width(Dimens.md))
        Column(Modifier.weight(1f)) {
            Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            Text(
                if (f.wanted) "${formatBytes(f.done)} / ${formatBytes(f.size)}" else stringResource(R.string.file_skipped, formatBytes(f.size)),
                style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            )
            if (f.wanted && f.size > 0) LinearProgressIndicator(
                progress = { (f.done.toFloat() / f.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(Dimens.sm))
        Box {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = badgeContainer,
                modifier = Modifier.clickable { menu = true },
            ) {
                Text(
                    stringResource(f.priority.labelRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeText,
                    modifier = Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xs),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                FilePriority.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(stringResource(p.labelRes())) },
                        trailingIcon = if (p == f.priority) ({ Icon(Icons.Rounded.Check, null) }) else null,
                        onClick = { menu = false; if (p != f.priority) onPriority(p) },
                    )
                }
            }
        }
    }
}

private fun FilePriority.labelRes(): Int = when (this) {
    FilePriority.SKIP -> R.string.file_priority_skip
    FilePriority.LOW -> R.string.file_priority_low
    FilePriority.NORMAL -> R.string.file_priority_normal
    FilePriority.HIGH -> R.string.file_priority_high
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().then(
            if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier,
        ).padding(vertical = Dimens.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.tracker_remove))
                }
            }
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        HorizontalDivider(Modifier.padding(top = Dimens.sm), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Empty(text: Int) {
    Text(
        stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xl),
    )
}

/** Every piece bucket as a square, like the piece view in desktop clients. */
@Composable
private fun PieceGrid(pieces: BooleanArray) {
    val c = MaterialTheme.colorScheme
    val cols = 32
    val rows = (pieces.size + cols - 1) / cols
    val have = pieces.count { it }
    Canvas(
        Modifier.fillMaxWidth().height((rows * 10).dp).semantics { contentDescription = "$have of ${pieces.size} piece groups downloaded" },
    ) {
        val cell = size.width / cols
        val gap = 1.5.dp.toPx()
        val h = size.height / rows
        pieces.forEachIndexed { i, on ->
            drawRoundRect(
                if (on) c.primary else c.surfaceContainerHighest,
                Offset((i % cols) * cell, (i / cols) * h), Size(cell - gap, h - gap), CornerRadius(2.dp.toPx()),
            )
        }
    }
}

private fun limitText(bps: Int) = if (bps <= 0) "∞" else formatSpeed(bps.toLong())

/** Overflow menu on the details top bar for a torrent. */
@Composable
fun TorrentMenu(
    sequential: Boolean,
    prioritizeFirstLast: Boolean = false,
    running: Boolean,
    hasTorrentFile: Boolean,
    onCommand: (TorrentCommand) -> Unit,
    onLimits: () -> Unit,
    onAddTrackers: () -> Unit,
    onSelectFiles: () -> Unit,
    onShareMagnet: () -> Unit,
    onExportTorrent: () -> Unit,
    onCreateTorrent: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.torrent_sequential)) },
            leadingIcon = { Checkbox(checked = sequential, onCheckedChange = null) },
            onClick = { open = false; onCommand(TorrentCommand.Sequential(!sequential)) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.torrent_first_last)) },
            leadingIcon = { Checkbox(checked = prioritizeFirstLast, onCheckedChange = null) },
            onClick = { open = false; onCommand(TorrentCommand.PrioritizeFirstLast(!prioritizeFirstLast)) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_title)) }, leadingIcon = { Icon(Icons.Rounded.Checklist, null) },
            onClick = { open = false; onSelectFiles() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.torrent_limits)) }, leadingIcon = { Icon(Icons.Rounded.Speed, null) },
            onClick = { open = false; onLimits() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.trackers_add)) }, leadingIcon = { Icon(Icons.Rounded.Add, null) },
            onClick = { open = false; onAddTrackers() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.torrent_recheck)) }, leadingIcon = { Icon(Icons.Rounded.Sync, null) },
            enabled = running, onClick = { open = false; onCommand(TorrentCommand.Recheck) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.trackers_reannounce)) }, leadingIcon = { Icon(Icons.Rounded.Campaign, null) },
            enabled = running, onClick = { open = false; onCommand(TorrentCommand.Reannounce) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.torrent_share_magnet)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
            onClick = { open = false; onShareMagnet() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.torrent_export_file)) }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
            enabled = hasTorrentFile, onClick = { open = false; onExportTorrent() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.torrent_create_file)) }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) },
            onClick = { open = false; onCreateTorrent() },
        )
    }
}

/** Dialog to pick a file via SAF, enter optional tracker URLs/comment, generate a `.torrent`, and share it. */
@Composable
fun CreateTorrentDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    var pickedSize by rememberSaveable { mutableLongStateOf(-1L) }
    var trackersText by rememberSaveable { mutableStateOf("") }
    var commentText by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
            }
            pickedName = name
            pickedSize = size
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Rounded.NoteAdd, null) },
        title = { Text(stringResource(R.string.torrent_create_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                if (pickedUri == null) {
                    Text(stringResource(R.string.torrent_create_no_file), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        pickedName + (if (pickedSize >= 0) " (${formatBytes(pickedSize)})" else ""),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                FilledTonalButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(Dimens.sm))
                    Text(stringResource(if (pickedUri == null) R.string.torrent_create_pick_file else R.string.torrent_create_change_file))
                }
                OutlinedTextField(
                    value = trackersText,
                    onValueChange = { trackersText = it },
                    label = { Text(stringResource(R.string.trackers_hint)) },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text(stringResource(R.string.torrent_create_comment)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pickedUri != null && !busy,
                onClick = {
                    val uri = pickedUri ?: return@TextButton
                    busy = true
                    scope.launch {
                        val outFile = withContext(Dispatchers.IO) {
                            runCatching {
                                val trackers = trackersText.lines().map(String::trim).filter(String::isNotEmpty)
                                context.contentResolver.openInputStream(uri)?.buffered()?.use { stream ->
                                    val bytes = TorrentCreator.create(
                                        input = stream,
                                        name = pickedName.ifBlank { "file" },
                                        size = pickedSize,
                                        trackers = trackers,
                                        comment = commentText,
                                    )
                                    val torrentsDir = File(context.filesDir, "torrents")
                                    TorrentCreator.saveToFile(torrentsDir, pickedName.ifBlank { "file" }, bytes)
                                }
                            }.getOrNull()
                        }
                        busy = false
                        onDismiss()
                        if (outFile != null) {
                            shareTorrentFile(context, outFile.path, pickedName.ifBlank { "file" })
                        }
                    }
                },
            ) {
                Text(stringResource(if (busy) R.string.torrent_creating else R.string.torrent_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Per-torrent speed limits in KB/s; empty or 0 means unlimited. */
@Composable
fun LimitsDialog(down: Int, up: Int, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var d by rememberSaveable { mutableStateOf(if (down > 0) (down / 1024).toString() else "") }
    var u by rememberSaveable { mutableStateOf(if (up > 0) (up / 1024).toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Speed, null) },
        title = { Text(stringResource(R.string.torrent_limits)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Text(stringResource(R.string.limits_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    d, { d = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.limit_down)) },
                    suffix = { Text("KB/s") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    u, { u = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.limit_up)) },
                    suffix = { Text("KB/s") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply((d.toIntOrNull() ?: 0).coerceAtMost(Int.MAX_VALUE / 1024) * 1024, (u.toIntOrNull() ?: 0).coerceAtMost(Int.MAX_VALUE / 1024) * 1024)
            }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Paste tracker URLs, one per line (the usual way to share tracker lists). */
@Composable
fun AddTrackersDialog(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val urls = text.lines().map(String::trim).filter { Regex("""^(udp|https?|wss?)://\S+$""").matches(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Add, null) },
        title = { Text(stringResource(R.string.trackers_add)) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(stringResource(R.string.trackers_hint)) },
                minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(urls) }, enabled = urls.isNotEmpty()) { Text(stringResource(R.string.trackers_add_n, urls.size)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
