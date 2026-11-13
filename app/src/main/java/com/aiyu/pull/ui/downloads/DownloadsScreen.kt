package com.aiyu.pull.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.data.Status
import com.aiyu.pull.engine.ErrorKind
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.MenuIcon
import com.aiyu.pull.ui.components.SpeedGraph
import com.aiyu.pull.ui.components.openFile
import com.aiyu.pull.ui.theme.NumberTextStyle
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.FilledTonalIconButton
import com.aiyu.pull.ui.navigation.LocalBottomBarInsets
import com.aiyu.pull.ui.navigation.Tab
import com.aiyu.pull.ui.components.MorphingShape
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.heroBrush
import com.aiyu.pull.ui.components.readableGutter
import com.aiyu.pull.ui.components.rememberGlide
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.formatDuration
import com.aiyu.pull.ui.formatSpeed
import com.aiyu.pull.ui.speakSpeed
import com.aiyu.pull.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Filter options under the title: file type or origin app. */
enum class FilterType(val labelRes: Int) {
    ALL(R.string.kind_all),
    INSTAGRAM(R.string.filter_instagram),
    YOUTUBE(R.string.filter_youtube),
    VIDEO(R.string.kind_video),
    AUDIO(R.string.kind_audio),
    DOCUMENT(R.string.kind_document),
    ARCHIVE(R.string.kind_archive),
    APK(R.string.kind_apk),
    IMAGE(R.string.kind_image),
    OTHER(R.string.kind_other);

    fun matches(e: com.aiyu.pull.data.DownloadEntity): Boolean = when (this) {
        ALL -> true
        INSTAGRAM -> e.url.contains("instagram.com", ignoreCase = true) ||
            e.url.contains("instagr.am", ignoreCase = true) ||
            e.pageUrl?.contains("instagram.com", ignoreCase = true) == true ||
            e.pageUrl?.contains("instagr.am", ignoreCase = true) == true
        YOUTUBE -> e.url.contains("youtube.com", ignoreCase = true) ||
            e.url.contains("youtu.be", ignoreCase = true) ||
            e.pageUrl?.contains("youtube.com", ignoreCase = true) == true ||
            e.pageUrl?.contains("youtu.be", ignoreCase = true) == true
        VIDEO -> FileKind.of(e.fileName, e.mimeType) == FileKind.VIDEO
        AUDIO -> FileKind.of(e.fileName, e.mimeType) == FileKind.AUDIO
        DOCUMENT -> FileKind.of(e.fileName, e.mimeType) == FileKind.DOCUMENT
        ARCHIVE -> FileKind.of(e.fileName, e.mimeType) == FileKind.ARCHIVE
        APK -> FileKind.of(e.fileName, e.mimeType) == FileKind.APK
        IMAGE -> FileKind.of(e.fileName, e.mimeType) == FileKind.IMAGE
        OTHER -> FileKind.of(e.fileName, e.mimeType) == FileKind.OTHER
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    vm: DownloadsViewModel,
    tab: Tab,
    onOpen: (Long) -> Unit,
    onAdd: (fromClipboard: Boolean) -> Unit,
    onBrowser: () -> Unit,
    onOpenDrawer: () -> Unit,
    drawerProgress: () -> Float,
    selectedId: Long?,
    modifier: Modifier = Modifier,
) {
    val rows by vm.downloads.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(FilterType.ALL) }
    var selection by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var askDelete by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    val haptics = LocalHapticFeedback.current
    val inSelection = selection.isNotEmpty()
    val insets = LocalBottomBarInsets.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val fastSpatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

    BackHandler(inSelection) { selection = emptySet() }
    BackHandler(menuOpen && !inSelection) { menuOpen = false }

