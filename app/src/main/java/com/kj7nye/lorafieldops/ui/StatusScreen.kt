package com.kj7nye.lorafieldops.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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

        // GPS: valid / location (copyable) / altitude, one per line
        StatusCard(
            icon = { Icon(Icons.Default.GpsFixed, null) },
            title = "GPS",
            onRefresh = { vm.readGps() },
        ) {
            GpsStatusBody(statusLines)
        }

        // APRS-IS
        val aprsIsConnected = statusLines["aprsIS.connected"]
        StatusCard(
            icon = { Icon(Icons.Default.Wifi, null) },
            title = "APRS-IS",
            body = buildList {
                add("Connected" to yesNo(aprsIsConnected))
                if (aprsIsConnected == "true") {
                    aprsIsServerText(statusLines)?.let { add("Server" to it) }
                    aprsIsLoginText(statusLines["aprsIS.login"])?.let { add("Login" to it) }
                }
            },
            onRefresh = { vm.readAprsIsStatus() },
        )

        // WiFi STA
        val wifiConnected = statusLines["wifiSTA.connected"]
        StatusCard(
            icon = { Icon(Icons.Default.Wifi, null) },
            title = "WiFi STA",
            body = buildList {
                add("Connected" to yesNo(wifiConnected))
                if (wifiConnected == "true") {
                    statusLines["wifiSTA.ssid"]?.takeIf { it.isNotEmpty() }?.let { add("SSID" to it) }
                    statusLines["wifiSTA.ip"]?.let { add("IP" to it) }
                }
            },
            onRefresh = { vm.readWifiStaStatus() },
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

/** "true"/"false" from the firmware as Yes/No; "—" until first read. */
private fun yesNo(flag: String?): String = when (flag) {
    "true" -> "Yes"
    "false" -> "No"
    else -> "—"
}

/**
 * "T2USASW (rotate.aprs.net:14580)": the pool member the login landed on, then the
 * host:port the tracker dialed. Falls back to whichever part the firmware reported.
 */
private fun aprsIsServerText(status: Map<String, String>): String? {
    val host = status["aprsIS.host"]
    val port = status["aprsIS.port"]
    val server = status["aprsIS.server"]
    val dialed = host?.let { if (port != null) "$it:$port" else it }
    return when {
        server != null && dialed != null -> "$server ($dialed)"
        else -> server ?: dialed
    }
}

/**
 * APRS-IS login outcome. A rejected passcode does not drop the connection — the
 * server keeps it open receive-only — so "Connected: Yes" alone can't show it.
 * Null (no line) when the firmware doesn't report it.
 */
private fun aprsIsLoginText(login: String?): String? = when (login) {
    "verified"   -> "Verified (uploads accepted)"
    "unverified" -> "Passcode rejected (receive-only)"
    "pending"    -> "Logging in…"
    else         -> null
}

/** GPS card body: fix status, then a copyable "lat,lon", then altitude. */
@Composable
private fun GpsStatusBody(status: Map<String, String>) {
    val valid = status["gps.valid"]
    val hasFix = valid == "1"
    val sats = status["gps.sats"]

    StatusRow(
        "Valid",
        when {
            valid == null -> "—"
            hasFix -> if (sats != null) "Yes ($sats satellites)" else "Yes"
            else -> "No fix"
        },
    )

    val lat = status["gps.lat"]
    val lon = status["gps.lon"]
    if (hasFix && lat != null && lon != null) {
        val location = "$lat,$lon"
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Location:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            // Selectable as well as copy-button so a long-press works too.
            SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                Text(location, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Tracker location", location)))
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ContentCopy, "Copy location", modifier = Modifier.size(16.dp))
            }
        }
    } else {
        StatusRow("Location", "—")
    }

    val alt = status["gps.alt"]
    StatusRow("Altitude", if (hasFix && alt != null) "$alt m" else "—")
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Card with a simple list of label/value rows. */
@Composable
private fun StatusCard(
    icon: @Composable () -> Unit,
    title: String,
    body: List<Pair<String, String>>,
    onRefresh: () -> Unit,
) = StatusCard(icon, title, onRefresh) {
    body.forEach { (label, value) -> StatusRow(label, value) }
}

/** Card with custom [content] under the title. */
@Composable
private fun StatusCard(
    icon: @Composable () -> Unit,
    title: String,
    onRefresh: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
                content()
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Refresh $title")
            }
        }
    }
}
