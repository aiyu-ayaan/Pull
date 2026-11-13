package com.aiyu.pull.ui.details

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.aiyu.pull.download.TorrentCommand
import com.aiyu.pull.download.TorrentLive
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.PieceMap
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.SpeedGraph
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.downloads.DownloadView
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.formatDuration
import com.aiyu.pull.ui.formatSpeed
import com.aiyu.pull.ui.theme.Dimens
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
                item(key = "transfer") { TransferCard(view, t) }
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
            items(files.size, key = { "file$it" }) { i -> FileRow(files[i]) }
        }
        TorrentTab.TRACKERS -> {
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
                }
            }
            val list = t?.trackers.orEmpty()
            if (list.isEmpty()) item(key = "tr-empty") { Empty(if (t == null) R.string.torrent_not_running else R.string.trackers_none) }
            items(list, key = { "tr" + it.url }) { tr ->
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
                        if (tr.message.isNotBlank()) append(" · ").append(tr.message)
                    },
                    onLongClick = { onCopy(tr.url, tr.url) },
                )
            }
        }
        TorrentTab.PEERS -> {
            val list = t?.peerList.orEmpty()
            if (list.isEmpty()) item(key = "peer-empty") { Empty(if (t == null) R.string.torrent_not_running else R.string.peers_none) }
            items(list, key = { "peer" + it.ip }) { p ->
                ListRow(
                    title = p.ip,
                    subtitle = listOf(
                        p.client.ifBlank { stringResource(R.string.peer_unknown_client) },
                        "↓ ${formatSpeed(p.down)}", "↑ ${formatSpeed(p.up)}",
                        String.format(Locale.getDefault(), "%.1f%%", p.progress * 100),
                    ).joinToString(" · "),
                    progress = p.progress,
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
private fun TransferCard(view: DownloadView, t: TorrentLive) {
    val ratio = if (t.downloadedTotal > 0) t.uploaded.toDouble() / t.downloadedTotal else 0.0
    val rows = listOf(
        stringResource(R.string.torrent_swarm) to stringResource(
            R.string.torrent_swarm_value, t.seeds, t.swarmSeeds.takeIf { it >= 0 }?.toString() ?: "?",
            t.peers, t.swarmPeers.takeIf { it >= 0 }?.toString() ?: "?",
        ),
        stringResource(R.string.torrent_ratio) to String.format(Locale.getDefault(), "%.3f", ratio),
        stringResource(R.string.torrent_availability) to if (t.availability < 0) "—" else String.format(Locale.getDefault(), "%.2f", t.availability),
        stringResource(R.string.torrent_downloaded) to formatBytes(t.downloadedTotal),
        stringResource(R.string.torrent_limits) to "↓ ${limitText(t.downLimit)} · ↑ ${limitText(t.upLimit)}",
        stringResource(R.string.torrent_sequential) to stringResource(if (t.sequential) R.string.on else R.string.off),
    )
    Card(stringResource(R.string.torrent_transfer)) {
        rows.forEach { (label, value) -> KeyValue(label, value) }
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
private fun FileRow(f: com.aiyu.pull.download.TorrentFileLive) {
    val kind = FileKind.of(f.path, null)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
        ShapeIcon(kind.icon, kind.shape, kind.tone.container, kind.tone.onContainer, size = 36.dp, iconSize = 18.dp)
        Spacer(Modifier.width(Dimens.md))
        Column(Modifier.weight(1f)) {
            Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            Text(
                if (f.wanted) "${formatBytes(f.done)} / ${formatBytes(f.size)}" else stringResource(R.string.file_skipped, formatBytes(f.size)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (f.wanted && f.size > 0) LinearProgressIndicator(
                progress = { (f.done.toFloat() / f.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ListRow(title: String, subtitle: String, progress: Float? = null, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().then(
            if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier,
        ).padding(vertical = Dimens.xs),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
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
    running: Boolean,
    onCommand: (TorrentCommand) -> Unit,
    onLimits: () -> Unit,
    onAddTrackers: () -> Unit,
    onSelectFiles: () -> Unit,
    onShareMagnet: () -> Unit,
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
    }
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
