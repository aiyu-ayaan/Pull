package com.aiyu.pull.ui.add

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.formatDuration
import com.aiyu.pull.ui.theme.Dimens
import com.aiyu.pull.ui.theme.clickHaptics
import com.aiyu.pull.video.VideoEngine
import com.aiyu.pull.video.VideoMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * What opens the sheet: a typed/pasted/shared link or magnet, a download the browser caught (with its cookies),
 * or a .torrent file copied to [torrentFile].
 */
@Serializable
data class AddRequest(
    val url: String = "",
    val fileName: String? = null,
    val pageUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val torrentFile: String? = null,
)

/**
 * One popup for everything Pull downloads. It reads the input and switches itself: a file link (size, resume,
 * connections), a torrent or magnet (file picker, sequential), or a video page (quality picker, Best by default).
 */
@Composable
fun AddDownloadSheet(request: AddRequest, vm: AddViewModel, onDismiss: () -> Unit, onAdded: (Long) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Saveable: a process death while the sheet is open brings the same input back.
    var url by rememberSaveable { mutableStateOf(request.url) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(url, request.torrentFile) {
        if (request.torrentFile == null) delay(400) // typing debounce
        vm.analyze(url.trim(), request.headers, request.torrentFile)
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val add: (suspend () -> Long) -> Unit = { block ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        scope.launch {
            val id = block()
            sheet.hide()
            onAdded(id)
        }
    }

    ModalBottomSheet(onDismissRequest = { vm.reset(); onDismiss() }, sheetState = sheet, modifier = Modifier.clickHaptics()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.xl)
                .imePadding().navigationBarsPadding().padding(bottom = Dimens.lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.lg),
        ) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall)
            if (request.torrentFile == null) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.add_url_any)) },
                    placeholder = { Text("https://  ·  magnet:?") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    isError = state is Analysis.Failed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedContent(state, contentKey = { it::class }, label = "analysis") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.lg)) {
                    when (s) {
                        Analysis.Idle -> Hint(stringResource(R.string.probe_idle_any))
                        is Analysis.Loading -> Busy(stringResource(s.message))
                        is Analysis.Failed -> Problem(s.message)
                        is Analysis.FileLink -> FileBody(s, url.trim(), request, vm, add)
                        is Analysis.WebPage -> WebPageBody(onFind = { vm.findVideos(url.trim()) })
                        is Analysis.Torrent -> TorrentBody(s, vm, add)
                        is Analysis.Video -> VideoBody(s.meta, url.trim(), request, vm, add)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.lg))
    }
}

@Composable
private fun Busy(text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.lg), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(40.dp))
            Spacer(Modifier.width(Dimens.md))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Problem(text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(Dimens.md))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun Header(icon: @Composable () -> Unit, title: String, lines: List<String>) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.lg), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Actions(onLater: () -> Unit, onStart: () -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = onLater, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
            Text(stringResource(R.string.add_later))
        }
        Button(onClick = onStart, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
            Text(stringResource(R.string.add_start))
        }
    }
}

@Composable
private fun ColumnScope.FileBody(s: Analysis.FileLink, url: String, request: AddRequest, vm: AddViewModel, add: (suspend () -> Long) -> Unit) {
    val info = s.info
    var name by rememberSaveable(info.finalUrl) { mutableStateOf(request.fileName ?: info.fileName.orEmpty()) }
    val defaults by vm.connections.collectAsStateWithLifecycle()
    var connections by rememberSaveable { mutableFloatStateOf(-1f) }
    if (connections < 0 && defaults > 0) connections = defaults.toFloat()
    val kind = FileKind.of(name, info.mimeType)
    Header(
        icon = { ShapeIcon(kind.icon, kind.shape, kind.tone.container, kind.tone.onContainer, size = 40.dp, iconSize = 20.dp) },
        title = if (info.totalBytes >= 0) formatBytes(info.totalBytes) else stringResource(R.string.probe_unknown_size),
        lines = listOf(stringResource(if (info.acceptRanges) R.string.probe_resumable else R.string.probe_not_resumable)),
    )
    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.add_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.add_connections), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${connections.toInt().coerceAtLeast(1)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = connections.coerceIn(1f, 32f), onValueChange = { connections = it }, valueRange = 1f..32f, steps = 30)
    }
    val go = { start: Boolean ->
        // The typed link, not the redirect target: an expired signed URL is re-resolved from it later.
        add { vm.addFile(url, name, request.pageUrl, request.headers, connections.toInt(), start) }
    }
    Actions(onLater = { go(false) }, onStart = { go(true) })
}

