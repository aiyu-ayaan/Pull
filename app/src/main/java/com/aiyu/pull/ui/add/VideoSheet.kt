package com.aiyu.pull.ui.add

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.formatDuration
import com.aiyu.pull.ui.theme.Dimens
import com.aiyu.pull.video.VideoFormat
import com.aiyu.pull.video.VideoMeta
import com.aiyu.pull.video.VideoMode
import com.aiyu.pull.video.VideoOptions

/** What the sheet downloads: yt-dlp with the preset or picked formats, audio only, or a saved command. */
private enum class SheetType { VIDEO, AUDIO, COMMAND }

/**
 * The video part of the add popup, laid out like Seal's download sheet: download type, then Preset (saved defaults,
 * quality changeable here) or Custom (exact formats), then extra options. Defaults come from Settings → Video.
 */
@Composable
internal fun ColumnScope.VideoBody(meta: VideoMeta, url: String, request: AddRequest, vm: AddViewModel, add: (suspend () -> Long) -> Unit) {
    val defaults by vm.videoDefaults.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf(SheetType.VIDEO) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var maxRes by rememberSaveable(defaults.maxRes) { mutableIntStateOf(defaults.maxRes) }
    var subtitles by rememberSaveable(defaults.subtitles) { mutableStateOf(defaults.subtitles) }
    var thumbnail by rememberSaveable(defaults.thumbnail) { mutableStateOf(defaults.thumbnail) }
    var sponsor by rememberSaveable(defaults.sponsorBlock) { mutableStateOf(defaults.sponsorBlock) }
    var template by rememberSaveable { mutableStateOf<Long?>(null) }
    var extrasOpen by rememberSaveable { mutableStateOf(false) }

    val resolutions = remember(meta) { meta.formats.filter { it.hasVideo && it.res > 0 }.map { it.res }.distinct().sortedDescending() }
    val bestAudio = remember(meta) { meta.formats.filter { it.hasAudio && !it.hasVideo }.maxOfOrNull { it.sizeBytes } ?: 0 }
    val audioOnly = type == SheetType.AUDIO
    val choice = defaults.copy(
        mode = if (audioOnly) VideoMode.AUDIO else VideoMode.VIDEO,
        maxRes = maxRes, subtitles = subtitles, thumbnail = thumbnail, sponsorBlock = sponsor,
    )
    val chosenTemplate = templates.firstOrNull { it.id == template } ?: templates.firstOrNull()
    val (format, args) = when (type) {
        SheetType.COMMAND -> com.aiyu.pull.video.VideoEngine.BEST to chosenTemplate?.args
        else -> VideoOptions.build(choice, picked.takeIf { custom })
    }
    val size = when {
        custom && picked != null -> picked!!.split('+').sumOf { id -> meta.formats.firstOrNull { it.id == id }?.sizeBytes ?: 0 }
        audioOnly -> bestAudio
        else -> (meta.formats.filter { it.hasVideo && (maxRes == 0 || it.res <= maxRes) }.maxOfOrNull { it.sizeBytes } ?: 0) + bestAudio
    }

    Header(
        icon = { ShapeIcon(Icons.Rounded.Movie, MaterialShapes.Cookie9Sided, FileKind.VIDEO.tone.container, FileKind.VIDEO.tone.onContainer, size = 40.dp, iconSize = 20.dp) },
        title = meta.title,
        lines = listOfNotNull(
            meta.durationSeconds.takeIf { it > 0 }?.let { formatDuration(it.toLong()) },
            size.takeIf { it > 0 && type != SheetType.COMMAND }?.let { stringResource(R.string.quality_size, formatBytes(it)) },
        ),
        thumbnailUrl = meta.thumbnail,
    )

    Label(R.string.video_type)
    val types = SheetType.entries.filter { it != SheetType.COMMAND || templates.isNotEmpty() }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        types.forEachIndexed { i, t ->
            SegmentedButton(
                selected = type == t,
                onClick = { type = t; if (t == SheetType.AUDIO) picked = null },
                shape = SegmentedButtonDefaults.itemShape(i, types.size),
                icon = { SegmentedButtonDefaults.Icon(type == t) { Icon(t.icon(), null, Modifier.size(SegmentedButtonDefaults.IconSize)) } },
            ) { Text(stringResource(t.label())) }
        }
    }

    if (type == SheetType.COMMAND) {
        Label(R.string.command_template)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            templates.forEach { t -> FilterChip(selected = chosenTemplate?.id == t.id, onClick = { template = t.id }, label = { Text(t.name) }) }
        }
        chosenTemplate?.let { Text(it.args, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        Label(R.string.video_format_selection)
        ChoiceCard(
            selected = !custom, icon = Icons.Rounded.AutoAwesome,
            title = stringResource(R.string.video_preset),
            body = presetSummary(choice),
            onClick = { custom = false },
        ) {
            if (!audioOnly && !custom && resolutions.isNotEmpty()) Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = Dimens.sm),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                // The card is already secondaryContainer, the selected chip's default: fill it with primary to stand out.
                val colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                )
                FilterChip(selected = maxRes == 0, onClick = { maxRes = 0 }, colors = colors, label = { Text(stringResource(R.string.quality_best)) })
                resolutions.forEach { r -> FilterChip(selected = maxRes == r, onClick = { maxRes = r }, colors = colors, label = { Text("${r}p") }) }
            }
        }
        ChoiceCard(
            selected = custom, icon = Icons.Rounded.Tune,
            title = stringResource(R.string.video_custom),
            body = picked?.takeIf { custom }?.let { describe(it, meta) } ?: stringResource(R.string.video_custom_hint),
            onClick = { custom = true; picking = true },
        )
    }

    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).selectable(extrasOpen, role = Role.Button) { extrasOpen = !extrasOpen },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.video_extras), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (extrasOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
    }
    AnimatedVisibility(extrasOpen) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            val enabled = type != SheetType.COMMAND
            FilterChip(selected = subtitles, enabled = enabled && !audioOnly, onClick = { subtitles = !subtitles }, label = { Text(stringResource(R.string.opt_subtitles)) })
            FilterChip(selected = thumbnail, enabled = enabled, onClick = { thumbnail = !thumbnail }, label = { Text(stringResource(R.string.opt_thumbnail)) })
            FilterChip(selected = sponsor, enabled = enabled, onClick = { sponsor = !sponsor }, label = { Text(stringResource(R.string.opt_sponsorblock)) })
        }
    }

    val headersWithThumb = if (!meta.thumbnail.isNullOrBlank()) {
        request.headers + ("X-Pull-Thumbnail" to meta.thumbnail)
    } else {
        request.headers
    }

    Actions(
        enabled = !(custom && picked == null && type != SheetType.COMMAND),
        onLater = { add { vm.addVideo(url, meta.title, format, args?.ifBlank { null }, headersWithThumb, size, false) } },
        onStart = { add { vm.addVideo(url, meta.title, format, args?.ifBlank { null }, headersWithThumb, size, true) } },
    )

    if (picking) FormatPicker(
        meta = meta, audioOnly = audioOnly, initial = picked,
        onDismiss = { picking = false; if (picked == null) custom = false },
        onPick = { picked = it; picking = false },
    )
}

