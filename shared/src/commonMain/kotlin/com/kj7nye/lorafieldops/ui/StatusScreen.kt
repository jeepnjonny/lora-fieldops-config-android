package com.kj7nye.lorafieldops.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.kj7nye.lorafieldops.platform.plainTextClip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(vm: ConfigViewModel) {
    val statusLines by vm.statusLines.collectAsStateWithLifecycle()
    val loading by vm.statusLoading.collectAsStateWithLifecycle()

    // Everything on entry: the reads queue on the link and fill in card by card.
    LaunchedEffect(Unit) { vm.refreshAllStatus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Live Status",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = { vm.refreshAllStatus() }, enabled = loading.isEmpty()) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("Refresh all", modifier = Modifier.padding(start = 6.dp))
            }
        }

        StatusCard(
            icon = Icons.Default.BatteryFull,
            title = "Battery",
            loading = StatusGroup.BATTERY in loading,
            body = listOf(
                "Voltage" to (statusLines["bat.voltage"]?.let { "$it V" } ?: "—"),
                "Charge" to (statusLines["bat.percent"]?.let { "$it%" } ?: "—"),
            ),
            onRefresh = { vm.readBattery() },
        )

        StatusCard(
            icon = Icons.Default.GpsFixed,
            title = "GPS",
            loading = StatusGroup.GPS in loading,
            onRefresh = { vm.readGps() },
        ) {
            GpsStatusBody(statusLines)
        }

        val aprsIsConnected = statusLines["aprsIS.connected"]
        StatusCard(
            icon = Icons.Default.Cloud,
            title = "APRS-IS",
            loading = StatusGroup.APRS_IS in loading,
            body = buildList {
                add("Connected" to yesNo(aprsIsConnected))
                if (aprsIsConnected == "true") {
                    aprsIsServerText(statusLines)?.let { add("Server" to it) }
                    aprsIsLoginText(statusLines["aprsIS.login"])?.let { add("Login" to it) }
                }
            },
            onRefresh = { vm.readAprsIsStatus() },
        )

        val wifiConnected = statusLines["wifiSTA.connected"]
        StatusCard(
            icon = Icons.Default.Wifi,
            title = "WiFi",
            loading = StatusGroup.WIFI in loading,
            body = buildList {
                add("Connected" to yesNo(wifiConnected))
                if (wifiConnected == "true") {
                    statusLines["wifiSTA.ssid"]?.takeIf { it.isNotEmpty() }?.let { add("Network" to it) }
                    statusLines["wifiSTA.ip"]?.let { add("IP" to it) }
                }
            },
            onRefresh = { vm.readWifiStaStatus() },
        )

        StatusCard(
            icon = Icons.Default.Memory,
            title = "Firmware",
            loading = StatusGroup.VERSION in loading,
            body = listOf("Version" to (statusLines["version"] ?: "—")),
            onRefresh = { vm.readFirmwareVersion() },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send now", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.txNow(TxBeacon.COMMENT) }) { Text("Comment beacon") }
                    OutlinedButton(onClick = { vm.txNow(TxBeacon.STATUS) }) { Text("Status beacon") }
                }
            }
        }
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
internal fun aprsIsServerText(status: Map<String, String>): String? {
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
internal fun aprsIsLoginText(login: String?): String? = when (login) {
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

    InfoRow(
        "Fix",
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
            // Selectable as well as copy-button so a long-press works too.
            SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                InfoRow("Location", location)
            }
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(plainTextClip("Tracker location", location))
                    }
                },
            ) {
                Icon(Icons.Default.ContentCopy, "Copy location")
            }
        }
    } else {
        InfoRow("Location", "—")
    }

    val alt = status["gps.alt"]
    InfoRow("Altitude", if (hasFix && alt != null) "$alt m" else "—")
}

/** Card with a simple list of label/value rows. */
@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    loading: Boolean,
    body: List<Pair<String, String>>,
    onRefresh: () -> Unit,
) = StatusCard(icon, title, loading, onRefresh) {
    body.forEach { (label, value) -> InfoRow(label, value) }
}

/** Card with custom [content] under the title, and its own refresh button / spinner. */
@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(2.dp))
                content()
            }
            // 48 dp either way, so the layout doesn't jump while loading.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "Refresh $title")
                    }
                }
            }
        }
    }
}
