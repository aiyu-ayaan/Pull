package com.aiyu.pull.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiyu.pull.R
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.theme.Dimens
import com.aiyu.pull.ui.theme.clickHaptics

/** One file of a torrent as the picker shows it. [done] is null before the download starts. */
data class PickFile(val index: Int, val path: String, val size: Long, val done: Long? = null)

/** A row of the flattened tree: a folder (with its depth) or a file. */
private sealed interface TreeRow {
    val depth: Int
    data class Dir(val path: String, val name: String, override val depth: Int, val files: List<Int>) : TreeRow
    data class File(val f: PickFile, override val depth: Int) : TreeRow
}

/**
 * Choose which files of a torrent to download, like other torrent apps: folder tree with tri-state boxes,
 * type filters that select everything of a kind, live per-file progress once running, and the selected size.
 */
@Composable
fun TorrentFilesSheet(
    name: String,
    files: List<PickFile>,
    selected: Set<Int>,
    onDismiss: () -> Unit,
    onApply: (Set<Int>) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by rememberSaveable { mutableStateOf(selected.toList()) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    val set = chosen.toSet()
    val rows = remember(files, collapsed) { flatten(files, collapsed.toSet()) }
    val kinds = remember(files) { files.groupBy { FileKind.of(it.path, null) }.filterKeys { it != FileKind.OTHER } }
    val size = files.filter { it.index in set }.sumOf { it.size }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, modifier = Modifier.clickHaptics()) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = Dimens.xl)) {
                Text(stringResource(R.string.files_title), style = MaterialTheme.typography.headlineSmall)
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Spacer(Modifier.size(Dimens.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.files_selected, set.size, files.size, formatBytes(size)),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { chosen = if (set.size == files.size) emptyList() else files.map { it.index } }) {
                        Text(stringResource(if (set.size == files.size) R.string.select_none else R.string.select_all))
                    }
                }
                if (kinds.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    kinds.forEach { (kind, list) ->
                        val ids = list.map { it.index }
                        val on = ids.all { it in set }
                        // A type chip selects or clears every file of that type (e.g. only the videos).
                        FilterChip(
                            selected = on,
                            onClick = { chosen = if (on) chosen - ids.toSet() else (chosen + ids).distinct() },
                            label = { Text("${stringResource(kindLabel(kind))} · ${list.size}") },
                        )
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = Dimens.sm)) {
                items(rows, key = { r -> if (r is TreeRow.Dir) "d:${r.path}" else "f:${(r as TreeRow.File).f.index}" }) { r ->
                    val pad = Modifier.padding(start = Dimens.md + 20.dp * r.depth, end = Dimens.lg)
                    when (r) {
                        is TreeRow.Dir -> {
                            val n = r.files.count { it in set }
                            val state = when (n) { 0 -> ToggleableState.Off; r.files.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                            val open = r.path !in collapsed
                            Row(
                                Modifier.fillMaxWidth().clickable { collapsed = if (open) collapsed + r.path else collapsed - r.path }.then(pad).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (open) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null)
                                TriStateCheckbox(state, onClick = {
                                    chosen = if (state == ToggleableState.On) chosen - r.files.toSet() else (chosen + r.files).distinct()
                                })
                                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(Dimens.sm))
                                Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("$n/${r.files.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is TreeRow.File -> {
                            val f = r.f
                            val on = f.index in set
                            val kind = FileKind.of(f.path, null)
                            Row(
                                Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - f.index else chosen + f.index }.then(pad).heightIn(min = 56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spacer(Modifier.width(24.dp))
                                TriStateCheckbox(if (on) ToggleableState.On else ToggleableState.Off, onClick = { chosen = if (on) chosen - f.index else chosen + f.index })
                                ShapeIcon(kind.icon, kind.shape, kind.tone.container, kind.tone.onContainer, size = 32.dp, iconSize = 16.dp)
                                Spacer(Modifier.width(Dimens.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                                    val done = f.done
                                    Text(
                                        if (done != null && f.size > 0) "${formatBytes(done)} / ${formatBytes(f.size)}" else formatBytes(f.size),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (done != null && f.size > 0 && on) {
                                        LinearProgressIndicator(progress = { (done.toFloat() / f.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { onApply(chosen.toSet()) },
                enabled = chosen.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.md).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.files_apply, set.size)) }
        }
    }
}

private fun kindLabel(kind: FileKind) = when (kind) {
    FileKind.VIDEO -> R.string.kind_video
    FileKind.AUDIO -> R.string.kind_audio
    FileKind.IMAGE -> R.string.kind_image
    FileKind.DOCUMENT -> R.string.kind_document
    FileKind.ARCHIVE -> R.string.kind_archive
    FileKind.APK -> R.string.kind_apk
    FileKind.OTHER -> R.string.kind_other
}

/** Paths to a depth-first tree; the torrent's own top folder is skipped when every file shares it. */
private fun flatten(files: List<PickFile>, collapsed: Set<String>): List<TreeRow> {
    val common = files.map { it.path.substringBefore('/', "") }.distinct().singleOrNull()?.takeIf { it.isNotEmpty() && files.size > 1 }
    val rel = files.map { f -> f to (if (common != null) f.path.removePrefix("$common/") else f.path) }
    val out = mutableListOf<TreeRow>()
    fun walk(prefix: String, items: List<Pair<PickFile, String>>, depth: Int) {
        val (here, deeper) = items.partition { !it.second.contains('/') }
        deeper.groupBy { it.second.substringBefore('/') }.toSortedMap().forEach { (dir, list) ->
            val path = "$prefix$dir/"
            out += TreeRow.Dir(path, dir, depth, list.map { it.first.index })
            if (path !in collapsed) walk(path, list.map { it.first to it.second.substringAfter('/') }, depth + 1)
        }
        here.sortedBy { it.second.lowercase() }.forEach { out += TreeRow.File(it.first, depth) }
    }
    walk("", rel, 0)
    return out
}