    val views = rows.orEmpty().map { DownloadView(it, live[it.id]) }
    val shown = views.filter { tab.matches(it.entity.status) && filter.matches(it.entity) }
    val active = views.count { it.running }
    val totalSpeed = views.sumOf { it.speed }

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = inSelection,
                transitionSpec = {
                    (fadeIn(animationSpec = fastEffects) +
                        scaleIn(animationSpec = fastSpatial, initialScale = 0.95f))
                        .togetherWith(
                            fadeOut(animationSpec = fastEffects) +
                                scaleOut(animationSpec = fastSpatial, targetScale = 0.95f)
                        )
                },
                label = "topBar"
            ) { selecting ->
                if (selecting) {
                    TopAppBar(
                        title = {
                            Text(
                                pluralStringResource(R.plurals.selected_count, selection.size, selection.size),
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { selection = emptySet() }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear_selection))
                            }
                        },
                        actions = {
                            val allSelected = shown.isNotEmpty() && selection.size == shown.size
                            IconButton(
                                onClick = {
                                    selection = if (allSelected) emptySet() else shown.map { it.id }.toSet()
                                },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                                    stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all)
                                )
                            }
                            IconButton(
                                onClick = { askDelete = selection.toList() },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        title = { Text(stringResource(tab.title)) },
                        navigationIcon = {
                            IconButton(onClick = onOpenDrawer, shapes = IconButtonDefaults.shapes()) {
                                MenuIcon(drawerProgress, Modifier.semantics { contentDescription = context.getString(R.string.cd_open_drawer) })
                            }
                        },
                        subtitle = {
                            AnimatedContent(active > 0, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") { busy ->
                                if (busy) {
                                    val speed by rememberGlide(totalSpeed)
                                    Text(
                                        pluralStringResource(R.plurals.downloads_subtitle_active, active, formatSpeed(speed), active),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.semantics { contentDescription = speakSpeed(totalSpeed) },
                                    )
                                } else {
                                    val n = views.count { tab.matches(it.entity.status) }
                                    Text(pluralStringResource(R.plurals.downloads_subtitle_idle, n, n))
                                }
                            }
                        },
                        actions = {
                            val anyRunning = views.any { it.running || it.entity.status == Status.QUEUED }
                            val anyPaused = views.any { it.entity.status == Status.PAUSED }
                            if (anyRunning) IconButton(onClick = vm::pauseAll, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.PauseCircle, stringResource(R.string.action_pause_all))
                            } else if (anyPaused) IconButton(onClick = vm::resumeAll, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.PlayCircle, stringResource(R.string.action_resume_all))
                            }
                        },
                        scrollBehavior = scroll,
                    )
                }
            }
        },
        floatingActionButton = {
            if (!inSelection) {
                FloatingActionButtonMenu(
                    modifier = Modifier.padding(bottom = insets.fab),
                    expanded = menuOpen,
                    button = {
                        ToggleFloatingActionButton(
                            checked = menuOpen,
                            onCheckedChange = { menuOpen = it },
                            modifier = Modifier.semantics {
                                traversalIndex = -1f
                                stateDescription = if (menuOpen) "expanded" else "collapsed"
                            },
                        ) {
                            val icon = if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add
                            Icon(icon, stringResource(R.string.cd_add_menu), Modifier.animateIcon({ checkedProgress }))
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = { menuOpen = false; onAdd(false) },
                        icon = { Icon(Icons.Rounded.Add, null) },
                        text = { Text(stringResource(R.string.fab_add_url)) },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = { menuOpen = false; onAdd(true) },
                        icon = { Icon(Icons.Rounded.ContentPaste, null) },
                        text = { Text(stringResource(R.string.fab_paste)) },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = { menuOpen = false; onBrowser() },
                        icon = { Icon(Icons.Rounded.Public, null) },
                        text = { Text(stringResource(R.string.fab_browser)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = readableGutter()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                FilterType.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(stringResource(f.labelRes)) }
                    )
                }
            }
            if (rows != null && shown.isEmpty()) {
                EmptyState(tab, filter == FilterType.ALL, Modifier.weight(1f).padding(bottom = insets.content))
                return@Column
            }
            LazyColumn(
                contentPadding = PaddingValues(
                    start = readableGutter(), end = readableGutter(), top = Dimens.sm,
                    bottom = padding.calculateBottomPadding() + insets.content + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (tab == Tab.ACTIVE && active > 0) item(key = "live") {
                    LiveHero(views.filter { it.running }, Modifier.animateItem())
                }
                val groups = if (tab == Tab.DONE) shown.groupBy { dayLabel(context, it.entity.finishedAt ?: it.entity.createdAt) } else mapOf("" to shown)
                groups.forEach { (day, group) ->
                    if (day.isNotEmpty()) item(key = "day:$day") {
                        Text(
                            day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.animateItem().padding(start = Dimens.sm, top = Dimens.md),
                        )
                    }
                    items(group, key = { it.id }) { view ->
                        SwipeToDelete(
                            enabled = !inSelection,
                            onDelete = { askDelete = listOf(view.id) },
                            modifier = Modifier.animateItem(),
                        ) {
                            DownloadRow(
                                view = view,
                                selected = view.id == selectedId,
                                inSelection = inSelection,
                                checked = view.id in selection,
                                onClick = {
                                    if (inSelection) {
                                        selection = if (view.id in selection) selection - view.id else selection + view.id
                                    } else {
                                        onOpen(view.id)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selection = if (view.id in selection) selection - view.id else selection + view.id
                                },
                                onToggle = { if (view.running || view.entity.status == Status.QUEUED) vm.pause(view.id) else vm.resume(view.id) },
                                onOpenFile = {
                                    if (!openFile(context, vm.files, view.entity)) scope.launch { snackbar.showSnackbar(context.getString(R.string.no_app_to_open)) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    askDelete?.let { ids ->
        val targetViews = views.filter { it.id in ids }
        var withFile by rememberSaveable(ids) { mutableStateOf(targetViews.any { it.entity.status == Status.COMPLETED }) }
        AlertDialog(
            onDismissRequest = { askDelete = null },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(
                    if (ids.size == 1) stringResource(R.string.delete_title)
                    else pluralStringResource(R.plurals.delete_title_count, ids.size, ids.size)
                )
            },
            text = {
                Column {
                    Text(
                        if (ids.size == 1) {
                            targetViews.firstOrNull()?.entity?.fileName.orEmpty()
                        } else {
                            pluralStringResource(R.plurals.delete_body_count, ids.size, ids.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Dimens.md))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { withFile = !withFile }
                            .padding(vertical = Dimens.xs)
                    ) {
                        Checkbox(checked = withFile, onCheckedChange = { withFile = it })
                        Spacer(Modifier.width(Dimens.sm))
                        Text(stringResource(R.string.delete_file_too), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ids.forEach { id -> vm.delete(id, withFile) }
                        selection = selection - ids.toSet()
                        askDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(
    enabled: Boolean = true,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            if (!enabled) return@SwipeToDismissBox
            val color by animateColorAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainer,
                label = "swipe",
            )
            Box(
                Modifier.fillMaxSize().background(color, MaterialTheme.shapes.large).padding(horizontal = Dimens.xl),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
        modifier = modifier,
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    view: DownloadView,
    selected: Boolean,
    inSelection: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
    onOpenFile: () -> Unit
) {
    val e = view.entity
    val kind = FileKind.of(e.fileName, e.mimeType)
    val tone = kind.tone
    val c = MaterialTheme.colorScheme
    val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val fastSpatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val rowColor by animateColorAsState(
        targetValue = when {
            checked -> c.secondaryContainer
            selected -> c.secondaryContainer
            else -> c.surfaceContainerLow
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "rowColor"
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = rowColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
    ) {
        Column(Modifier.padding(horizontal = Dimens.lg, vertical = Dimens.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = inSelection,
                    transitionSpec = {
                        (fadeIn(animationSpec = fastEffects) +
                            scaleIn(animationSpec = fastSpatial, initialScale = 0.8f))
                            .togetherWith(
                                fadeOut(animationSpec = fastEffects) +
                                    scaleOut(animationSpec = fastSpatial, targetScale = 0.8f)
                            )
                    },
                    label = "leadingCheckbox"
                ) { selecting ->
                    if (selecting) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                            )
                        }
                    } else {
                        val thumbUrl = e.headers["X-Pull-Thumbnail"]
                        if (!thumbUrl.isNullOrBlank()) {
                            com.aiyu.pull.ui.components.RemoteThumbnail(
                                url = thumbUrl,
                                modifier = Modifier.size(44.dp),
                                shape = MaterialTheme.shapes.small,
                                placeholder = {
                                    ShapeIcon(kind.icon, kind.shape, tone.container, tone.onContainer, size = 44.dp, iconSize = 22.dp)
                                }
                            )
                        } else {
                            ShapeIcon(kind.icon, kind.shape, tone.container, tone.onContainer, size = 44.dp, iconSize = 22.dp)
                        }
                    }
                }
                Spacer(Modifier.width(Dimens.md))
                Column(Modifier.weight(1f)) {
                    Text(e.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    StatusLine(view)
                }
                AnimatedVisibility(
                    visible = !inSelection,
                    enter = fadeIn(fastEffects) + scaleIn(fastSpatial, initialScale = 0.8f),
                    exit = fadeOut(fastEffects) + scaleOut(fastSpatial, targetScale = 0.8f)
                ) {
                    when (e.status) {
                        Status.RUNNING, Status.QUEUED, Status.PAUSED -> {
                            val on = e.status != Status.PAUSED
                            FilledTonalIconToggleButton(
                                checked = on,
                                onCheckedChange = { onToggle() },
                                shapes = IconButtonDefaults.toggleableShapes(),
                            ) {
                                Icon(
                                    if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    stringResource(if (on) R.string.action_pause else R.string.action_resume),
                                )
                            }
                        }
                        Status.FAILED -> IconButton(onClick = onToggle, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.action_retry))
                        }
                        Status.COMPLETED -> FilledTonalIconButton(onClick = onOpenFile, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_open))
                        }
                    }
                }
            }
            if (e.status != Status.COMPLETED) {
                Spacer(Modifier.height(Dimens.md))
                RowProgress(view)
            }
        }
    }
}

@Composable
private fun RowProgress(view: DownloadView) {
    val glided by rememberGlide(view.downloaded)
    val total = view.total
    val fraction = if (total > 0) (glided.toFloat() / total).coerceIn(0f, 1f) else 0f
    val m = Modifier.fillMaxWidth()
    when {
        view.running && total <= 0 -> LinearWavyProgressIndicator(m)
        view.running -> LinearWavyProgressIndicator(progress = { fraction }, modifier = m)
        view.entity.status == Status.FAILED -> LinearProgressIndicator(
            progress = { fraction }, modifier = m,
            color = MaterialTheme.colorScheme.error, trackColor = MaterialTheme.colorScheme.errorContainer,
        )
        else -> LinearProgressIndicator(progress = { fraction }, modifier = m.alpha(if (view.entity.status == Status.QUEUED) 0.6f else 1f))
    }
}

@Composable
private fun StatusLine(view: DownloadView) {
    val e = view.entity
    val c = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    val downloaded by rememberGlide(view.downloaded)
    val sizes = if (view.total > 0) "${formatBytes(downloaded)} / ${formatBytes(view.total)}" else formatBytes(downloaded)
    when (e.status) {
        Status.RUNNING -> {
            val speed by rememberGlide(view.speed)
            val parts = listOfNotNull(
                sizes,
                formatSpeed(speed),
                view.eta?.let { stringResource(R.string.eta_left, formatDuration(it)) },
            )
            Text(parts.joinToString(" · "), style = style, color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Status.QUEUED -> Text(stringResource(R.string.status_queued_line, sizes), style = style, color = c.onSurfaceVariant)
        Status.PAUSED -> Text(stringResource(R.string.status_paused_line, sizes), style = style, color = c.onSurfaceVariant)
        Status.COMPLETED if view.seeding -> Text(
            stringResource(R.string.status_seeding_line, formatBytes(e.totalBytes), formatSpeed(view.live?.torrent?.uploadBytesPerSecond ?: 0)),
            style = style, color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Status.COMPLETED -> Text(formatBytes(e.totalBytes.coerceAtLeast(e.downloadedBytes)), style = style, color = c.onSurfaceVariant)
        Status.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
            val expired = e.errorKind == ErrorKind.LINK_EXPIRED.name
            Icon(
                if (expired) Icons.Rounded.LinkOff else Icons.Rounded.ErrorOutline, null,
                tint = c.error, modifier = Modifier.size(16.dp).padding(end = 2.dp),
            )
            Text(
                if (expired) stringResource(R.string.status_link_expired) else e.error ?: stringResource(R.string.status_failed),
                style = style, color = c.error, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState(tab: Tab, everything: Boolean, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(Dimens.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MorphingShape(brush = heroBrush(), modifier = Modifier.size(120.dp))
        Spacer(Modifier.height(Dimens.xl))
        Text(
            stringResource(
                when {
                    !everything -> R.string.empty_filtered
                    tab == Tab.DONE -> R.string.empty_done_title
                    else -> R.string.empty_title
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (everything && tab == Tab.ACTIVE) {
            Spacer(Modifier.height(Dimens.sm))
            Text(
                stringResource(R.string.empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Top of the Active screen: everything running, summed. One big live number, the minute's speed curve and the
 * combined wavy bar. Desktop download managers make speed the headline; so does this.
 */
@Composable
private fun LiveHero(running: List<DownloadView>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val speed by rememberGlide(running.sumOf { it.speed })
    val downloaded by rememberGlide(running.sumOf { it.downloaded })
    val total = if (running.any { it.total <= 0 }) -1 else running.sumOf { it.total }
    val connections = running.sumOf { v -> v.live?.segments?.count { it.active } ?: 0 }
    // Per-second speed of all downloads added together, aligned on "now".
    val histories = running.mapNotNull { it.live?.history }
    val length = histories.maxOfOrNull { it.size } ?: 0
    val history = List(length) { i -> histories.sumOf { h -> h.getOrNull(h.size - length + i) ?: 0L } }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = c.primaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.xl)) {
            Text(
                formatSpeed(speed),
                style = NumberTextStyle,
                color = c.onPrimaryContainer,
                modifier = Modifier.semantics { contentDescription = speakSpeed(speed) },
            )
            Text(
                pluralStringResource(R.plurals.live_summary, running.size, running.size, connections),
                style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer,
            )
            Spacer(Modifier.height(Dimens.md))
            SpeedGraph(history)
            Spacer(Modifier.height(Dimens.md))
            if (total > 0) {
                LinearWavyProgressIndicator(progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    stringResource(R.string.details_of, formatBytes(downloaded), formatBytes(total)),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = c.onPrimaryContainer,
                )
            } else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun dayLabel(context: android.content.Context, millis: Long): String = when {
    android.text.format.DateUtils.isToday(millis) -> context.getString(R.string.day_today)
    android.text.format.DateUtils.isToday(millis + android.text.format.DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.day_yesterday)
    else -> android.text.format.DateUtils.formatDateTime(context, millis, android.text.format.DateUtils.FORMAT_SHOW_DATE)
}