@Composable
private fun WebPageBody(onFind: () -> Unit) {
    Header(
        icon = { Icon(Icons.Rounded.Language, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
        title = stringResource(R.string.analyze_web_page),
        lines = listOf(stringResource(R.string.analyze_web_page_hint)),
    )
    Button(onClick = onFind, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Rounded.Movie, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
        Text(stringResource(R.string.analyze_find_videos))
    }
}

@Composable
private fun ColumnScope.TorrentBody(s: Analysis.Torrent, vm: AddViewModel, add: (suspend () -> Long) -> Unit) {
    val torrentIcon = @Composable {
        ShapeIcon(Icons.Rounded.Hub, MaterialShapes.Flower, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, size = 40.dp, iconSize = 20.dp)
    }
    val seqDefault by vm.sequentialDefault.collectAsStateWithLifecycle()
    var sequential by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val seq = sequential ?: seqDefault
    val meta = s.meta
    if (meta == null) {
        if (s.fetching) Busy(stringResource(R.string.torrent_fetching)) else Hint(stringResource(R.string.torrent_fetch_failed))
        Text(stringResource(R.string.torrent_fetch_later_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Actions(
            onLater = { add { vm.addTorrent(null, s.magnet, emptySet(), seq, false) } },
            onStart = { add { vm.addTorrent(null, s.magnet, emptySet(), seq, true) } },
        )
        return
    }
    // Saveable as a list: which files to download (all by default).
    var chosen by rememberSaveable(meta.infoHash) { mutableStateOf(meta.files.indices.toList()) }
    val size = chosen.sumOf { meta.files[it].second }
    Header(torrentIcon, meta.name, listOf(stringResource(R.string.torrent_summary, formatBytes(size), chosen.size, meta.files.size)))
    var picking by rememberSaveable { mutableStateOf(false) }
    if (meta.files.size > 1) {
        Surface(
            onClick = { picking = true },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(Dimens.lg), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Checklist, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(Dimens.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.files_choose), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        stringResource(R.string.files_selected, chosen.size, meta.files.size, formatBytes(size)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    if (picking) {
        TorrentFilesSheet(
            name = meta.name,
            files = meta.files.mapIndexed { i, (path, bytes) -> PickFile(i, path, bytes) },
            selected = chosen.toSet(),
            onDismiss = { picking = false },
            onApply = { chosen = it.sorted(); picking = false },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.torrent_sequential), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.torrent_sequential_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = seq, onCheckedChange = { sequential = it })
    }
    val selected = if (chosen.size == meta.files.size) emptySet() else chosen.toSet()
    Actions(
        onLater = { add { vm.addTorrent(meta, s.magnet, selected, seq, false) } },
        onStart = { add { vm.addTorrent(meta, s.magnet, selected, seq, true) } },
        enabled = chosen.isNotEmpty(),
    )
}

/** Quality choices offered for a video. [format] is the yt-dlp selector. */
private data class Quality(val key: String, val label: String, val format: String, val size: Long)

@Composable
private fun ColumnScope.VideoBody(meta: VideoMeta, url: String, request: AddRequest, vm: AddViewModel, add: (suspend () -> Long) -> Unit) {
    val bestAudio = meta.formats.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { it.sizeBytes }?.sizeBytes ?: 0
    val heights = meta.formats.filter { it.hasVideo && it.height > 0 }.groupBy { it.height }.toSortedMap(compareByDescending { it })
    val best = stringResource(R.string.quality_best)
    val audio = stringResource(R.string.quality_audio)
    val choices = buildList {
        add(Quality("best", best, VideoEngine.BEST, (heights.values.firstOrNull()?.maxOf { it.sizeBytes } ?: 0) + bestAudio))
        heights.forEach { (h, list) ->
            add(Quality("h$h", "${h}p", "bv*[height<=$h]+ba/b[height<=$h]", list.maxOf { it.sizeBytes } + bestAudio))
        }
        add(Quality("audio", audio, VideoEngine.AUDIO_ONLY, bestAudio))
    }
    var choice by rememberSaveable { mutableStateOf("best") }
    val templates by vm.templates.collectAsStateWithLifecycle()
    var template by rememberSaveable { mutableStateOf<Long?>(null) }
    val picked = choices.firstOrNull { it.key == choice } ?: choices.first()
    Header(
        icon = { ShapeIcon(Icons.Rounded.Movie, MaterialShapes.Cookie9Sided, FileKind.VIDEO.tone.container, FileKind.VIDEO.tone.onContainer, size = 40.dp, iconSize = 20.dp) },
        title = meta.title,
        lines = listOfNotNull(
            meta.durationSeconds.takeIf { it > 0 }?.let { formatDuration(it.toLong()) },
            picked.size.takeIf { it > 0 }?.let { stringResource(R.string.quality_size, formatBytes(it)) },
        ),
    )
    Text(stringResource(R.string.quality_title), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        choices.forEach { q ->
            FilterChip(
                selected = q.key == choice,
                onClick = { choice = q.key },
                label = { Text(q.label) },
                leadingIcon = if (q.key == "best") ({ Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }) else null,
            )
        }
    }
    if (choice == "best") {
        Text(stringResource(R.string.quality_best_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (templates.isNotEmpty()) {
        Text(stringResource(R.string.command_template), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            FilterChip(selected = template == null, onClick = { template = null }, label = { Text(stringResource(R.string.command_none)) })
            templates.forEach { t -> FilterChip(selected = template == t.id, onClick = { template = t.id }, label = { Text(t.name) }) }
        }
    }
    val args = templates.firstOrNull { it.id == template }?.args
    Actions(
        onLater = { add { vm.addVideo(url, meta.title, picked.format, args, request.headers, picked.size, false) } },
        onStart = { add { vm.addVideo(url, meta.title, picked.format, args, request.headers, picked.size, true) } },
    )
}

