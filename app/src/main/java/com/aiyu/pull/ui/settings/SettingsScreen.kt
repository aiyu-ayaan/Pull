package com.aiyu.pull.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.BuildConfig
import com.aiyu.pull.R
import com.aiyu.pull.data.Encryption
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.ThemeMode
import com.aiyu.pull.video.AudioFormat
import com.aiyu.pull.video.Container
import com.aiyu.pull.video.VideoCodec
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.readableGutter
import com.aiyu.pull.ui.theme.Dimens
import kotlinx.serialization.Serializable

/** Categories of the settings home, each its own page (Android Settings: home list, then a page per subject). */
@Serializable
enum class SettingsPage(val title: Int, val summary: Int) {
    DOWNLOADS(R.string.settings_downloads, R.string.settings_downloads_summary),
    TORRENTS(R.string.settings_torrents, R.string.settings_torrents_summary),
    VIDEO(R.string.settings_video, R.string.settings_video_summary),
    APPEARANCE(R.string.settings_appearance, R.string.settings_appearance_summary),
    ABOUT(R.string.settings_about, R.string.settings_about_summary),
}

private data class PageLook(val icon: ImageVector, val shape: RoundedPolygon, val container: Color, val content: Color)

@Composable
private fun SettingsPage.look(): PageLook {
    val c = MaterialTheme.colorScheme
    return when (this) {
        SettingsPage.DOWNLOADS -> PageLook(Icons.Rounded.Download, MaterialShapes.Cookie9Sided, c.primaryContainer, c.onPrimaryContainer)
        SettingsPage.TORRENTS -> PageLook(Icons.Rounded.Hub, MaterialShapes.Clover4Leaf, c.tertiaryContainer, c.onTertiaryContainer)
        SettingsPage.VIDEO -> PageLook(Icons.Rounded.SmartDisplay, MaterialShapes.Sunny, c.secondaryContainer, c.onSecondaryContainer)
        SettingsPage.APPEARANCE -> PageLook(Icons.Rounded.Palette, MaterialShapes.Cookie7Sided, c.primaryContainer, c.onPrimaryContainer)
        SettingsPage.ABOUT -> PageLook(Icons.Rounded.Info, MaterialShapes.Circle, c.surfaceContainerHighest, c.onSurfaceVariant)
    }
}

/** Settings home: one row per category. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onPage: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    SettingsScaffold(stringResource(R.string.settings_title), onBack, modifier) {
        item {
            Group {
                SettingsPage.entries.forEach { page ->
                    val look = page.look()
                    ListItem(
                        headlineContent = { Text(stringResource(page.title)) },
                        supportingContent = { Text(stringResource(page.summary)) },
                        leadingContent = { ShapeIcon(look.icon, look.shape, look.container, look.content, size = 40.dp, iconSize = 22.dp) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onPage(page) },
                    )
                }
            }
        }
    }
}

/** One category's options. */
@Composable
fun SettingsPageScreen(page: SettingsPage, vm: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val settings = s ?: return
    SettingsScaffold(stringResource(page.title), onBack, modifier) {
        when (page) {
            SettingsPage.DOWNLOADS -> downloads(settings, vm)
            SettingsPage.TORRENTS -> torrents(settings, vm)
            SettingsPage.VIDEO -> video(settings, vm)
            SettingsPage.APPEARANCE -> appearance(settings, vm)
            SettingsPage.ABOUT -> item {
                Group {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.app_name)) },
                        supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
                        colors = itemColors(),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_license)) },
                        supportingContent = { Text(stringResource(R.string.settings_license_summary)) },
                        colors = itemColors(),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.downloads(s: Settings, vm: SettingsViewModel) {
    item {
        Section(R.string.settings_section_speed) {
            SliderItem(R.string.settings_connections, R.string.settings_connections_hint, s.connections, 1..32, onChange = vm::setConnections)
            SliderItem(R.string.settings_simultaneous, R.string.settings_simultaneous_hint, s.maxDownloads, 1..10, onChange = vm::setMaxDownloads)
        }
    }
    item {
        Section(R.string.settings_section_behavior) {
            SwitchItem(R.string.settings_auto_resume, R.string.settings_auto_resume_hint, s.autoResume, vm::setAutoResume)
        }
    }
}

