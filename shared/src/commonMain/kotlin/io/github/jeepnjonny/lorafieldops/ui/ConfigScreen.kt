package io.github.jeepnjonny.lorafieldops.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeepnjonny.lorafieldops.model.BEACON_PATH_OPTIONS
import io.github.jeepnjonny.lorafieldops.model.DeviceRole
import io.github.jeepnjonny.lorafieldops.model.DigiMode
import io.github.jeepnjonny.lorafieldops.model.FirmwareVersion
import io.github.jeepnjonny.lorafieldops.model.GpsSource
import io.github.jeepnjonny.lorafieldops.model.SMARTBEACON_CUSTOM
import io.github.jeepnjonny.lorafieldops.model.SMARTBEACON_LABELS
import io.github.jeepnjonny.lorafieldops.model.SmartBeaconLimits
import io.github.jeepnjonny.lorafieldops.model.TrackerConfig
import io.github.jeepnjonny.lorafieldops.platform.AppPermission
import io.github.jeepnjonny.lorafieldops.platform.rememberPermissionRequest

// Board-agnostic curated set: 20 dBm is the safe ceiling on every supported
// board — bare SX1262 boards (T114, V3.2) clamp to +22 dBm chip output
// regardless of setting >=20, and HAS_1W_LORA boards (T-Beam 1W, LoRanger V1)
// are firmware-clamped to 20 dBm chip drive into their external 30 dBm PA
// (see lora_utils.cpp applyOutputPower()).
private val TX_POWER_OPTIONS = listOf(6, 12, 18, 20)

// Actual RF output per setting, by board family. Only the 20 dBm row for
// HAS_1W_LORA boards is a verified datasheet figure; the rest of that column
// is approximate (assumes a roughly fixed PA gain across its drive range).
private data class TxPowerRow(val setting: Int, val chipOnly: String, val oneWatt: String)
private val TX_POWER_TABLE = listOf(
    TxPowerRow(6, "8 dBm (~6 mW)", "~16 dBm (~40 mW), approx."),
    TxPowerRow(12, "14 dBm (~25 mW)", "~22 dBm (~158 mW), approx."),
    TxPowerRow(18, "20 dBm (~100 mW)", "~28 dBm (~631 mW), approx."),
    TxPowerRow(20, "22 dBm (~158 mW)", "30 dBm (1 W) — datasheet-validated max"),
)

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

private val Boolean.onOff get() = if (this) "On" else "Off"

