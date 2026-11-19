package com.kj7nye.lorafieldops.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kj7nye.lorafieldops.logger.trackerDevice
import java.text.DateFormat
import java.util.Date

/**
 * Settings and status for the optional coverage logger. [onBack] is shown as a back
 * arrow when the screen was opened from the connection screen (no bottom bar there).
 */
@Composable
fun CoverageLoggerScreen(
    onBack: (() -> Unit)? = null,
    vm: LoggerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val status by vm.status.collectAsState()
    val runtime by vm.runtime.collectAsState()
    val queued by vm.queued.collectAsState()
    val uploading by vm.uploading.collectAsState()
    val tracker by vm.tracker.collectAsState()

    // Text fields are edited locally and applied with Save, so a half-typed key or
    // callsign never reaches the logger. Re-seeded when the stored value changes.
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var device by remember(settings.device) { mutableStateOf(settings.device) }
    var showKey by remember { mutableStateOf(false) }
    val fieldsChanged = baseUrl != settings.baseUrl || apiKey != settings.apiKey || device != settings.device

    var notificationsDenied by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsDenied = !granted
    }
    LaunchedEffect(settings.enabled) {
        if (settings.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                val fromTracker = trackerDevice(tracker)
                OutlinedTextField(
                    value = device,
                    onValueChange = { device = it },
                    label = { Text("Device callsign") },
                    placeholder = { Text(fromTracker.ifEmpty { "Connected tracker's callsign" }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(
                            when {
                                device.isNotBlank() -> "Leave blank to follow the connected tracker. " +
                                    "Must match the website, e.g. K7ABC-9, or SENDER:OBJECT for an object"
                                fromTracker.isNotEmpty() -> "Following the connected tracker: $fromTracker. Type another to override it"
                                else -> "Blank uses the connected tracker's callsign, plus its tactical " +
                                    "callsign as SENDER:OBJECT when it beacons as an object"
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
                Button(onClick = { vm.saveFields(baseUrl, apiKey, device) }, enabled = fieldsChanged && urlError == null) {
                    Text("Save")
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                val state = when {
                    !settings.enabled -> "Off"
                    runtime.logging -> "Logging ${runtime.device}: ${runtime.sent} sent, ${runtime.heard} heard"
                    runtime.reconnecting -> "Reconnecting to the tracker…"
                    runtime.linkError != null -> runtime.linkError!!
                    else -> "Waiting for a Bluetooth tracker connection"
                }
                InfoRow("Logger", state)
                InfoRow("Queued", "$queued")
                InfoRow(
                    "Last upload",
                    if (status.lastUploadMs > 0)
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(status.lastUploadMs))
                    else "Never",
                )
                status.blockedMessage?.let {
                    Text("Uploads stopped: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (status.blockedMessage == null) {
                    status.lastError?.let {
                        Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (status.entryErrors.isNotEmpty()) {
                    Text("Entries the server rejected:", style = MaterialTheme.typography.bodySmall)
                    status.entryErrors.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedButton(
                    onClick = vm::uploadNow,
                    enabled = settings.enabled && !uploading && queued > 0,
                ) { Text(if (uploading) "Uploading…" else "Upload now") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