private fun LazyListScope.torrents(s: Settings, vm: SettingsViewModel) {
    item {
        Section(R.string.settings_section_downloading) {
            SwitchItem(R.string.torrent_sequential, R.string.settings_sequential_hint, s.torrentSequential) { on -> vm.update { setTorrentSequential(on) } }
        }
    }
    item {
        Section(R.string.settings_section_limits) {
            LimitItem(R.string.settings_torrent_down, s.torrentDownLimit) { kb -> vm.update { setTorrentDownLimit(kb) } }
            LimitItem(R.string.settings_torrent_up, s.torrentUpLimit) { kb -> vm.update { setTorrentUpLimit(kb) } }
            SliderItem(R.string.settings_torrent_connections, R.string.settings_torrent_connections_hint, s.torrentConnections, 20..500, step = 10) { n ->
                vm.update { setTorrentConnections(n) }
            }
        }
    }
    item {
        Section(R.string.settings_section_network) {
            SwitchItem(R.string.settings_dht, R.string.settings_dht_hint, s.torrentDht) { on -> vm.update { setTorrentDht(on) } }
            SwitchItem(R.string.settings_lsd, R.string.settings_lsd_hint, s.torrentLsd) { on -> vm.update { setTorrentLsd(on) } }
            SwitchItem(R.string.settings_utp, R.string.settings_utp_hint, s.torrentUtp) { on -> vm.update { setTorrentUtp(on) } }
            SwitchItem(R.string.settings_port_mapping, R.string.settings_port_mapping_hint, s.torrentPortMapping) { on -> vm.update { setTorrentPortMapping(on) } }
        }
    }
    item {
        Section(R.string.settings_section_privacy) {
            ChoiceItem(
                R.string.settings_encryption,
                options = listOf(
                    Encryption.ENABLED to R.string.encryption_enabled,
                    Encryption.FORCED to R.string.encryption_forced,
                    Encryption.DISABLED to R.string.encryption_disabled,
                ),
                selected = s.torrentEncryption,
            ) { e -> vm.update { setTorrentEncryption(e) } }
        }
    }
}

private fun LazyListScope.video(s: Settings, vm: SettingsViewModel) {
    val v = s.video
    item {
        Section(R.string.settings_section_format) {
            ChoiceItem(
                R.string.settings_video_quality,
                options = listOf(0 to R.string.quality_best, 2160 to R.string.res_2160, 1440 to R.string.res_1440, 1080 to R.string.res_1080,
                    720 to R.string.res_720, 480 to R.string.res_480, 360 to R.string.res_360),
                selected = v.maxRes,
            ) { r -> vm.update { setVideoMaxRes(r) } }
            ChoiceItem(
                R.string.settings_video_codec,
                options = listOf(VideoCodec.ANY to R.string.codec_any, VideoCodec.AV1 to R.string.codec_av1, VideoCodec.VP9 to R.string.codec_vp9, VideoCodec.AVC to R.string.codec_avc),
                selected = v.codec,
            ) { c -> vm.update { setVideoCodec(c) } }
            ChoiceItem(
                R.string.settings_video_container,
                options = listOf(Container.AUTO to R.string.container_auto, Container.MP4 to R.string.container_mp4, Container.MKV to R.string.container_mkv),
                selected = v.container,
            ) { c -> vm.update { setVideoContainer(c) } }
            ChoiceItem(
                R.string.settings_audio_format,
                options = listOf(AudioFormat.BEST to R.string.audio_best, AudioFormat.M4A to R.string.audio_m4a, AudioFormat.MP3 to R.string.audio_mp3, AudioFormat.OPUS to R.string.audio_opus),
                selected = v.audio,
            ) { f -> vm.update { setAudioFormat(f) } }
        }
    }
    item {
        Section(R.string.settings_section_extras) {
            SwitchItem(R.string.opt_subtitles, R.string.opt_subtitles_hint, v.subtitles) { on -> vm.update { setVideoSubtitles(on) } }
            ChoiceItem(
                R.string.settings_subtitle_langs,
                options = listOf("en.*" to R.string.subs_english, "all,-live_chat" to R.string.subs_all),
                selected = v.subtitleLangs,
            ) { l -> vm.update { setSubtitleLangs(l) } }
            SwitchItem(R.string.opt_thumbnail, R.string.opt_thumbnail_hint, v.thumbnail) { on -> vm.update { setVideoThumbnail(on) } }
            SwitchItem(R.string.opt_metadata, R.string.opt_metadata_hint, v.metadata) { on -> vm.update { setVideoMetadata(on) } }
            SwitchItem(R.string.opt_sponsorblock, R.string.opt_sponsorblock_hint, v.sponsorBlock) { on -> vm.update { setSponsorBlock(on) } }
        }
    }
    item {
        Section(R.string.settings_section_ytdlp) {
            ChoiceItem(
                R.string.settings_ytdlp_channel,
                options = listOf("STABLE" to R.string.channel_stable, "NIGHTLY" to R.string.channel_nightly, "MASTER" to R.string.channel_master),
                selected = s.ytdlpChannel,
            ) { ch -> vm.update { setYtdlpChannel(ch) } }
            SwitchItem(R.string.settings_ytdlp_auto, R.string.settings_ytdlp_auto_hint, s.ytdlpAutoUpdate) { on -> vm.update { setYtdlpAutoUpdate(on) } }
        }
    }
}