private fun SheetType.label() = when (this) {
    SheetType.VIDEO -> R.string.kind_video
    SheetType.AUDIO -> R.string.kind_audio
    SheetType.COMMAND -> R.string.video_command
}

private fun SheetType.icon() = when (this) {
    SheetType.VIDEO -> Icons.Rounded.Movie
    SheetType.AUDIO -> Icons.Rounded.MusicNote
    SheetType.COMMAND -> Icons.Rounded.Terminal
}

@Composable
private fun Label(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.titleSmall)

/** "1080p · H.264 · MP4 · subtitles" from the current choice. */
@Composable
private fun presetSummary(c: com.aiyu.pull.video.VideoChoice): String = buildList {
    if (c.mode == VideoMode.AUDIO) {
        add(stringResource(R.string.quality_audio))
        add(stringResource(when (c.audio) {
            com.aiyu.pull.video.AudioFormat.BEST -> R.string.audio_best
            com.aiyu.pull.video.AudioFormat.M4A -> R.string.audio_m4a
            com.aiyu.pull.video.AudioFormat.MP3 -> R.string.audio_mp3
            com.aiyu.pull.video.AudioFormat.OPUS -> R.string.audio_opus
        }))
    } else {
        add(if (c.maxRes > 0) "${c.maxRes}p" else stringResource(R.string.quality_best))
        if (c.codec != com.aiyu.pull.video.VideoCodec.ANY) add(c.codec.name.replace("AVC", "H.264"))
        if (c.container != com.aiyu.pull.video.Container.AUTO) add(c.container.name)
        if (c.subtitles) add(stringResource(R.string.opt_subtitles).lowercase())
    }
}.joinToString(" · ")

