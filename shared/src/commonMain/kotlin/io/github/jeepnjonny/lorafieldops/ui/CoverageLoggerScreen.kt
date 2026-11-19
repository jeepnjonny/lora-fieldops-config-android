package io.github.jeepnjonny.lorafieldops.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jeepnjonny.lorafieldops.logger.trackerDevice
import io.github.jeepnjonny.lorafieldops.platform.AppPermission
import io.github.jeepnjonny.lorafieldops.platform.formatDateTime
import io.github.jeepnjonny.lorafieldops.platform.rememberPermissionRequest
import kotlinx.coroutines.delay

private fun fieldsChangedSinceSave(baseUrl: String, apiKey: String, device: String, s: io.github.jeepnjonny.lorafieldops.logger.LoggerSettings) =
    baseUrl != s.baseUrl || apiKey != s.apiKey || device != s.device

/**
 * Settings and status for the optional coverage logger. [onBack] is shown as a back
 * arrow when the screen was opened from the connection screen (no bottom bar there).
 */
@Composable
fun CoverageLoggerScreen(
    onBack: (() -> Unit)? = null,
    vm: LoggerViewModel,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val tracker by vm.tracker.collectAsStateWithLifecycle()

    // Text fields are edited locally and applied with Save, so a half-typed key or
    // callsign never reaches the logger. Re-seeded when the stored value changes.
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var device by remember(settings.device) { mutableStateOf(settings.device) }
    var showKey by remember { mutableStateOf(false) }
    // "Saved" confirmation next to the button; fades after a few seconds or on the next edit.
    var savedNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedNote) { if (savedNote != null) { delay(4_000); savedNote = null } }
    LaunchedEffect(baseUrl, apiKey, device) { if (fieldsChangedSinceSave(baseUrl, apiKey, device, settings)) savedNote = null }
    val fieldsChanged = baseUrl != settings.baseUrl || apiKey != settings.apiKey || device != settings.device

    var notificationsDenied by remember { mutableStateOf(false) }
    val notificationPermission = rememberPermissionRequest(AppPermission.NOTIFICATIONS) { granted ->
        notificationsDenied = !granted
    }
    LaunchedEffect(settings.enabled) {
        if (settings.enabled && !notificationPermission.isGranted) notificationPermission.launch()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
            Text("Coverage logger", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "Records what the tracker sends and hears over Bluetooth and uploads it to the APRS " +
                "Coverage website, so two trackers can be compared exactly. Works on Bluetooth " +
                "connections only. Nothing is sent anywhere while this is off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Status first: it's what you check on a drive.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                val state = when {
                    !settings.enabled -> "Off"
                    runtime.logging -> "Logging ${runtime.device}: ${runtime.sent} sent, ${runtime.heard} heard"
                    runtime.reconnecting -> "Reconnecting to the tracker…"
                    runtime.linkError != null -> runtime.linkError!!
                    else -> "Waiting for a Bluetooth tracker connection"
                }
                InfoRow("Logger", state)
                val loggingAs = settings.device.ifBlank { trackerDevice(tracker) }
                InfoRow("Logging as", loggingAs.ifEmpty { "— (no tracker connected)" })
                InfoRow("Queued", "$queued")
                InfoRow(
                    "Last upload",
                    if (status.lastUploadMs > 0)
                        formatDateTime(status.lastUploadMs)
                    else "Never",
                )
                OutlinedButton(
                    onClick = vm::uploadNow,
                    enabled = settings.enabled && !uploading && queued > 0,
                ) {
                    if (uploading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (uploading) "Uploading…" else "Upload now")
                }
            }
        }

        status.blockedMessage?.let {
            WarningBanner("Uploads stopped: $it\nFix it below and tap Save; uploads resume after any settings change.")
        }
        if (status.blockedMessage == null) {
            status.lastError?.let { WarningBanner("Last upload problem: $it") }
        }
        if (status.entryErrors.isNotEmpty()) {
            var showEntryErrors by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    TextButton(onClick = { showEntryErrors = !showEntryErrors }) {
                        Text(
                            "${status.entryErrors.size} entr${if (status.entryErrors.size == 1) "y" else "ies"} " +
                                "rejected by the server (${if (showEntryErrors) "hide" else "show"})",
                        )
                    }
                    if (showEntryErrors) {
                        status.entryErrors.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow("Enable coverage logger", settings.enabled, vm::setEnabled)
                Text(
                    "The tracker allows one Bluetooth phone at a time, so APRSDroid (or APRS.fi) " +
                        "can't be connected to it while the logger is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notificationsDenied) {
                    Text(
                        "Notifications are off, so the logging notification won't show. Logging still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Website", style = MaterialTheme.typography.titleMedium)
                val urlError = baseUrlError(baseUrl)
                val keyWillClear = urlError == null && settings.apiKey.isNotEmpty() &&
                    hostChanged(settings.baseUrl, baseUrl) && apiKey.trim() == settings.apiKey
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Website address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = urlError != null,
                    supportingText = when {
                        urlError != null -> ({ Text(urlError) })
                        keyWillClear -> ({ Text("A different website: saving clears the upload key. Enter that site's key.") })
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                val keyLooksWrong = apiKey.isNotBlank() && !apiKey.trim().startsWith("acv_")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Upload key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = keyLooksWrong,
                    supportingText = {
                        Text(
                            if (keyLooksWrong) "Upload keys start with acv_"
                            else "Make one on the website: Account tab, Upload keys",
                        )
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (showKey) "Hide key" else "Show key",
                            )
                        }
                    },
                )
                // Blank = follow the connected tracker's own callsign (and object name, if set).
                // Default: follow the connected tracker (stored as a blank device). The field
                // then shows the callsign actually in use, read-only, so it's clear what is sent.
                val fromTracker = trackerDevice(tracker)
                var overriding by remember(settings.device) { mutableStateOf(settings.device.isNotBlank()) }
                SwitchRow("Use the connected tracker's callsign", !overriding) { follow ->
                    overriding = !follow
                    // Start an override from the tracker's own callsign, the usual thing to tweak.
                    device = if (follow) "" else device.ifBlank { fromTracker }
                }
                OutlinedTextField(
                    value = if (overriding) device else fromTracker,
                    onValueChange = { device = it },
                    readOnly = !overriding,
                    label = { Text(if (overriding) "Device callsign" else "Device callsign (from tracker)") },
                    placeholder = if (!overriding && fromTracker.isEmpty()) {
                        { Text("Read when a tracker connects over Bluetooth") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(
                            when {
                                overriding -> "Must match the website, e.g. K7ABC-9, or SENDER:OBJECT for an object"
                                fromTracker.isNotEmpty() -> "Read from the connected tracker; its tactical name is " +
                                    "added as SENDER:OBJECT when it beacons as an object"
                                else -> "Connect a tracker over Bluetooth and its callsign is filled in here"
                            },
                        )
                    },
                )
                if (!vm.keyPersisted) {
                    Text(
                        "This phone's secure storage isn't available, so the key is kept only until the app closes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            savedNote = if (keyWillClear) "Saved. Upload key cleared: enter the new site's key." else "Saved"
                            vm.saveFields(baseUrl, apiKey, device)
                        },
                        enabled = fieldsChanged && urlError == null,
                    ) {
                        Text("Save")
                    }
                    savedNote?.let {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What to upload", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Upload transmissions", settings.uploadTx, vm::setUploadTx)
                SwitchRow("Upload received packets", settings.uploadRx, vm::setUploadRx)
                SwitchRow("Record setting changes as device notes", settings.recordSettingNotes, vm::setRecordNotes)
                Text(
                    "Notes are added when you save settings to this tracker, e.g. \"beacon rate 120 → 60 s\". " +
                        "Passwords, PINs, passcodes, unlock tokens, the fixed position, Wi-Fi networks, " +
                        "the APRS-IS server and filter and the Bluetooth name are never included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}