private fun LazyListScope.appearance(s: Settings, vm: SettingsViewModel) {
    item {
        Section(R.string.settings_theme) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.lg, vertical = Dimens.sm).selectableGroup(),
            ) {
                val modes = ThemeMode.entries
                modes.forEachIndexed { i, mode ->
                    ToggleButton(
                        checked = s.themeMode == mode,
                        onCheckedChange = { vm.setTheme(mode) },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = Dimens.minTouch).semantics { role = Role.RadioButton },
                    ) {
                        Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchItem(R.string.settings_dynamic, R.string.settings_dynamic_hint, s.dynamicColor, vm::setDynamicColor)
            }
        }
    }
}

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, modifier: Modifier, content: LazyListScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = readableGutter(), end = readableGutter(),
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Dimens.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.md),
            content = content,
        )
    }
}

@Composable
private fun itemColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

/** Rounded group of rows, like the grouped cards of Android 16 Settings. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = Dimens.sm)) { content() }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column {
        Text(
            stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Dimens.lg, bottom = Dimens.sm, top = Dimens.sm),
        )
        Group(content)
    }
}

@Composable
private fun SwitchItem(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = itemColors(),
        // The whole row toggles, as in Android Settings.
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
private fun SliderItem(title: Int, hint: Int, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = Dimens.lg, vertical = Dimens.sm)) {
        Row {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${local.toInt()}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = local,
            onValueChange = { local = (Math.round(it / step) * step).toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()) },
            onValueChangeFinished = { onChange(local.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // Tick marks only while they stay readable; long ranges snap to [step] without them.
            steps = ((range.last - range.first) / step - 1).takeIf { it <= 20 } ?: 0,
        )
    }
}

/** A value picked from a short list in a dialog (Android's ListPreference). */
@Composable
private fun <T> ChoiceItem(title: Int, options: List<Pair<T, Int>>, selected: T, onPick: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: options.first().second
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(current)) },
        colors = itemColors(),
        modifier = Modifier.clickable { open = true },
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)
                            .selectable(value == selected, role = Role.RadioButton) { open = false; onPick(value) },
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.md))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A speed limit in KB/s; 0 means unlimited. */
@Composable
private fun LimitItem(title: Int, kb: Int, onChange: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(if (kb > 0) "$kb KB/s" else stringResource(R.string.limit_unlimited)) },
        colors = itemColors(),
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        var text by rememberSaveable { mutableStateOf(if (kb > 0) kb.toString() else "") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Text(stringResource(R.string.limits_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        text, { text = it.filter(Char::isDigit).take(7) }, suffix = { Text("KB/s") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = false; onChange(text.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.action_apply)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
