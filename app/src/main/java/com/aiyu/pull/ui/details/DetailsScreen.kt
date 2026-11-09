package com.aiyu.pull.ui.details

import android.content.ClipData
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.data.Kind
import com.aiyu.pull.download.TorrentCommand
import com.aiyu.pull.data.Status
import com.aiyu.pull.engine.ErrorKind
import com.aiyu.pull.engine.SegmentProgress
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.SegmentMap
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.SpeedGraph
import com.aiyu.pull.ui.components.openFile
import com.aiyu.pull.ui.components.readableGutter
import com.aiyu.pull.ui.components.rememberGlide
import com.aiyu.pull.ui.components.shareFile
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.add.PickFile
import com.aiyu.pull.ui.add.TorrentFilesSheet
import com.aiyu.pull.ui.downloads.DownloadView
import com.aiyu.pull.ui.downloads.DownloadsViewModel
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.formatDuration
import com.aiyu.pull.ui.formatSpeed
import com.aiyu.pull.ui.speakSpeed
import com.aiyu.pull.ui.theme.Dimens
import com.aiyu.pull.ui.theme.NumberTextStyle
import com.aiyu.pull.video.VideoEngine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Everything about one download, IDM-style: live percent and byte counter, transfer rate, time left, the
 * connection map, per-connection table and a one-minute speed graph. Used full screen and as the wide-window pane.
 */
