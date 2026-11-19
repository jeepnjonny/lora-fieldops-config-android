package io.github.jeepnjonny.lorafieldops.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jeepnjonny.lorafieldops.platform.AppPermission
import io.github.jeepnjonny.lorafieldops.platform.UsbDeviceInfo
import io.github.jeepnjonny.lorafieldops.platform.rememberPermissionRequest

@Composable
fun ConnectionScreen(
    vm: ConfigViewModel,
    onOpenLogger: () -> Unit,
    onConnected: () -> Unit,
) {
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val loggerRuntime by vm.coverageLogger.runtime.collectAsStateWithLifecycle()
    val retryLabel by vm.retryLabel.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf<List<UsbDeviceInfo>>(emptyList()) }
    val hasUsb = vm.platform.usb != null

    // Refresh device list whenever screen is shown or connection state changes
    LaunchedEffect(state) {
        if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
            devices = vm.findUsbDevices()
        }
        if (state is ConnectionState.Connected) {
            onConnected()
        }
    }

    // Leaving this screen (connected, or app backgrounded) ends any running scan.
    DisposableEffect(Unit) { onDispose { vm.bleScanner.stop() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LoRa FieldOps Config", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (hasUsb) "Connect your tracker by USB-OTG cable or Bluetooth" else "Connect your tracker over Bluetooth",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            is ConnectionState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(s.message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { vm.cancelConnecting() }) { Text("Cancel") }
            }
            is ConnectionState.Connected -> {
                // Handled by LaunchedEffect navigation above
            }
            is ConnectionState.Error, ConnectionState.Disconnected -> {
                if (s is ConnectionState.Error) {
                    WarningBanner(s.message)
                    if (!loggerRuntime.reconnecting) {
                        retryLabel?.let { label ->
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.retryConnection() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(label)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (loggerRuntime.reconnecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Coverage logger: reconnecting to the tracker automatically…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { vm.disconnect() }) { Text("Stop") }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (hasUsb) {
                    UsbSection(
                        devices = devices,
                        onRefresh = { devices = vm.findUsbDevices() },
                        onConnect = { device -> vm.connectUsbWithPermission(device) },
                    )
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))
                }
                BluetoothSection(vm)
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onOpenLogger) { Text("Coverage logger settings") }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// USB
// -----------------------------------------------------------------------------

@Composable
private fun UsbSection(
    devices: List<UsbDeviceInfo>,
    onRefresh: () -> Unit,
    onConnect: (UsbDeviceInfo) -> Unit,
) {
    SectionHeader(Icons.Default.Usb, "USB")
    Spacer(Modifier.height(12.dp))
    if (devices.isEmpty()) {
        Text(
            "No USB serial devices detected.\nConnect the tracker and tap Refresh.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { device ->
                DeviceCard(
                    title = device.title,
                    subtitle = device.subtitle,
                    onConnect = { onConnect(device) },
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onRefresh) { Text("Refresh devices") }
}

// -----------------------------------------------------------------------------
// Bluetooth
// -----------------------------------------------------------------------------

@Composable
private fun BluetoothSection(vm: ConfigViewModel) {
    val trackers by vm.bleScanner.results.collectAsStateWithLifecycle()
    val scanning by vm.bleScanner.scanning.collectAsStateWithLifecycle()
    val scanError by vm.bleScanner.error.collectAsStateWithLifecycle()
    var permissionDenied by remember { mutableStateOf(false) }

    val permission = rememberPermissionRequest(AppPermission.BLUETOOTH) { granted ->
        permissionDenied = !granted
        if (granted) vm.bleScanner.start()
    }

    fun startScan() {
        if (permission.isGranted) {
            permissionDenied = false
            vm.bleScanner.start()
        } else {
            permission.launch()
        }
    }

    SectionHeader(Icons.Default.Bluetooth, "Bluetooth")
    Spacer(Modifier.height(8.dp))
    Text(
        "Bluetooth setup needs the tracker's \"Require PIN\" option turned on (Bluetooth " +
        "section, set over USB first). ${vm.platform.osName} asks for the PIN the first time you connect. " +
        "The tracker accepts one Bluetooth connection at a time, so disconnect APRSDroid or " +
        "APRS.fi from it first.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    if (!vm.bleScanner.isSupported) {
        Text("This phone has no Bluetooth LE.", color = MaterialTheme.colorScheme.error)
        return
    }

    val message = when {
        permissionDenied -> "Bluetooth permission was denied. Allow it in ${vm.platform.osName} settings to scan."
        scanError != null -> scanError
        else -> null
    }
    message?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }

    if (trackers.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            trackers.forEach { tracker ->
                DeviceCard(
                    title = tracker.name,
                    subtitle = "${tracker.id}  ${tracker.rssi} dBm",
                    onConnect = { vm.connectBle(tracker) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    } else if (scanning) {
        Text("Searching for trackers…", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { vm.bleScanner.stop() }) { Text("Stop") }
        } else {
            OutlinedButton(onClick = { startScan() }) { Text("Scan for trackers") }
        }
    }
}

// -----------------------------------------------------------------------------
// Shared pieces
// -----------------------------------------------------------------------------

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DeviceCard(title: String, subtitle: String, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}