@Composable
fun ConfigScreen(vm: ConfigViewModel) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val saved by vm.savedConfig.collectAsStateWithLifecycle()
    val fwVersion by vm.firmwareVersion.collectAsStateWithLifecycle()
    val fieldError by vm.lastError.collectAsStateWithLifecycle()

    // Feature floors verified against tagged firmware source — see FirmwareVersion.kt.
    // Version unknown (not yet read, or unparseable) fails open for features whose
    // rejection is safely surfaced as an error; wifista multi-network fails closed
    // because older firmware silently corrupts rather than rejects that command.
    val supportsPhg = fwVersion?.let { it >= FirmwareVersion.MIN_PHG } ?: true
    val supportsAprsIsDownlink = fwVersion?.let { it >= FirmwareVersion.MIN_APRSIS_DOWNLINK } ?: true
    val supportsWifiMultiNetwork = fwVersion?.let { it >= FirmwareVersion.MIN_WIFI_MULTI_NETWORK } ?: false
    val supportsRemoteConfig = fwVersion?.let { it >= FirmwareVersion.MIN_REMOTE_CONFIG } ?: true
    val fwHint = fwVersion?.let { " (connected firmware: v$it)" } ?: ""

    val c = cfg
    if (c == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading configuration…", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    /** True when the part of the config [pick] selects differs from what's saved on the tracker. */
    fun edited(pick: (TrackerConfig) -> Any?): Boolean = saved?.let { pick(c) != pick(it) } ?: false

    val b = c.beacons[0]
    val isNocall = b.callsign.startsWith("NOCALL", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isNocall) WarningBanner("Callsign is NOCALL — transmit is blocked by firmware")

        fieldError?.let { err ->
            WarningBanner("\"${err.field}\" was rejected: ${err.message}") {
                TextButton(onClick = { vm.clearFieldError() }) { Text("Dismiss") }
            }
        }

        // ---- Main settings ------------------------------------------------------

        // What the tracker beacons as: CALLSIGN, or CALLSIGN:OBJECT when a tactical name is set.
        val identity = if (b.tacticalCallsign.isBlank()) b.callsign else "${b.callsign}:${b.tacticalCallsign}"
        Section(
            "Beacon",
            summaryContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(identity, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    AprsSymbolIcon(b.overlay, b.symbol, Modifier.padding(horizontal = 6.dp), size = 20.dp)
                    if (b.comment.isNotBlank()) {
                        Text(
                            b.comment,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            edited = edited { it.beacons[0].copy(smartBeaconActive = false, smartBeaconSetting = 0) },
            initiallyExpanded = true,
        ) {
            LabeledTextField("Callsign", b.callsign) { vm.setCallsign(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledTextField("Symbol (1 char)", b.symbol) {
                        singleCharEdit(b.symbol, it)?.let(vm::setSymbol)
                    }
                    LabeledTextField("Overlay / table (1 char)", b.overlay) {
                        singleCharEdit(b.overlay, it)?.let(vm::setOverlay)
                    }
                }
                // Live preview of the map icon these two characters select.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AprsSymbolIcon(b.overlay, b.symbol, size = 48.dp)
                    Text("${b.overlay}${b.symbol}", style = MaterialTheme.typography.labelMedium)
                }
            }
            DropdownField("Mic-E", MIC_E_OPTIONS, b.micE) { vm.setMicE(it) }
            LabeledTextField("Comment", b.comment) { vm.setComment(it) }
            LabeledTextField("Status", b.status) { vm.setStatus(it) }
            LabeledTextField("Tactical callsign (≤9 chars)", b.tacticalCallsign) { vm.setTacticalCallsign(it) }
        }

        Section(
            "SmartBeacon",
            summary = if (b.smartBeaconActive) "On · ${SMARTBEACON_LABELS.getOrElse(b.smartBeaconSetting) { "?" }}" else "Off",
            edited = edited { Triple(it.beacons[0].smartBeaconActive, it.beacons[0].smartBeaconSetting, it.customSmartBeacon) },
        ) {
            SwitchRow("SmartBeacon active", b.smartBeaconActive) { vm.setSmartBeaconActive(it) }
            DropdownField(
                label = "Profile",
                options = SMARTBEACON_LABELS.mapIndexed { i, l -> i to l },
                selected = b.smartBeaconSetting,
            ) { vm.setSmartBeaconSetting(it) }

            if (b.smartBeaconSetting == SMARTBEACON_CUSTOM) {
                // Custom parameters only visible when Custom profile selected
                HorizontalDivider()
                Text("Custom parameters", style = MaterialTheme.typography.labelLarge)
                val sb = c.customSmartBeacon
                IntField("Slow rate (sec)", sb.slowRate, SmartBeaconLimits.SLOW_RATE) { vm.setSmartSlowRate(it) }
                IntField("Slow speed (km/h)", sb.slowSpeed, SmartBeaconLimits.SLOW_SPEED) { vm.setSmartSlowSpeed(it) }
                IntField("Fast rate (sec)", sb.fastRate, SmartBeaconLimits.FAST_RATE) { vm.setSmartFastRate(it) }
                IntField("Fast speed (km/h)", sb.fastSpeed, SmartBeaconLimits.FAST_SPEED) { vm.setSmartFastSpeed(it) }
                IntField("Min turn (deg)", sb.turnMinDeg, SmartBeaconLimits.TURN_MIN_DEG) { vm.setSmartTurnMinDeg(it) }
                IntField("Turn slope", sb.turnSlope, SmartBeaconLimits.TURN_SLOPE) { vm.setSmartTurnSlope(it) }
                // Hidden on firmware that predates turnMinTime (key absent from its config).
                sb.turnMinTime?.let { minTime ->
                    IntField("Min turn time (sec)", minTime, SmartBeaconLimits.TURN_MIN_TIME) { vm.setSmartTurnMinTime(it) }
                }
            }
        }

        val o = c.other
        Section(
            "Beaconing",
            summary = "Path ${o.beaconPath.ifEmpty { "DIRECT" }} · every ${o.nonSmartBeaconRateSec} s without SmartBeacon",
            edited = edited { it.other.copy(cadActive = false, digiMode = 0) },
        ) {
            DropdownField(
                label = "Beacon path",
                options = BEACON_PATH_OPTIONS,
                // Legacy configs stored "" for no-repeat; normalize to the explicit DIRECT option.
                selected = o.beaconPath.ifEmpty { "DIRECT" },
            ) { vm.setBeaconPath(it) }
            IntField("Non-smart rate (sec)", o.nonSmartBeaconRateSec) { vm.setNonSmartRate(it) }
            IntField("Comment after N beacons", o.sendCommentAfterXBeacons) { vm.setSendCommentAfter(it) }
            SwitchRow("Send altitude", o.sendAltitude) { vm.setSendAltitude(it) }
            SwitchRow("Send speed/course", o.sendSpeedCourse) { vm.setSendSpeedCourse(it) }
        }

        val l = c.lora[0]
        Section(
            "LoRa",
            summary = "${formatMhz(l.frequency)} MHz" +
                " · SF${l.spreadingFactor} · " +
                (LORA_BW_OPTIONS.firstOrNull { it.first == l.signalBandwidth }?.second ?: "${l.signalBandwidth} Hz") +
                " · ${l.power} dBm",
            edited = edited { it.lora to it.other.cadActive },
        ) {
            IntField("Frequency (Hz)", l.frequency.toInt()) { vm.setLoraFreq(it.toLong()) }
            LabeledSlider("Spreading Factor", l.spreadingFactor.toFloat(), 7f, 12f, steps = 4) {
                vm.setLoraSf(it.toInt())
            }
            DropdownField("Bandwidth", LORA_BW_OPTIONS, l.signalBandwidth) { vm.setLoraBw(it) }
            LabeledSlider("Coding Rate (4/x)", l.codingRate4.toFloat(), 5f, 8f, steps = 2) {
                vm.setLoraCr(it.toInt())
            }
            DropdownField("TX Power (dBm)", TX_POWER_OPTIONS.map { it to "$it dBm" }, l.power) { vm.setLoraPower(it) }
            HelpText(
                "Actual RF output depends on your board — only 20 dBm on 1 W boards " +
                    "(T-Beam 1W, LoRanger V1) is a verified datasheet figure; other " +
                    "1 W-board values are approximate.",
            )
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Setting", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("T114 / V3.2", Modifier.weight(2f), style = MaterialTheme.typography.labelLarge)
                    Text("1 W boards", Modifier.weight(2f), style = MaterialTheme.typography.labelLarge)
                }
                TX_POWER_TABLE.forEach { row ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text("${row.setting} dBm", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(row.chipOnly, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                        Text(row.oneWatt, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            SwitchRow("Channel Activity Detection (CAD, listen before TX)", o.cadActive) { vm.setCadActive(it) }
            HelpText(
                "Waits for a clear channel plus a short random backoff before each TX; " +
                    "transmits anyway after 10 s of a busy channel.",
            )
        }

        val role = DeviceRole.fromCode(c.deviceRole)
        val gps = GpsSource.fromCode(c.gpsSource)
        val digi = DigiMode.fromCode(o.digiMode)
        Section(
            "Role & GPS",
            summary = "${role.label} · ${gps.label} · digi ${digi.label.substringBefore(" (")}",
            edited = edited { Triple(it.deviceRole, it.gpsSource, it.other.digiMode) },
        ) {
            DropdownField("Device role", DeviceRole.entries.map { it to it.label }, role) { vm.setDeviceRole(it) }
            DropdownField("GPS source", GpsSource.entries.map { it to it.label }, gps) { vm.setGpsSource(it) }
            DropdownField("Digi mode", DigiMode.entries.map { it to it.label }, digi) { vm.setDigiMode(it) }
        }

        if (gps == GpsSource.FIXED) {
            val fp = c.fixedPosition
            Section(
                "Fixed Position",
                summary = "${fp.latitude}, ${fp.longitude} · ${fp.elevation} m",
                edited = edited { it.fixedPosition },
            ) {
                FloatField("Latitude (dd.dddddd)", fp.latitude) { vm.setFixedLat(it) }
                FloatField("Longitude (dd.dddddd)", fp.longitude) { vm.setFixedLon(it) }
                FloatField("Elevation (m)", fp.elevation) { vm.setFixedElev(it) }

                val locating by vm.locationFetching.collectAsStateWithLifecycle()
                val locationPermission = rememberPermissionRequest(AppPermission.LOCATION) { granted ->
                    if (granted) vm.useDeviceLocation() else vm.locationPermissionDenied()
                }

                OutlinedButton(
                    onClick = {
                        if (locationPermission.isGranted) {
                            vm.useDeviceLocation()
                        } else {
                            locationPermission.launch()
                        }
                    },
                    enabled = !locating,
                ) {
                    if (locating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (locating) "Fetching location…" else "Use This Device's Location")
                }
            }
        }

        Section(
            "Bluetooth",
            summary = if (c.bluetooth.active) {
                "On · ${c.bluetooth.deviceName} · PIN ${c.bluetooth.pinEnabled.onOff.lowercase()}"
            } else "Off",
            edited = edited { it.bluetooth to it.kiss },
        ) {
            SwitchRow("Bluetooth active", c.bluetooth.active) { vm.setBtActive(it) }
            LabeledTextField("Device name", c.bluetooth.deviceName) { vm.setBtName(it) }
            SwitchRow("Require PIN", c.bluetooth.pinEnabled) { vm.setBtPinEnabled(it) }
            BlePinField(c.bluetooth.pin, c.bluetooth.pinEnabled) { vm.setBtPin(it) }
            HelpText(
                "Save + reboot to apply. After changing the PIN or turning it on/off, " +
                    "forget the tracker in your phone's Bluetooth settings and pair again.",
            )
            // Only firmware that has the option reports a "kiss" block.
            c.kiss?.let { k ->
                SwitchRow("Send RSSI/SNR with received packets", k.rssi) { vm.setKissRssi(it) }
                HelpText(
                    "Adds the signal strength of each heard packet to the KISS output (Bluetooth, " +
                        "USB and TCP), for the coverage logger. Tracker role only. Some KISS apps " +
                        "show the extra frames, so leave it off unless you log coverage.",
                    isError = k.rssi && role != DeviceRole.TRACKER,
                )
            }
        }

        val w = c.wifiSTA
        Section(
            "WiFi",
            summary = if (w.enabled) "On · ${w.networks.size} network${if (w.networks.size == 1) "" else "s"}" else "Off",
            edited = edited { it.wifiSTA },
        ) {
            SwitchRow("Enable WiFi STA", w.enabled) { vm.setWifiStaEnabled(it) }

            if (supportsWifiMultiNetwork) {
                HelpText("Up to 5 networks, tried in order top to bottom. Stays connected until the link drops.")

                w.networks.forEachIndexed { i, net ->
                    HorizontalDivider()
                    LabeledTextField("SSID ${i + 1}", net.ssid) { vm.setWifiStaSsid(i, it) }
                    PasswordField("Password ${i + 1}", net.password) { vm.setWifiStaPassword(i, it) }
                    TextButton(onClick = { vm.removeWifiStaNetwork(i) }) { Text("Delete network ${i + 1}") }
                }

                if (w.networks.size < 5) {
                    OutlinedButton(onClick = { vm.addWifiStaNetwork("") }) { Text("+ Add network") }
                }

                val scanning by vm.wifiScanning.collectAsStateWithLifecycle()
                val scanResults by vm.wifiScanResults.collectAsStateWithLifecycle()
                OutlinedButton(onClick = { vm.scanWifiNetworks() }, enabled = !scanning) {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (scanning) "Scanning… (blocks the device a few seconds)" else "Scan for networks")
                }
                if (scanResults.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Nearby networks — tap to add", style = MaterialTheme.typography.labelLarge)
                    scanResults.sortedByDescending { it.rssi }.forEach { net ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(
                                    enabled = w.networks.size < 5,
                                    onClickLabel = "Add network",
                                ) { vm.addWifiStaNetwork(net.ssid) }
                                .semantics(mergeDescendants = true) {},
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (net.secure) {
                                Icon(Icons.Default.Lock, contentDescription = "Secured", modifier = Modifier.size(18.dp))
                            } else {
                                Spacer(Modifier.width(18.dp))
                            }
                            Text(net.ssid, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("${net.rssi} dBm", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                // Older firmware parses "wifista ssid <i> <ssid>" as a flat,
                // non-indexed command and silently mis-stores the index digit
                // as part of the SSID instead of rejecting it — so this UI stays
                // hidden entirely rather than risk sending it. See FirmwareVersion.kt.
                HelpText(
                    if (fwVersion == null) {
                        "Saved-network list unavailable until firmware version is confirmed."
                    } else {
                        "Multiple saved networks require firmware v${FirmwareVersion.MIN_WIFI_MULTI_NETWORK} or newer$fwHint."
                    },
                )
            }
        }

        val a = c.aprsIS
        Section(
            "APRS-IS",
            summary = "${a.server}:${a.port}",
            edited = edited { it.aprsIS },
        ) {
            LabeledTextField("Server", a.server) { vm.setAprsIsServer(it) }
            IntField("Port", a.port) { vm.setAprsIsPort(it) }
            LabeledTextField("Passcode (blank = auto from callsign)", a.passcode) { vm.setAprsIsPasscode(it) }
            LabeledTextField("Filter", a.filter) { vm.setAprsIsFilter(it) }
            if (supportsAprsIsDownlink) {
                SwitchRow("Downlink to RF", a.downlinkEnabled) { vm.setAprsIsDownlink(it) }
                HelpText(
                    "Gates directed messages from APRS-IS back to RF, but only to a " +
                        "station heard directly on RF in the last 30 minutes.",
                )
            } else {
                HelpText("Downlink to RF requires firmware v${FirmwareVersion.MIN_APRSIS_DOWNLINK} or newer$fwHint.")
            }
        }

        val d = c.display
        Section(
            "Display",
            summary = "Eco ${d.ecoMode.onOff.lowercase()} · timeout ${d.timeout} s · LED ${d.ledEnabled.onOff.lowercase()}",
            edited = edited { it.display },
        ) {
            SwitchRow("Eco mode (auto-off)", d.ecoMode) { vm.setDisplayEco(it) }
            IntField("Timeout (sec)", d.timeout) { vm.setDisplayTimeout(it) }
            SwitchRow("Rotate 180°", d.turn180) { vm.setDisplayTurn180(it) }
            SwitchRow("Invert display", d.invertDisplay) { vm.setDisplayInvert(it) }
            SwitchRow("LED enabled", d.ledEnabled) { vm.setDisplayLed(it) }
        }

        val bat = c.battery
        Section(
            "Battery",
            summary = "Sleep below ${bat.sleepVoltage} V · voltage in beacon ${bat.sendVoltage.onOff.lowercase()}",
            edited = edited { it.battery },
        ) {
            SwitchRow("Send voltage in beacon", bat.sendVoltage) { vm.setBatSendVoltage(it) }
            SwitchRow("Send on every beacon", bat.sendVoltageAlways) { vm.setBatSendVoltageAlways(it) }
            FloatField("Sleep threshold (V)", bat.sleepVoltage) { vm.setBatSleepVoltage(it) }
        }

        // ---- Advanced: rarely changed ------------------------------------------

        Text(
            "Advanced",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 16.dp, start = 4.dp)
                .semantics { heading() },
        )

        val p = c.pttTrigger
        Section(
            "PTT Trigger",
            summary = if (p.active) "On · GPIO ${p.ioPin}" else "Off",
            edited = edited { it.pttTrigger },
        ) {
            SwitchRow("PTT active", p.active) { vm.setPttActive(it) }
            IntField("GPIO pin (-1 = disabled)", p.ioPin) { vm.setPttPin(it) }
            SwitchRow("Reverse polarity", p.reverse) { vm.setPttReverse(it) }
            IntField("Pre-delay (ms)", p.preDelay) { vm.setPttPreDelay(it) }
            IntField("Post-delay (ms)", p.postDelay) { vm.setPttPostDelay(it) }
        }

        Section(
            "PHG (Station Power-Height-Gain)",
            summary = if (!supportsPhg) "Needs newer firmware" else c.phg.enabled.onOff,
            edited = edited { it.phg },
        ) {
            if (supportsPhg) {
                val phg = c.phg
                SwitchRow("PHG beacon active", phg.enabled) { vm.setPhgEnabled(it) }
                LabeledSlider("Power code", phg.power.toFloat(), 0f, 9f, steps = 8) { vm.setPhgPower(it.toInt()) }
                LabeledSlider("Height code", phg.height.toFloat(), 0f, 9f, steps = 8) { vm.setPhgHeight(it.toInt()) }
                LabeledSlider("Gain code", phg.gain.toFloat(), 0f, 9f, steps = 8) { vm.setPhgGain(it.toInt()) }
                LabeledSlider("Directivity code", phg.directivity.toFloat(), 0f, 9f, steps = 8) {
                    vm.setPhgDirectivity(it.toInt())
                }
                IntField("Beacon interval (min)", phg.beaconRate) { vm.setPhgRate(it) }
            } else {
                HelpText("Requires firmware v${FirmwareVersion.MIN_PHG} or newer$fwHint.")
            }
        }

        Section(
            "Remote Config (CourseSentry)",
            summary = if (!supportsRemoteConfig) "Needs newer firmware" else c.remoteCfg.enabled.onOff,
            edited = edited { it.remoteCfg },
        ) {
            if (supportsRemoteConfig) {
                val r = c.remoteCfg
                SwitchRow("Enable remote config", r.enabled) { vm.setRemoteCfgEnabled(it) }
                PasswordField("Passcode", r.token) { vm.setRemoteCfgToken(it) }
                IntField("Write-unlock window (sec)", r.unlockWindowSec) { vm.setRemoteCfgWindow(it) }
                HelpText(
                    "Lets the tracker be read and, after an APRS message with the correct " +
                        "passcode, written to remotely over APRS. An empty passcode disables writes.",
                )
            } else {
                HelpText("Requires firmware v${FirmwareVersion.MIN_REMOTE_CONFIG} or newer$fwHint.")
            }
        }

        Section("WiFi Access Point", summary = "Setup hotspot password", edited = edited { it.wifiAP }) {
            PasswordField("AP password", c.wifiAP.password) { vm.setWifiApPassword(it) }
        }

        Section("TCP KISS", summary = "Port ${c.tcpKISS.port}", edited = edited { it.tcpKISS }) {
            IntField("Port", c.tcpKISS.port) { vm.setTcpKissPort(it) }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Save / Discard for the config editor, shown above the bottom navigation while there
 * are unsaved changes. Lives outside the scrolling editor so it is never covered by a
 * snackbar or scrolled away, and shows progress while the tracker works.
 */
@Composable
fun ConfigSaveBar(vm: ConfigViewModel) {
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showDiscardDialog by remember { mutableStateOf(false) }
    if (!dirty && busy == null) return

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (busy) {
                    BusyAction.SAVING -> "Saving…"
                    BusyAction.DISCARDING -> "Discarding…"
                    null -> "Unsaved changes"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            OutlinedButton(onClick = { showDiscardDialog = true }, enabled = busy == null) {
                if (busy == BusyAction.DISCARDING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text("Discard")
            }
            Button(onClick = { vm.save() }, enabled = busy == null) {
                if (busy == BusyAction.SAVING) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.Save, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
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
private fun HelpText(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Collapsible settings group. Collapsed, it shows a one-line [summary] of the current
 * values; [edited] marks a group with unsaved changes. The whole header is the tap target.
 */
@Composable
private fun Section(
    title: String,
    summary: String? = null,
    edited: Boolean = false,
    /** Richer collapsed summary (e.g. with an icon); used instead of [summary] when given. */
    summaryContent: (@Composable () -> Unit)? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClickLabel = if (expanded) "Collapse" else "Expand") { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        heading()
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (edited) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Edited",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (!expanded && summaryContent != null) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            summaryContent()
                        }
                    } else if (!expanded && !summary.isNullOrBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { content() }
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

/**
 * Edit rule for a one-character field. Typing into a field that already holds a
 * character yields two characters; keep the newly typed one so the user can replace
 * the value directly instead of having to delete it first. Returns null to ignore
 * the edit (e.g. a multi-character paste).
 */
internal fun singleCharEdit(current: String, input: String): String? = when {
    input.length <= 1 -> input
    input.length == 2 && current.length == 1 ->
        if (input[0].toString() == current) input.substring(1) else input.substring(0, 1)
    else -> null
}

private val BLE_PIN_REGEX = Regex("[0-9]{4,6}")   // used with matches(), i.e. full-string match

/**
 * BLE pairing PIN editor. Only digits are accepted, and only a complete 4-6 digit
 * PIN is sent to the tracker. BLE always pairs with a 6-digit passkey, so the
 * supporting text shows the zero-padded digits the phone must be given.
 */
@Composable
private fun BlePinField(value: String, pinEnabled: Boolean, onValidPin: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val isValid = BLE_PIN_REGEX.matches(text)
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            if (s.length <= 6 && s.all { it.isDigit() }) {
                text = s
                if (BLE_PIN_REGEX.matches(s)) onValidPin(s)
            }
        },
        label = { Text("PIN (4-6 digits)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = pinEnabled,
        isError = pinEnabled && !isValid,
        supportingText = {
            Text(
                when {
                    !pinEnabled -> "PIN off: any nearby phone can connect"
                    isValid     -> "Phone passkey: ${text.padStart(6, '0')}"
                    else        -> "Enter 4 to 6 digits"
                }
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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

/**
 * Integer text field. When [range] is given, values outside it are shown as an
 * error and never passed to [onValueChange], so a half-typed number (e.g. "2"
 * on the way to "20") is not sent to firmware that would reject it.
 */
@Composable
private fun IntField(
    label: String,
    value: Int,
    range: IntRange? = null,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val isError = range != null && (parsed == null || parsed !in range)
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toIntOrNull()
                ?.takeIf { range == null || it in range }
                ?.let { onValueChange(it) }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
        supportingText = range?.let { r -> { Text("${r.first}–${r.last}") } },
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
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(current.toInt().toString(), style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onValueChangeFinished(current) },
            valueRange = min..max,
            steps = steps,
            modifier = Modifier.semantics { this.contentDescription = label },
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
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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

/** Hz as MHz with exactly three decimals, e.g. 433775000 → "433.775" (no locale: always a dot). */
internal fun formatMhz(hz: Long): String {
    val khz = (hz + 500) / 1000                  // round to the nearest kHz
    val whole = khz / 1000
    val frac = (khz % 1000).toString().padStart(3, '0')
    return "$whole.$frac"
}
