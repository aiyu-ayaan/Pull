package com.aiyu.pull.ui.add

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.R
import com.aiyu.pull.ui.components.FileKind
import com.aiyu.pull.ui.components.ShapeIcon
import com.aiyu.pull.ui.components.tone
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.theme.Dimens
import com.aiyu.pull.ui.theme.clickHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What opens the sheet: a typed/pasted/shared link, or a download the browser caught (with its cookies). */
@Serializable
data class AddRequest(
    val url: String = "",
    val fileName: String? = null,
    val pageUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Composable
fun AddDownloadSheet(request: AddRequest, vm: AddViewModel, onDismiss: () -> Unit, onAdded: (Long) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Saveable: a process death while the sheet is open brings the same text back.
    var url by rememberSaveable { mutableStateOf(request.url) }
    var name by rememberSaveable { mutableStateOf(request.fileName.orEmpty()) }
    var nameEdited by rememberSaveable { mutableStateOf(request.fileName != null) }
    val defaults by vm.connections.collectAsStateWithLifecycle()
    var connections by rememberSaveable { mutableFloatStateOf(-1f) }
    if (connections < 0 && defaults > 0) connections = defaults.toFloat()
    val probe by vm.probe.collectAsStateWithLifecycle()

    LaunchedEffect(url) {
        delay(400) // typing debounce
        vm.probe(url.trim(), request.headers)
    }
    LaunchedEffect(probe) {
        val p = probe
        if (p is ProbeState.Ready && !nameEdited) p.info.fileName?.let { name = it }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun add(start: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        scope.launch {
            val id = vm.add(url.trim(), name.takeIf { nameEdited || it.isNotBlank() }, request.pageUrl, request.headers, connections.toInt(), start)
            sheet.hide()
            onAdded(id)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, modifier = Modifier.clickHaptics()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.xl)
                .imePadding().navigationBarsPadding().padding(bottom = Dimens.lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.lg),
        ) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.add_url)) },
                placeholder = { Text("https://") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                isError = probe is ProbeState.Failed,
                modifier = Modifier.fillMaxWidth(),
            )
            ProbeCard(probe, name)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameEdited = true },
                label = { Text(stringResource(R.string.add_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.add_connections), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${connections.toInt().coerceAtLeast(1)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = connections.coerceIn(1f, 32f), onValueChange = { connections = it }, valueRange = 1f..32f, steps = 30)
            }
            val ready = url.trim().startsWith("http://") || url.trim().startsWith("https://")
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { add(false) }, enabled = ready, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.Schedule, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                    Text(stringResource(R.string.add_later))
                }
                Button(onClick = { add(true) }, enabled = ready, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(Dimens.sm))
                    Text(stringResource(R.string.add_start))
                }
            }
        }
    }
}

@Composable
private fun ProbeCard(probe: ProbeState, name: String) {
    val c = MaterialTheme.colorScheme
    AnimatedContent(probe, contentKey = { it::class }, label = "probe") { p ->
        Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(Dimens.lg).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                when (p) {
                    ProbeState.Idle -> Text(stringResource(R.string.probe_idle), color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    ProbeState.Loading -> {
                        LoadingIndicator(Modifier.size(40.dp))
                        Spacer(Modifier.width(Dimens.md))
                        Text(stringResource(R.string.probe_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                    is ProbeState.Failed -> {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = c.error)
                        Spacer(Modifier.width(Dimens.md))
                        Text(p.message, color = c.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    is ProbeState.Ready -> {
                        val kind = FileKind.of(name.ifBlank { p.info.fileName.orEmpty() }, p.info.mimeType)
                        ShapeIcon(kind.icon, kind.shape, kind.tone.container, kind.tone.onContainer, size = 40.dp, iconSize = 20.dp)
                        Spacer(Modifier.width(Dimens.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (p.info.totalBytes >= 0) formatBytes(p.info.totalBytes) else stringResource(R.string.probe_unknown_size),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (p.info.acceptRanges) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
                                    tint = if (p.info.acceptRanges) c.primary else c.tertiary, modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(Dimens.xs))
                                Text(
                                    stringResource(if (p.info.acceptRanges) R.string.probe_resumable else R.string.probe_not_resumable),
                                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
