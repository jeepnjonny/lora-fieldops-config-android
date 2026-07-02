package com.kj7nye.lorafieldops.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kj7nye.lorafieldops.model.SMARTBEACON_LABELS
import com.kj7nye.lorafieldops.model.BEACON_PATH_OPTIONS
import com.kj7nye.lorafieldops.model.DeviceRole
import com.kj7nye.lorafieldops.model.DigiMode
import com.kj7nye.lorafieldops.model.GpsSource

private val LORA_BW_OPTIONS = listOf(
    7800L to "7.8 kHz", 10400L to "10.4 kHz", 15600L to "15.6 kHz",
    20800L to "20.8 kHz", 31250L to "31.25 kHz", 41700L to "41.7 kHz",
    62500L to "62.5 kHz", 125000L to "125 kHz", 250000L to "250 kHz",
    500000L to "500 kHz",
)
private val MIC_E_OPTIONS = listOf(
    "" to "Off",
    "0" to "0 – Off duty",
    "1" to "1 – En route",
    "2" to "2 – In service",
    "3" to "3 – Returning",
    "4" to "4 – Committed",
    "5" to "5 – Special",
    "6" to "6 – Priority",
    "7" to "7 – Emergency",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(vm: ConfigViewModel) {
    val cfg by vm.config.collectAsState()
    val dirty by vm.dirty.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }

    if (cfg == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { Text("Loading configuration…") }
        return
    }
    val c = cfg!!

    // Warn user before NOCALL TX
    val isNocall = c.beacons[0].callsign.startsWith("NOCALL", ignoreCase = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Configuration")
                        if (dirty) Text(
                            "Unsaved changes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (dirty) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtendedFloatingActionButton(
                        text = { Text("Discard") },
                        icon = { Icon(Icons.Default.Undo, null) },
                        onClick = { showDiscardDialog = true },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                    ExtendedFloatingActionButton(
                        text = { Text("Save") },
                        icon = { Icon(Icons.Default.Save, null) },
                        onClick = { vm.save() },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isNocall) {
                Card(
                    color = MaterialTheme.colorScheme.errorContainer,
                    label = "⚠ Callsign is NOCALL — transmit is blocked by firmware",
                )
            }

            Section("Beacon") {
                LabeledTextField("Callsign", c.beacons[0].callsign) {
                    vm.setCallsign(it)
                }
                LabeledTextField("Symbol (1 char)", c.beacons[0].symbol) {
                    if (it.length <= 1) vm.setSymbol(it)
                }
                LabeledTextField("Overlay (1 char)", c.beacons[0].overlay) {
                    if (it.length <= 1) vm.setOverlay(it)
                }
                DropdownField("Mic-E", MIC_E_OPTIONS, c.beacons[0].micE) { vm.setMicE(it) }
                LabeledTextField("Comment", c.beacons[0].comment) { vm.setComment(it) }
                LabeledTextField("Status", c.beacons[0].status) { vm.setStatus(it) }
                LabeledTextField("Tactical callsign (≤9 chars)", c.beacons[0].tacticalCallsign) {
                    vm.setTacticalCallsign(it)
                }
            }

            Section("SmartBeacon") {
                SwitchRow("SmartBeacon active", c.beacons[0].smartBeaconActive) {
                    vm.setSmartBeaconActive(it)
                }
                DropdownField(
                    label = "Profile",
                    options = SMARTBEACON_LABELS.mapIndexed { i, l -> i to l },
                    selected = c.beacons[0].smartBeaconSetting,
                ) { vm.setSmartBeaconSetting(it) }

                if (c.beacons[0].smartBeaconSetting == 4) {
                    // Custom parameters only visible when Custom profile selected
                    HorizontalDivider()
                    Text("Custom parameters", style = MaterialTheme.typography.labelMedium)
                    val sb = c.customSmartBeacon
                    IntField("Slow rate (sec)", sb.slowRate) { vm.setSmartSlowRate(it) }
                    IntField("Slow speed (km/h)", sb.slowSpeed) { vm.setSmartSlowSpeed(it) }
                    IntField("Fast rate (sec)", sb.fastRate) { vm.setSmartFastRate(it) }
                    IntField("Fast speed (km/h)", sb.fastSpeed) { vm.setSmartFastSpeed(it) }
                    IntField("Min turn (deg)", sb.turnMinDeg) { vm.setSmartTurnMinDeg(it) }
                    IntField("Turn slope", sb.turnSlope) { vm.setSmartTurnSlope(it) }
                }
            }

            Section("LoRa") {
                val l = c.lora[0]
                IntField("Frequency (Hz)", l.frequency.toInt()) { vm.setLoraFreq(it.toLong()) }
                LabeledSlider("Spreading Factor", l.spreadingFactor.toFloat(), 7f, 12f, steps = 4) {
                    vm.setLoraSf(it.toInt())
                }
                DropdownField("Bandwidth", LORA_BW_OPTIONS, l.signalBandwidth) {
                    vm.setLoraBw(it)
                }
                LabeledSlider("Coding Rate (4/x)", l.codingRate4.toFloat(), 5f, 8f, steps = 2) {
                    vm.setLoraCr(it.toInt())
                }
                LabeledSlider("TX Power (dBm)", l.power.toFloat(), 0f, 22f, steps = 21) {
                    vm.setLoraPower(it.toInt())
                }
            }

            Section("Display") {
                val d = c.display
                SwitchRow("Eco mode (auto-off)", d.ecoMode) { vm.setDisplayEco(it) }
                IntField("Timeout (sec)", d.timeout) { vm.setDisplayTimeout(it) }
                SwitchRow("Rotate 180°", d.turn180) { vm.setDisplayTurn180(it) }
                SwitchRow("Invert display", d.invertDisplay) { vm.setDisplayInvert(it) }
                SwitchRow("LED enabled", d.ledEnabled) { vm.setDisplayLed(it) }
            }

            Section("Bluetooth") {
                SwitchRow("Bluetooth active", c.bluetooth.active) { vm.setBtActive(it) }
                LabeledTextField("Device name", c.bluetooth.deviceName) { vm.setBtName(it) }
            }

            Section("Battery") {
                val b = c.battery
                SwitchRow("Send voltage in beacon", b.sendVoltage) { vm.setBatSendVoltage(it) }
                SwitchRow("Send on every beacon", b.sendVoltageAlways) {
                    vm.setBatSendVoltageAlways(it)
                }
                FloatField("Sleep threshold (V)", b.sleepVoltage) { vm.setBatSleepVoltage(it) }
            }

            Section("PTT Trigger") {
                val p = c.pttTrigger
                SwitchRow("PTT active", p.active) { vm.setPttActive(it) }
                IntField("GPIO pin (-1 = disabled)", p.ioPin) { vm.setPttPin(it) }
                SwitchRow("Reverse polarity", p.reverse) { vm.setPttReverse(it) }
                IntField("Pre-delay (ms)", p.preDelay) { vm.setPttPreDelay(it) }
                IntField("Post-delay (ms)", p.postDelay) { vm.setPttPostDelay(it) }
            }

            Section("PHG (Station Power-Height-Gain)") {
                val p = c.phg
                SwitchRow("PHG beacon active", p.enabled) { vm.setPhgEnabled(it) }
                LabeledSlider("Power code", p.power.toFloat(), 0f, 9f, steps = 8) {
                    vm.setPhgPower(it.toInt())
                }
                LabeledSlider("Height code", p.height.toFloat(), 0f, 9f, steps = 8) {
                    vm.setPhgHeight(it.toInt())
                }
                LabeledSlider("Gain code", p.gain.toFloat(), 0f, 9f, steps = 8) {
                    vm.setPhgGain(it.toInt())
                }
                LabeledSlider("Directivity code", p.directivity.toFloat(), 0f, 9f, steps = 8) {
                    vm.setPhgDirectivity(it.toInt())
                }
                IntField("Beacon interval (min)", p.beaconRate) { vm.setPhgRate(it) }
            }

            Section("WiFi AP") {
                PasswordField("AP password", c.wifiAP.password) { vm.setWifiApPassword(it) }
            }

            Section("WiFi STA") {
                val w = c.wifiSTA
                SwitchRow("Enable WiFi STA", w.enabled) { vm.setWifiStaEnabled(it) }
                LabeledTextField("SSID", w.ssid) { vm.setWifiStaSsid(it) }
                PasswordField("Password", w.password) { vm.setWifiStaPassword(it) }
            }

            Section("APRS-IS") {
                val a = c.aprsIS
                LabeledTextField("Server", a.server) { vm.setAprsIsServer(it) }
                IntField("Port", a.port) { vm.setAprsIsPort(it) }
                LabeledTextField("Passcode", a.passcode) { vm.setAprsIsPasscode(it) }
                LabeledTextField("Filter", a.filter) { vm.setAprsIsFilter(it) }
            }

            Section("TCP KISS") {
                IntField("Port", c.tcpKISS.port) { vm.setTcpKissPort(it) }
            }

            Section("Role & GPS") {
                DropdownField(
                    label = "Device role",
                    options = listOf(0 to "Tracker", 1 to "iGate", 2 to "Digipeater"),
                    selected = c.deviceRole,
                ) { vm.setDeviceRole(it) }
                DropdownField(
                    label = "GPS source",
                    options = listOf(
                        0 to GpsSource.label(0),
                        1 to GpsSource.label(1),
                        2 to GpsSource.label(2),
                    ),
                    selected = c.gpsSource,
                ) { vm.setGpsSource(it) }
                DropdownField(
                    label = "Digi mode",
                    options = listOf(
                        0 to DigiMode.label(0),
                        1 to DigiMode.label(1),
                        2 to DigiMode.label(2),
                    ),
                    selected = c.other.digiMode,
                ) { vm.setDigiMode(it) }
            }

            if (c.gpsSource == 1) {
                Section("Fixed Position") {
                    val fp = c.fixedPosition
                    FloatField("Latitude (dd.dddddd)", fp.latitude) { vm.setFixedLat(it) }
                    FloatField("Longitude (dd.dddddd)", fp.longitude) { vm.setFixedLon(it) }
                    FloatField("Elevation (m)", fp.elevation) { vm.setFixedElev(it) }
                }
            }

            Section("Other") {
                val o = c.other
                DropdownField(
                    label = "Beacon path",
                    options = BEACON_PATH_OPTIONS,
                    // Legacy configs stored "" for no-repeat; normalize to the explicit DIRECT option.
                    selected = o.beaconPath.ifEmpty { "DIRECT" },
                ) { vm.setBeaconPath(it) }
                IntField("Non-smart rate (min)", o.nonSmartBeaconRate) { vm.setNonSmartRate(it) }
                IntField("Comment after N beacons", o.sendCommentAfterXBeacons) {
                    vm.setSendCommentAfter(it)
                }
                SwitchRow("Send altitude", o.sendAltitude) { vm.setSendAltitude(it) }
                SwitchRow("Send speed/course", o.sendSpeedCourse) { vm.setSendSpeedCourse(it) }
            }

            Spacer(Modifier.height(80.dp)) // space for FAB
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("All unsaved changes will be lost. The device will reboot to reload the last saved config.") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; vm.discard() }) {
                    Text("Discard & reboot", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Reusable composables
// ---------------------------------------------------------------------------

@Composable
private fun Card(color: androidx.compose.ui.graphics.Color, label: String) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = color),
    ) { Text(label, modifier = Modifier.padding(12.dp)) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
    }
}

@Composable
private fun LabeledTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun IntField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toIntOrNull()?.let { onValueChange(it) }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun FloatField(label: String, value: Float, onValueChange: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toFloatOrNull()?.let { onValueChange(it) }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(current.toInt().toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onValueChangeFinished(current) },
            valueRange = min..max,
            steps = steps,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected.toString()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}
