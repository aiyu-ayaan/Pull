package com.aiyu.pull.ui.update

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aiyu.pull.R
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.service.Notifier
import com.aiyu.pull.update.AppUpdater
import com.aiyu.pull.update.NoteBlock
import com.aiyu.pull.update.ReleaseNotes
import com.aiyu.pull.update.UpdateChannel
import com.aiyu.pull.update.UpdateState
import com.aiyu.pull.update.UpdateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updater: AppUpdater,
    private val repo: SettingsRepository,
    private val notifier: Notifier,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: StateFlow<UpdateState> = updater.state
    val settings: StateFlow<Settings?> = updater.settings
    val offerShownInline: MutableStateFlow<Boolean> = updater.offerShownInline
    val installedName: String get() = updater.installedName
    val channel: UpdateChannel get() = updater.channel

    fun check() { viewModelScope.launch { updater.check(manual = true) } }

    fun download(s: UpdateState.Available) = updater.download(s.release, s.apk)
    fun install(s: UpdateState.Ready) = updater.install(s.file)
    fun notNow() = updater.snooze()
    fun dismiss() = updater.dismiss()

    fun canInstall() = updater.canInstall()
    fun installPermissionIntent(): Intent = updater.installPermissionIntent()

    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            repo.setUpdatesEnabled(on)
            UpdateWorker.schedule(context, on) // a switch that says "no" must not leave a daily job running
            if (!on) { updater.dismiss(); notifier.cancelUpdateAvailable() }
        }
    }

    fun setChannel(c: UpdateChannel) {
        viewModelScope.launch {
            repo.setUpdateChannel(c)
            updater.onChannelChanged()
            updater.check(manual = true, channel = c)
        }
    }
}

/** Shown over the app when the launch / daily check found a newer release. "Not now" is quiet for a day. */
@Composable
fun UpdateHost() {
    val vm = hiltViewModel<UpdateViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val inline by vm.offerShownInline.collectAsStateWithLifecycle()
    if (!inline && (state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Ready)) {
        // Fully expanded: a half-open sheet hides the buttons under the system gesture bar.
        ModalBottomSheet(onDismissRequest = vm::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            UpdateOffer(state, vm, Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding())
        }
    }
}

/** Top of Settings → Updates: what's available, or where the last check got to, and the Check button. */
@Composable
fun UpdateStatus(vm: UpdateViewModel, lastChecked: Long, formatWhen: (Long) -> String) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val st = state) {
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready -> UpdateOffer(st, vm)
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularWavyProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
            }
            UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.titleMedium)
            is UpdateState.Failed -> Text(st.message, style = MaterialTheme.typography.bodyMedium, color = c.error)
            UpdateState.Idle -> Text(
                if (lastChecked > 0) stringResource(R.string.update_last_checked, formatWhen(lastChecked)) else stringResource(R.string.update_not_checked),
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
        }
        Button(onClick = vm::check, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_check)) }
    }
}

/** The body shared by the sheet and the Updates page: what's new, then the one action that fits the state. */
@Composable
fun UpdateOffer(state: UpdateState, vm: UpdateViewModel, modifier: Modifier = Modifier) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        else -> return
    }
    val context = LocalContext.current
    // "Install unknown apps" is granted in system settings; re-check when the user comes back.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose { } }
    val canInstall = remember(resumes, state) { vm.canInstall() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(stringResource(R.string.notif_update_title, release.name), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.update_youre_on, vm.installedName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (release.notes.isNotBlank()) {
            ReleaseNotesView(release.notes, Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
        }
        when (state) {
            is UpdateState.Available -> Button(onClick = { vm.download(state) }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_download) + if (state.apk.size > 0) stringResource(R.string.update_download_size, state.apk.size / 1_000_000) else "")
            }
            is UpdateState.Downloading -> {
                LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_downloading, (state.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is UpdateState.Ready -> if (canInstall) {
                Button(onClick = { vm.install(state) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_install)) }
            } else {
                Text(stringResource(R.string.update_install_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { context.startActivity(vm.installPermissionIntent()) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_allow_installs)) }
            }
            else -> Unit
        }
        if (state !is UpdateState.Downloading) OutlinedButton(onClick = vm::notNow, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_not_now)) }
    }
}

/** A GitHub release body drawn as what it is (headings, bullets, paragraphs), not as raw `###` and `**`. */
@Composable
fun ReleaseNotesView(notes: String, modifier: Modifier = Modifier) {
    val blocks = remember(notes) { ReleaseNotes.parse(notes) }
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceContainerHighest)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                is NoteBlock.Bullet -> Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                    Text(inline(block.text, code), style = MaterialTheme.typography.bodyMedium)
                }
                is NoteBlock.Paragraph -> Text(inline(block.text, code), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

private fun inline(text: String, code: SpanStyle): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        m.groups[1]?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it.value) } }
        m.groups[2]?.let { withStyle(code) { append(it.value) } }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

val UpdateChannel.labelRes: Int get() = when (this) {
    UpdateChannel.STABLE -> R.string.update_channel_stable
    UpdateChannel.BETA -> R.string.update_channel_beta
    UpdateChannel.ALPHA -> R.string.update_channel_alpha
}

val UpdateChannel.detailRes: Int get() = when (this) {
    UpdateChannel.STABLE -> R.string.update_channel_stable_desc
    UpdateChannel.BETA -> R.string.update_channel_beta_desc
    UpdateChannel.ALPHA -> R.string.update_channel_alpha_desc
}
