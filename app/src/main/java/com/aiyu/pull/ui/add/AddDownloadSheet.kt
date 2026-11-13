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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.UploadFile
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.aiyu.pull.data.DownloadEntity
import com.aiyu.pull.data.Kind
import com.aiyu.pull.data.Status
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
fun AddDownloadSheet(
    request: AddRequest,
    vm: AddViewModel,
    onDismiss: () -> Unit,
    onAdded: (Long) -> Unit,
    onOpenExisting: ((Long) -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Saveable: a process death while the sheet is open brings the same input back.
    var url by rememberSaveable { mutableStateOf(request.url) }
    var mirrors by rememberSaveable { mutableStateOf("") }
    var checksum by rememberSaveable { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    val batchUrls = remember(url) { expandUrlPattern(url) }
    val isBatch = request.torrentFile == null && batchUrls.size > 1
    val state by vm.state.collectAsStateWithLifecycle()

    val importTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val parsed = parseUrlListText(text.orEmpty())
            if (parsed.isNotEmpty()) {
                url = parsed.joinToString("\n")
            }
        }
    }

    LaunchedEffect(url, request.torrentFile, isBatch) {
        if (isBatch) {
            vm.reset()
            return@LaunchedEffect
        }
        if (request.torrentFile == null) delay(400) // typing debounce
        val targetUrl = batchUrls.singleOrNull() ?: url.trim()
        vm.analyze(targetUrl, request.headers, request.torrentFile)
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Same link or torrent already in the list: ask before downloading it a second time.
    var duplicate by remember { mutableStateOf<DownloadEntity?>(null) }
    LaunchedEffect(state) { duplicate = vm.duplicateOf(state, url.trim()) }
    var pending by remember { mutableStateOf<(suspend () -> Long)?>(null) }
    val start: (suspend () -> Long) -> Unit = { block ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        scope.launch {
            val id = block()
            sheet.hide()
            onAdded(id)
        }
    }
    val add: (suspend () -> Long) -> Unit = { block -> if (duplicate != null) pending = block else start(block) }
    val dup = duplicate
    pending?.let { block ->
        if (dup != null) DuplicateDialog(
            existing = dup,
            onAgain = { pending = null; start(block) },
            onShow = onOpenExisting?.let { open -> { pending = null; scope.launch { sheet.hide(); open(dup.id) } } },
            onDismiss = { pending = null },
        )
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
                    placeholder = { Text("https://  ·  part[01-05].zip  ·  magnet:?") },
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    isError = !isBatch && state is Analysis.Failed,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalButton(
                        onClick = { importTxt.launch(arrayOf("text/plain")) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(Dimens.sm))
                        Text("Import .txt URL list")
                    }
                    if (isBatch) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "Batch: ${batchUrls.size} URLs",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm),
                            )
                        }
                    }
                }
                AdvancedOptionsSection(
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                    mirrors = mirrors,
                    onMirrorsChange = { mirrors = it },
                    checksum = checksum,
                    onChecksumChange = { checksum = it },
                )
            }
            if (isBatch) {
                BatchBody(
                    urls = batchUrls,
                    request = request,
                    vm = vm,
                    mirrors = mirrors,
                    checksum = checksum,
                    start = start,
                )
            } else {
                AnimatedContent(state, contentKey = { it::class }, label = "analysis") { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.lg)) {
                        when (s) {
                            Analysis.Idle -> Hint(stringResource(R.string.probe_idle_any))
                            is Analysis.Loading -> Busy(stringResource(s.message))
                            is Analysis.Failed -> Problem(s.message)
                            is Analysis.FileLink -> FileBody(s, batchUrls.singleOrNull() ?: url.trim(), request, vm, mirrors, checksum, add)
                            is Analysis.WebPage -> WebPageBody(onFind = { vm.findVideos(url.trim(), request.headers) })
                            is Analysis.Torrent -> TorrentBody(s, vm, add)
                            is Analysis.Video -> VideoBody(s.meta, url.trim(), request, vm, add)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateDialog(existing: DownloadEntity, onAgain: () -> Unit, onShow: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // libtorrent runs one copy of a torrent at a time: an unfinished one can only be shown, not started twice.
    val canRepeat = existing.kind != Kind.TORRENT || existing.status == Status.COMPLETED
    val added = android.text.format.DateUtils.getRelativeTimeSpanString(existing.createdAt).toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.ContentCopy, null) },
        title = { Text(stringResource(R.string.dup_title)) },
        text = {
            Text(
                buildString {
                    append(context.getString(R.string.dup_body, existing.fileName, added, context.getString(statusLabel(existing.status)).lowercase()))
                    append("\n\n")
                    append(context.getString(if (!canRepeat) R.string.dup_torrent_running else if (existing.kind == Kind.TORRENT) R.string.dup_torrent_again else R.string.dup_numbered))
                },
            )
        },
        confirmButton = {
            if (canRepeat) TextButton(onClick = onAgain) { Text(stringResource(R.string.dup_again)) }
            else if (onShow != null) TextButton(onClick = onShow) { Text(stringResource(R.string.dup_show)) }
        },
        dismissButton = {
            Row {
                if (canRepeat && onShow != null) TextButton(onClick = onShow) { Text(stringResource(R.string.dup_show)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

private fun statusLabel(s: Status) = when (s) {
    Status.QUEUED -> R.string.status_queued
    Status.RUNNING -> R.string.dup_status_running
    Status.PAUSED -> R.string.status_paused
    Status.COMPLETED -> R.string.status_completed
    Status.FAILED -> R.string.status_failed
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
internal fun Header(icon: @Composable () -> Unit, title: String, lines: List<String>, thumbnailUrl: String? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.lg), verticalAlignment = Alignment.CenterVertically) {
            if (!thumbnailUrl.isNullOrBlank()) {
                com.aiyu.pull.ui.components.RemoteThumbnail(
                    url = thumbnailUrl,
                    modifier = Modifier.size(width = 68.dp, height = 52.dp),
                    shape = MaterialTheme.shapes.small,
                    placeholder = icon,
                )
            } else {
                icon()
            }
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
internal fun Actions(onLater: () -> Unit, onStart: () -> Unit, enabled: Boolean = true) {
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
private fun AdvancedOptionsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    mirrors: String,
    onMirrorsChange: (String) -> Unit,
    checksum: String,
    onChecksumChange: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(Dimens.md),
            ) {
                Text(
                    text = "Advanced (Mirrors & Checksum)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                    modifier = Modifier.padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md),
                ) {
                    OutlinedTextField(
                        value = mirrors,
                        onValueChange = onMirrorsChange,
                        label = { Text("Mirror URLs (one per line)") },
                        placeholder = { Text("https://mirror.example.com/file.zip") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = checksum,
                        onValueChange = onChecksumChange,
                        label = { Text("Expected Checksum (MD5 / SHA-256)") },
                        placeholder = { Text("MD5 (32 hex) or SHA-256 (64 hex)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.BatchBody(
    urls: List<String>,
    request: AddRequest,
    vm: AddViewModel,
    mirrors: String,
    checksum: String,
    start: (suspend () -> Long) -> Unit,
) {
    val defaults by vm.connections.collectAsStateWithLifecycle()
    var connections by rememberSaveable { mutableFloatStateOf(-1f) }
    if (connections < 0 && defaults > 0) connections = defaults.toFloat()
    val kind = FileKind.of(urls.first(), null)
    Header(
        icon = { ShapeIcon(kind.icon, kind.shape, kind.tone.container, kind.tone.onContainer, size = 40.dp, iconSize = 20.dp) },
        title = "Batch: ${urls.size} URLs",
        lines = listOf(urls.first(), if (urls.size > 1) "… ${urls.last()}" else "").filter { it.isNotEmpty() },
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.add_connections), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${connections.toInt().coerceAtLeast(1)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = connections.coerceIn(1f, 32f), onValueChange = { connections = it }, valueRange = 1f..32f, steps = 30)
    }
    val go = { startNow: Boolean ->
        start { vm.addBatch(urls, request.pageUrl, request.headers, connections.toInt(), startNow, mirrors, checksum) }
    }
    Actions(onLater = { go(false) }, onStart = { go(true) })
}

@Composable
private fun ColumnScope.FileBody(
    s: Analysis.FileLink,
    url: String,
    request: AddRequest,
    vm: AddViewModel,
    mirrors: String,
    checksum: String,
    add: (suspend () -> Long) -> Unit,
) {
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
        add { vm.addFile(url, name, request.pageUrl, request.headers, connections.toInt(), start, mirrors, checksum) }
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


