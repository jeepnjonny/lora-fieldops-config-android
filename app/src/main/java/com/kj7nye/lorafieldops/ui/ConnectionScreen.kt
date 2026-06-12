package com.kj7nye.lorafieldops.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialDriver

@Composable
fun ConnectionScreen(
    vm: ConfigViewModel,
    onConnected: () -> Unit,
) {
    val state by vm.connectionState.collectAsState()
    var devices by remember { mutableStateOf<List<UsbSerialDriver>>(emptyList()) }

    // Refresh device list whenever screen is shown or connection state changes
    LaunchedEffect(state) {
        if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
            devices = vm.serialManager.findDevices()
        }
        if (state is ConnectionState.Connected) {
            onConnected()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Usb,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("LoRa FieldOps Config", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect your tracker via USB-OTG cable",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        when (val s = state) {
            is ConnectionState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Entering setup mode…")
            }
            is ConnectionState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = s.message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
                DeviceList(devices) { driver ->
                    requestPermissionAndConnect(vm, driver)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { devices = vm.serialManager.findDevices() }) {
                    Text("Refresh devices")
                }
            }
            is ConnectionState.Disconnected -> {
                if (devices.isEmpty()) {
                    Text(
                        "No USB serial devices detected.\nConnect the tracker and tap Refresh.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { devices = vm.serialManager.findDevices() }) {
                        Text("Refresh devices")
                    }
                } else {
                    Text("Select device to connect:", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    DeviceList(devices) { driver ->
                        requestPermissionAndConnect(vm, driver)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { devices = vm.serialManager.findDevices() }) {
                        Text("Refresh devices")
                    }
                }
            }
            is ConnectionState.Connected -> {
                // Handled by LaunchedEffect navigation above
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<UsbSerialDriver>,
    onConnect: (UsbSerialDriver) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices) { driver ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = driver.device.productName ?: "USB Device",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "VID:${driver.device.vendorId.toString(16).uppercase()} " +
                                   "PID:${driver.device.productId.toString(16).uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onConnect(driver) }) { Text("Connect") }
                }
            }
        }
    }
}

/** Request USB permission if needed, then connect. */
private fun requestPermissionAndConnect(vm: ConfigViewModel, driver: UsbSerialDriver) {
    val device = driver.device
    if (vm.serialManager.hasPermission(device)) {
        vm.connect(driver)
    } else {
        vm.serialManager.requestPermission(device) { granted ->
            if (granted) vm.connect(driver)
        }
    }
}