@Composable
fun DetailsScreen(
    id: Long,
    vm: DownloadsViewModel,
    onBack: (() -> Unit)?,
    onOpenPage: (url: String, refreshId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entity by remember(id) { vm.observe(id) }.collectAsStateWithLifecycle(null)
    val live by vm.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var askDelete by rememberSaveable { mutableStateOf(false) }
    var askLink by rememberSaveable { mutableStateOf(false) }
    var pickFiles by rememberSaveable { mutableStateOf(false) }
    var askLimits by rememberSaveable { mutableStateOf(false) }
    var askTrackers by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(TorrentTab.STATUS) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val e = entity
    // Deleted from here or elsewhere: leave instead of showing an empty page.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(e) { if (e != null) seen = true else if (seen) onBack?.invoke() }

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(e?.fileName.orEmpty(), maxLines = 2, overflow = TextOverflow.MiddleEllipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (e?.kind == Kind.TORRENT) TorrentMenu(
                        sequential = live[e.id]?.torrent?.sequential ?: e.sequential,
                        running = e.status == Status.RUNNING,
                        onCommand = { vm.torrentCommand(id, it) },
                        onLimits = { askLimits = true },
                        onAddTrackers = { askTrackers = true },
                        onSelectFiles = { pickFiles = true },
                    )
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (e == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Scaffold
        }
        val view = DownloadView(e, live[e.id])
        LazyColumn(
            contentPadding = PaddingValues(
                start = readableGutter(), end = readableGutter(),
                top = Dimens.sm, bottom = padding.calculateBottomPadding() + Dimens.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.md),
            // Padding outside the list, so the pinned torrent tabs stop below the top bar instead of under it.
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            item(key = "hero") { Hero(view) }
            item(key = "actions") {
                Actions(
                    view,
                    onPause = { vm.pause(id) },
                    onResume = { vm.resume(id) },
                    onOpen = {
                        if (!openFile(context, vm.files, e)) scope.launch { snackbar.showSnackbar(context.getString(R.string.no_app_to_open)) }
                    },
                    onShare = { shareFile(context, vm.files, e) },
                    onDelete = { askDelete = true },
                )
            }
            if (e.status == Status.FAILED) item(key = "problem") {
                Problem(
                    view,
                    onRetry = { vm.resume(id) },
                    onRestart = { vm.restart(id) },
                    onOpenPage = { onOpenPage(e.pageUrl ?: e.url, id) },
                    onPasteLink = { askLink = true },
                )
            }
            val copy = { label: String, value: String ->
                scope.launch {
                    clipboardCopy(context, label, value)
                    snackbar.showSnackbar(context.getString(R.string.copied, label))
                }
                Unit
            }
            if (e.kind == Kind.TORRENT) {
                stickyHeader(key = "tabs") { TorrentTabRow(tab) { tab = it } }
                torrentTab(
                    tab, view,
                    onSelectFiles = { pickFiles = true },
                    onCommand = { vm.torrentCommand(id, it) },
                    onAddTrackers = { askTrackers = true },
                    onCopy = copy,
                )
                return@LazyColumn
            }
            if (e.status != Status.COMPLETED) item(key = "stats") { Stats(view) }
            if (e.kind == Kind.HTTP && e.status != Status.COMPLETED && view.total > 0) item(key = "map") { MapCard(view) }
            if (view.live != null) item(key = "graph") {
                Card(stringResource(R.string.details_speed_graph)) {
                    SpeedGraph(view.live.history)
                    Text(
                        stringResource(R.string.details_peak, formatSpeed(view.live.peakBytesPerSecond)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val segs = view.live?.segments.orEmpty()
            if (segs.isNotEmpty()) item(key = "connections") { Connections(segs) }
            item(key = "info") { Info(view, onCopy = copy) }
        }
    }

    if (askLimits && e != null) {
        val t = live[e.id]?.torrent
        LimitsDialog(
            down = t?.downLimit ?: e.downLimit, up = t?.upLimit ?: e.upLimit,
            onDismiss = { askLimits = false },
            onApply = { d, u -> askLimits = false; vm.torrentCommand(id, TorrentCommand.Limits(d, u)) },
        )
    }
    if (askTrackers) {
        AddTrackersDialog(
            onDismiss = { askTrackers = false },
            onAdd = { askTrackers = false; vm.torrentCommand(id, TorrentCommand.AddTrackers(it)) },
        )
    }

    if (askDelete && e != null) {
        var withFile by rememberSaveable { mutableStateOf(e.status != Status.COMPLETED) }
        AlertDialog(
            onDismissRequest = { askDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withFile, onCheckedChange = { withFile = it }, enabled = e.status == Status.COMPLETED)
                    Text(stringResource(R.string.delete_file_too))
                }
            },
            confirmButton = {
                TextButton(onClick = { askDelete = false; vm.delete(id, withFile); onBack?.invoke() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (pickFiles && e != null) {
        val files = torrentPickFiles(e, live[e.id], vm)
        TorrentFilesSheet(
            name = e.fileName,
            files = files,
            selected = selectedSet(e.selectedFiles, files.size),
            onDismiss = { pickFiles = false },
            onApply = { pickFiles = false; vm.setTorrentFiles(id, it, files.size) },
        )
    }
    if (askLink) {
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askLink = false },
            icon = { Icon(Icons.Rounded.Refresh, null) },
            title = { Text(stringResource(R.string.refresh_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.refresh_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Dimens.md))
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.add_url)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { askLink = false; vm.refreshLink(id, url.trim()) },
                    enabled = url.trim().startsWith("http"),
                ) { Text(stringResource(R.string.action_resume)) }
            },
            dismissButton = { TextButton(onClick = { askLink = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Hero(view: DownloadView) {
    val e = view.entity
    val kind = FileKind.of(e.fileName, e.mimeType)
    val tone = kind.tone
    val c = MaterialTheme.colorScheme
    val downloaded by rememberGlide(view.downloaded)
    val total = view.total
    val fraction = if (e.status == Status.COMPLETED) 1f else if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    Surface(shape = MaterialTheme.shapes.extraLarge, color = c.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(kind.icon, kind.shape, tone.container, tone.onContainer, size = 56.dp, iconSize = 28.dp)
                Spacer(Modifier.width(Dimens.lg))
                Column(Modifier.weight(1f)) {
                    // Percent with two decimals: it visibly moves every frame at any speed.
                    val percent = if (total > 0 || e.status == Status.COMPLETED) String.format(Locale.getDefault(), "%.2f%%", fraction * 100) else "—"
                    Text(
                        percent,
                        style = NumberTextStyle,
                        color = if (e.status == Status.FAILED) c.error else c.primary,
                        modifier = Modifier.semantics { contentDescription = "${(fraction * 100).toInt()} percent" },
                    )
                    Text(
                        if (total > 0) stringResource(R.string.details_of, formatBytes(downloaded), formatBytes(total)) else formatBytes(downloaded),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = c.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Dimens.lg))
            val stroke = with(LocalDensity.current) { androidx.compose.ui.graphics.drawscope.Stroke(8.dp.toPx(), cap = StrokeCap.Round) }
            val m = Modifier.fillMaxWidth().height(20.dp)
            when {
                view.running && total <= 0 -> LinearWavyProgressIndicator(m, stroke = stroke, trackStroke = stroke)
                view.running -> LinearWavyProgressIndicator(
                    progress = { fraction }, modifier = m, stroke = stroke, trackStroke = stroke,
                    wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
                )
                else -> LinearProgressIndicator(
                    progress = { fraction }, modifier = m.height(8.dp),
                    color = if (e.status == Status.FAILED) c.error else c.primary,
                    strokeCap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.height(Dimens.md))
            Text(statusText(view), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
            if (view.entity.kind == Kind.TORRENT) {
                Spacer(Modifier.height(Dimens.lg))
                TorrentStages(torrentStage(view))
            }
        }
    }
}

@Composable
private fun statusText(view: DownloadView): String = when (view.entity.status) {
    Status.RUNNING if view.entity.kind == Kind.TORRENT -> {
        val t = view.live?.torrent
        when (t?.state) {
            null -> stringResource(R.string.status_connecting)
            "metadata", "downloading_metadata" -> stringResource(R.string.torrent_state_metadata)
            "checking_files", "checking_resume_data" -> stringResource(R.string.torrent_state_checking)
            else -> pluralStringResource(R.plurals.torrent_state_downloading, t.peers, t.peers)
        }
    }
    Status.RUNNING if view.entity.kind == Kind.VIDEO -> stringResource(R.string.video_state_downloading)
    Status.RUNNING -> {
        val active = view.live?.segments?.count { it.active } ?: 0
        if (active == 0) stringResource(R.string.status_connecting) else stringResource(R.string.status_receiving, active)
    }
    Status.QUEUED -> stringResource(R.string.status_queued)
    Status.PAUSED -> stringResource(R.string.status_paused)
    Status.COMPLETED -> stringResource(R.string.status_completed)
    Status.FAILED -> stringResource(R.string.status_failed)
}

@Composable
private fun Actions(
    view: DownloadView,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = view.entity.status
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        when (s) {
            Status.RUNNING, Status.QUEUED -> Button(onClick = onPause, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Pause, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                Text(stringResource(R.string.action_pause))
            }
            Status.PAUSED, Status.FAILED -> Button(onClick = onResume, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                Text(stringResource(R.string.action_resume))
            }
            Status.COMPLETED -> {
                Button(onClick = onOpen, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                    Text(stringResource(R.string.action_open))
                }
                FilledTonalButton(onClick = onShare, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                    Text(stringResource(R.string.action_share))
                }
            }
        }
        OutlinedButton(onClick = onDelete, shapes = ButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Delete, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
            Text(stringResource(R.string.action_delete))
        }
    }
}

/** Why it stopped and the way out. An expired link gets FDM's fix: fetch a fresh link from the web page. */
@Composable
private fun Problem(view: DownloadView, onRetry: () -> Unit, onRestart: () -> Unit, onOpenPage: () -> Unit, onPasteLink: () -> Unit) {
    val e = view.entity
    val c = MaterialTheme.colorScheme
    val kind = e.errorKind
    Surface(shape = MaterialTheme.shapes.large, color = c.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (kind == ErrorKind.LINK_EXPIRED.name) Icons.Rounded.LinkOff else Icons.Rounded.ErrorOutline, null, tint = c.onErrorContainer)
                Spacer(Modifier.width(Dimens.sm))
                Text(
                    stringResource(
                        when (kind) {
                            ErrorKind.LINK_EXPIRED.name -> R.string.problem_expired_title
                            ErrorKind.FILE_CHANGED.name -> R.string.problem_changed_title
                            ErrorKind.DISK.name -> R.string.problem_disk_title
                            else -> R.string.problem_network_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium, color = c.onErrorContainer,
                )
            }
            Spacer(Modifier.height(Dimens.xs))
            Text(
                when (kind) {
                    ErrorKind.LINK_EXPIRED.name -> stringResource(R.string.problem_expired_body)
                    ErrorKind.FILE_CHANGED.name -> stringResource(R.string.problem_changed_body)
                    else -> e.error.orEmpty()
                },
                style = MaterialTheme.typography.bodyMedium, color = c.onErrorContainer,
            )
            Spacer(Modifier.height(Dimens.md))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                when (kind) {
                    ErrorKind.LINK_EXPIRED.name -> {
                        Button(onClick = onOpenPage, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Public, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                            Text(stringResource(R.string.action_get_new_link))
                        }
                        FilledTonalButton(onClick = onPasteLink, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.ContentPaste, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                            Text(stringResource(R.string.action_paste_link))
                        }
                    }
                    ErrorKind.FILE_CHANGED.name -> Button(onClick = onRestart, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.RestartAlt, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                        Text(stringResource(R.string.action_restart))
                    }
                    else -> {
                        Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                            Text(stringResource(R.string.action_retry))
                        }
                        TextButton(onClick = onRestart) { Text(stringResource(R.string.action_restart)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Stats(view: DownloadView) {
    val e = view.entity
    val speed by rememberGlide(view.speed)
    val avg = if (e.activeMillis > 1000) e.downloadedBytes * 1000 / e.activeMillis else 0
    val connections = view.live?.segments?.count { it.active } ?: 0
    val t = view.live?.torrent
    val tiles = buildList {
        add(Triple(R.string.stat_rate, if (view.running) formatSpeed(speed) else "—", speakSpeed(view.speed)))
        add(Triple(R.string.stat_left, view.eta?.let(::formatDuration) ?: "—", null))
        if (e.kind == Kind.TORRENT) {
            add(Triple(R.string.stat_peers, t?.let { "${it.peers} / ${it.seeds}" } ?: "—", null))
            add(Triple(R.string.stat_upload, t?.let { formatSpeed(it.uploadBytesPerSecond) } ?: "—", null))
            add(Triple(R.string.stat_uploaded, t?.let { formatBytes(it.uploaded) } ?: "—", null))
            add(Triple(R.string.stat_elapsed, formatDuration(e.activeMillis / 1000), null))
            return@buildList
        }
        if (e.kind == Kind.VIDEO) {
            add(Triple(R.string.stat_avg, if (avg > 0) formatSpeed(avg) else "—", null))
            add(Triple(R.string.stat_elapsed, formatDuration(e.activeMillis / 1000), null))
            return@buildList
        }
        add(Triple(R.string.stat_connections, if (view.running) "$connections / ${e.maxConnections}" else "${e.maxConnections}", null))
        add(Triple(R.string.stat_avg, if (avg > 0) formatSpeed(avg) else "—", null))
        add(Triple(R.string.stat_elapsed, formatDuration(e.activeMillis / 1000), null))
        add(
            Triple(
                R.string.stat_resume,
                stringResource(
                    when {
                        e.finalUrl == null -> R.string.resume_unknown
                        e.acceptRanges -> R.string.resume_yes
                        else -> R.string.resume_no
                    },
                ),
                null,
            ),
        )
    }
    FlowRow(
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        tiles.forEach { (label, value, spoken) ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(Dimens.lg).semantics(mergeDescendants = true) { spoken?.let { contentDescription = it } }) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MapCard(view: DownloadView) {
    val segs = view.live?.segments ?: com.aiyu.pull.data.decodeSegments(view.entity.segments)
        .map { SegmentProgress(it.start, it.end, it.current, 0, false, 0) }
    Card(stringResource(R.string.details_map)) {
        SegmentMap(view.total, segs, view.entity.status == Status.COMPLETED)
        Text(
            stringResource(R.string.details_map_hint),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** IDM's connection table: one row per range with its own bar, speed and state. */
@Composable
private fun Connections(segs: List<SegmentProgress>) {
    Card(stringResource(R.string.details_connections)) {
        segs.forEachIndexed { i, s ->
            val size = s.end - s.start + 1
            val done = (s.current - s.start).coerceIn(0, size)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { if (size > 0) done.toFloat() / size else 1f },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (s.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${formatBytes(s.start)} – ${formatBytes(s.end + 1)} · ${
                            when {
                                s.current > s.end -> stringResource(R.string.conn_done)
                                s.active && s.bytesPerSecond > 0 -> formatSpeed(s.bytesPerSecond)
                                s.active -> stringResource(R.string.conn_connecting)
                                else -> stringResource(R.string.conn_idle)
                            }
                        }" + if (s.retries > 0) " · " + stringResource(R.string.conn_retries, s.retries) else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Info(view: DownloadView, onCopy: (String, String) -> Unit) {
    val e = view.entity
    val context = LocalContext.current
    val rows = listOfNotNull(
        stringResource(if (e.kind == Kind.TORRENT) R.string.info_magnet else R.string.info_url) to e.url,
        e.infoHash?.let { stringResource(R.string.info_hash) to it },
        e.videoFormat?.let { stringResource(R.string.info_format) to videoFormatLabel(it) },
        e.extraArgs?.let { stringResource(R.string.info_command) to it },
        e.finalUrl?.takeIf { it != e.url }?.let { stringResource(R.string.info_final_url) to it },
        e.pageUrl?.takeIf { it != e.url }?.let { stringResource(R.string.info_page) to it },
        stringResource(R.string.info_saved_to) to "Download/Pull/${e.fileName}",
        e.mimeType?.let { stringResource(R.string.info_type) to it },
        e.etag?.let { "ETag" to it },
        e.lastModified?.let { stringResource(R.string.info_modified) to it },
        stringResource(R.string.info_added) to DateUtils.formatDateTime(
            LocalContext.current, e.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
        ),
    )
    Card(stringResource(R.string.details_info)) {
        rows.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (value.startsWith("http") || value.startsWith("magnet:") || value == e.infoHash) IconButton(onClick = { onCopy(label, value) }) {
                    Icon(Icons.Rounded.ContentCopy, stringResource(R.string.cd_copy, label))
                }
                if (value.startsWith("magnet:")) IconButton(onClick = { shareText(context, value) }) {
                    Icon(Icons.Rounded.Share, stringResource(R.string.cd_share, label))
                }
            }
        }
    }
}

@Composable
internal fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.lg), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

private fun clipboardCopy(context: android.content.Context, label: String, value: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

/** Torrent files with live progress while running, else read from the stored .torrent. */
@Composable
private fun torrentPickFiles(e: com.aiyu.pull.data.DownloadEntity, live: com.aiyu.pull.download.LiveStats?, vm: DownloadsViewModel): List<PickFile> {
    val stored = remember(e.torrentPath) { vm.torrentFiles(e) }
    val running = live?.torrent?.files.orEmpty()
    return if (running.isNotEmpty()) running.mapIndexed { i, f -> PickFile(i, f.path, f.size, f.done) }
    else stored.mapIndexed { i, (path, size) -> PickFile(i, path, size) }
}

/** Saved selection: empty means every file. */
private fun selectedSet(saved: String, count: Int): Set<Int> =
    saved.split(',').mapNotNull(String::toIntOrNull).toSet().ifEmpty { (0 until count).toSet() }

@Composable
internal fun FilesCard(view: DownloadView, onSelect: () -> Unit) {
    val e = view.entity
    val files = view.live?.torrent?.files
    Card(stringResource(R.string.torrent_files)) {
        if (files != null && files.isNotEmpty()) {
            val wanted = files.filter { it.wanted }
            Text(
                stringResource(R.string.files_selected, wanted.size, files.size, formatBytes(wanted.sumOf { it.size })),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (e.torrentPath == null) {
            Text(stringResource(R.string.torrent_fetching), style = MaterialTheme.typography.bodyMedium)
        }
        FilledTonalButton(onClick = onSelect, enabled = e.torrentPath != null, shapes = ButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Checklist, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
            Text(stringResource(R.string.files_title))
        }
    }
}

@Composable
private fun videoFormatLabel(format: String): String = when {
    format == VideoEngine.BEST -> stringResource(R.string.quality_best)
    format == VideoEngine.AUDIO_ONLY -> stringResource(R.string.quality_audio)
    else -> Regex("""height<=(\d+)""").find(format)?.let { "${it.groupValues[1]}p" } ?: format
}

private fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, null))
}

/** 0 metadata, 1 checking, 2 downloading, 3 done. */
private fun torrentStage(view: DownloadView): Int {
    val e = view.entity
    if (e.status == Status.COMPLETED) return 3
    return when (view.live?.torrent?.state) {
        "metadata", "downloading_metadata" -> 0
        "checking_files", "checking_resume_data" -> 1
        "downloading" -> 2
        "finished", "seeding" -> 3
        else -> if (e.torrentPath == null) 0 else 2
    }
}

/** The steps a torrent goes through, so a long metadata fetch or re-check does not look stuck. */
@Composable
private fun TorrentStages(current: Int) {
    val c = MaterialTheme.colorScheme
    val labels = listOf(R.string.stage_metadata, R.string.stage_checking, R.string.stage_downloading, R.string.stage_done)
    val done = current >= labels.lastIndex
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            val reached = i < current || done
            val now = i == current && !done
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(2.dp).then(if (i > 0) Modifier.background(if (i <= current) c.primary else c.outlineVariant) else Modifier))
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (reached) c.primary else if (now) c.primaryContainer else c.surfaceContainerHighest,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when {
                                reached -> Icon(Icons.Rounded.Check, null, tint = c.onPrimary, modifier = Modifier.size(16.dp))
                                now -> LoadingIndicator(Modifier.size(20.dp))
                            }
                        }
                    }
                    Box(Modifier.weight(1f).height(2.dp).then(if (i < labels.lastIndex) Modifier.background(if (i < current) c.primary else c.outlineVariant) else Modifier))
                }
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (now) c.primary else c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