private fun describe(ids: String, meta: VideoMeta): String =
    ids.split('+').mapNotNull { id -> meta.formats.firstOrNull { it.id == id } }.joinToString(" + ") { headline(it) }

/** A selectable rounded card; the selected one is tinted and rounder (Seal's single-choice item). */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val radius by animateDpAsState(if (selected) 28.dp else 16.dp, label = "radius")
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(radius),
        color = if (selected) c.secondaryContainer else c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(Dimens.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(Dimens.md))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(icon, null, tint = c.onSurfaceVariant)
            }
            extra()
        }
    }
}

/**
 * Seal's format page: pick one video-only stream plus one audio stream (merged), or one stream that has both.
 * Returns yt-dlp format ids, e.g. `137+140`.
 */
@Composable
private fun FormatPicker(meta: VideoMeta, audioOnly: Boolean, initial: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ids = initial?.split('+').orEmpty()
    var video by rememberSaveable { mutableStateOf(ids.firstOrNull { id -> meta.formats.any { it.id == id && it.hasVideo && !it.hasAudio } }) }
    var audio by rememberSaveable { mutableStateOf(ids.firstOrNull { id -> meta.formats.any { it.id == id && it.hasAudio && !it.hasVideo } }) }
    var both by rememberSaveable { mutableStateOf(ids.firstOrNull { id -> meta.formats.any { it.id == id && it.hasAudio && it.hasVideo } }) }
    val byQuality = compareByDescending<VideoFormat> { it.res }.thenByDescending { it.fps }.thenByDescending { it.tbr }
    val videoOnly = meta.formats.filter { it.hasVideo && !it.hasAudio }.sortedWith(byQuality)
    val combined = meta.formats.filter { it.hasVideo && it.hasAudio }.sortedWith(byQuality)
    val audios = meta.formats.filter { it.hasAudio && !it.hasVideo }.sortedByDescending { it.abr.takeIf { a -> a > 0 } ?: it.tbr }
    val result = both ?: listOfNotNull(video.takeIf { !audioOnly }, audio).joinToString("+").ifEmpty { null }
    val c = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Text(
                stringResource(R.string.video_format_selection), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Dimens.xl),
            )
            Text(
                stringResource(R.string.video_format_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(Dimens.lg),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                modifier = Modifier.weight(1f),
            ) {
                fun section(title: Int, list: List<VideoFormat>, container: Color, isOn: (VideoFormat) -> Boolean, onTap: (VideoFormat) -> Unit) {
                    if (list.isEmpty()) return
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$title") {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = c.primary, modifier = Modifier.padding(top = Dimens.sm))
                    }
                    items(list, key = { "$title-${it.id}" }) { f -> FormatCard(f, isOn(f), container) { onTap(f) } }
                }
                section(R.string.formats_audio, audios, c.secondaryContainer, { it.id == audio }) { f ->
                    audio = if (audio == f.id) null else f.id; both = null
                }
                if (!audioOnly) {
                    section(R.string.formats_video_only, videoOnly, c.tertiaryContainer, { it.id == video }) { f ->
                        video = if (video == f.id) null else f.id; both = null
                    }
                    section(R.string.formats_video_audio, combined, c.surfaceContainerHigh, { it.id == both }) { f ->
                        both = if (both == f.id) null else f.id; video = null; audio = null
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.md), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { result?.let(onPick) }, enabled = result != null, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text(result?.let { describe(it, meta) } ?: stringResource(R.string.formats_pick)) }
            }
        }
    }
}

private fun headline(f: VideoFormat): String = when {
    f.hasVideo -> "${f.res}p" + (if (f.fps > 30) "${f.fps}" else "")
    f.abr > 0 -> "${f.abr} kbps"
    else -> f.label
}

@Composable
private fun FormatCard(f: VideoFormat, selected: Boolean, container: Color, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        border = if (selected) BorderStroke(2.dp, c.primary) else null,
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(Dimens.md)) {
            Text(headline(f), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(f.ext.uppercase().ifBlank { null }, f.vcodec.substringBefore('.').ifBlank { null }, f.acodec.substringBefore('.').ifBlank { null })
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1,
            )
            Text(
                listOfNotNull(f.sizeBytes.takeIf { it > 0 }?.let(::formatBytes), f.tbr.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ").ifEmpty { f.label },
                style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}
