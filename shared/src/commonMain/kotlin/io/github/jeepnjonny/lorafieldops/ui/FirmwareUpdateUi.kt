package io.github.jeepnjonny.lorafieldops.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeepnjonny.lorafieldops.firmware.ReleaseCheck
import io.github.jeepnjonny.lorafieldops.firmware.UpdateStep

/** Update controls inside the Status screen's Firmware card. Hidden where updates aren't offered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.FirmwareUpdateSection(vm: ConfigViewModel) {
    val updater = vm.firmwareUpdater ?: return
    val firmware = vm.platform.firmware ?: return
    val board by vm.boardId.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val check by updater.check.collectAsStateWithLifecycle()
    val pickFile = firmware.rememberFilePicker { picked ->
        if (picked != null) updater.useFile(picked) else vm.snack("Couldn't read that file")
    }

    Spacer(Modifier.height(8.dp))
    // Recomputed on each connection change: the link and its services are only known once open.
    val blocker = connection.let { updater.blocker(board) }
    when {
        blocker != null -> {
            Text(blocker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        dirty -> {
            Text(
                "Save or discard your config changes before updating the firmware.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
    }

    when (val c = check) {
        ReleaseCheck.Idle -> Unit
        ReleaseCheck.Checking -> Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium)
        is ReleaseCheck.Failed -> Text(c.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        is ReleaseCheck.Found -> Text(
            when (c.newer) {
                true -> "Update available: ${c.release.tag}"
                false -> "Up to date (latest release is ${c.release.tag})"
                null -> "Latest release: ${c.release.tag}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val found = check as? ReleaseCheck.Found
        if (found != null) {
            Button(onClick = { updater.downloadRelease() }) {
                Text(if (found.newer == false) "Reinstall ${found.release.tag}" else "Install ${found.release.tag}")
            }
        } else {
            OutlinedButton(onClick = { updater.checkForUpdate() }, enabled = check != ReleaseCheck.Checking) {
                Text("Check for update")
            }
        }
        OutlinedButton(onClick = pickFile) { Text("Install from file") }
    }
}

/** The update in progress, over whatever screen is showing (the link drops and reconnects meanwhile). */
@Composable
internal fun FirmwareUpdateDialog(vm: ConfigViewModel) {
    val updater = vm.firmwareUpdater ?: return
    val firmware = vm.platform.firmware ?: return
    val step by updater.step.collectAsStateWithLifecycle()
    val current = step ?: return

    val finished = current is UpdateStep.Done || current is UpdateStep.Failed || current is UpdateStep.Confirm
    if (!finished) firmware.KeepScreenOn()

    AlertDialog(
        onDismissRequest = { if (finished) updater.dismiss() },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = finished),
        title = {
            Text(
                when (current) {
                    is UpdateStep.Confirm -> "Install ${current.image.label}?"
                    is UpdateStep.Done -> "Firmware updated"
                    is UpdateStep.Failed -> "Firmware update failed"
                    else -> "Updating firmware"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (current) {
                    is UpdateStep.Confirm -> {
                        Text(current.image.fileName, style = MaterialTheme.typography.bodySmall)
                        current.warnings.forEach { Text("• $it") }
                    }
                    is UpdateStep.Downloading -> {
                        Text("Downloading…")
                        val f = current.fraction
                        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateStep.Transferring -> {
                        val pct = (current.fraction * 100).toInt()
                        val rate = current.kbPerSecond?.let { " · ${formatRate(it)} KB/s" } ?: ""
                        Text("Sending to the tracker: $pct %$rate")
                        LinearProgressIndicator(progress = { current.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Keep this screen open and the phone near the tracker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateStep.Working -> {
                        CircularProgressIndicator()
                        Text(current.message)
                    }
                    is UpdateStep.Done -> Text(current.message)
                    is UpdateStep.Failed -> Text(current.message)
                }
            }
        },
        confirmButton = {
            when (current) {
                is UpdateStep.Confirm -> Button(onClick = { updater.confirm() }) { Text("Install") }
                is UpdateStep.Failed -> if (current.canRetryNow) {
                    Button(onClick = { updater.retryNow() }) { Text("Retry now") }
                } else {
                    TextButton(onClick = { updater.dismiss() }) { Text("Close") }
                }
                is UpdateStep.Done -> TextButton(onClick = { updater.dismiss() }) { Text("Close") }
                is UpdateStep.Downloading -> TextButton(onClick = { updater.cancel() }) { Text("Cancel") }
                is UpdateStep.Transferring -> if (current.cancellable) {
                    TextButton(onClick = { updater.cancel() }) { Text("Cancel") }
                }
                is UpdateStep.Working -> Unit
            }
        },
        dismissButton = {
            when (current) {
                is UpdateStep.Confirm -> TextButton(onClick = { updater.dismiss() }) { Text("Not now") }
                is UpdateStep.Failed -> if (current.canRetryNow) {
                    TextButton(onClick = { updater.dismiss() }) { Text("Close") }
                }
                else -> Unit
            }
        },
    )
}

private fun formatRate(kbPerSecond: Float): String {
    val tenths = (kbPerSecond * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}
