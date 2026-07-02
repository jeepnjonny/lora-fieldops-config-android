package com.kj7nye.lorafieldops.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(vm: ConfigViewModel) {
    val statusLines by vm.statusLines.collectAsState()
    var showOtaDfuDialog by remember { mutableStateOf(false) }

    // Load version on entry
    LaunchedEffect(Unit) { vm.readFirmwareVersion() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Live Status", style = MaterialTheme.typography.headlineSmall)

        // Battery
        StatusCard(
            icon = { Icon(Icons.Default.BatteryFull, null) },
            title = "Battery",
            body = listOf(
                "Voltage" to (statusLines["bat.voltage"] ?: "—"),
                "Percent" to (statusLines["bat.percent"]?.let { "$it%" } ?: "—"),
            ),
            onRefresh = { vm.readBattery() },
        )

        // GPS
        StatusCard(
            icon = { Icon(Icons.Default.GpsFixed, null) },
            title = "GPS",
            body = listOf("Position" to (statusLines["gps"] ?: "—")),
            onRefresh = { vm.readGps() },
        )

        // APRS-IS
        StatusCard(
            icon = { Icon(Icons.Default.Wifi, null) },
            title = "APRS-IS",
            body = listOf(
                "Connected" to (statusLines["aprsIS.connected"] ?: "—"),
            ),
            onRefresh = { vm.readAprsIsStatus() },
        )

        // Firmware version
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Firmware", style = MaterialTheme.typography.titleMedium)
                    Text(
                        statusLines["version"] ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = { vm.readFirmwareVersion() }) {
                    Icon(Icons.Default.Refresh, "Refresh version")
                }
            }
        }

        // Actions
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Actions", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.txCommentNow() }) { Text("Send comment now") }
                    OutlinedButton(onClick = { vm.txStatusNow() }) { Text("Send status now") }
                }
                Button(onClick = { showOtaDfuDialog = true }) { Text("Enter OTA DFU mode") }
                Text(
                    "nRF52 boards only — disconnects USB serial; reflash via nRF Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showOtaDfuDialog) {
        AlertDialog(
            onDismissRequest = { showOtaDfuDialog = false },
            title = { Text("Enter OTA DFU mode?") },
            text = {
                Text(
                    "The tracker will reboot into the Nordic DFU bootloader and drop off " +
                        "this USB connection. You'll need the nRF Connect app to upload new " +
                        "firmware. ESP32 boards don't support this and will report an error instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showOtaDfuDialog = false; vm.triggerOtaDfu() }) {
                    Text("Enter DFU mode", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOtaDfuDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusCard(
    icon: @Composable () -> Unit,
    title: String,
    body: List<Pair<String, String>>,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            icon()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                body.forEach { (k, v) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "$k:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Refresh $title")
            }
        }
    }
}
