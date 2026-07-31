package com.kj7nye.lorafieldops.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.kj7nye.lorafieldops.model.AprsIsConfig
import com.kj7nye.lorafieldops.model.BatteryConfig
import com.kj7nye.lorafieldops.model.BeaconConfig
import com.kj7nye.lorafieldops.model.BluetoothConfig
import com.kj7nye.lorafieldops.model.CustomSmartBeaconConfig
import com.kj7nye.lorafieldops.model.DeviceRole
import com.kj7nye.lorafieldops.model.DigiMode
import com.kj7nye.lorafieldops.model.DisplayConfig
import com.kj7nye.lorafieldops.model.FirmwareVersion
import com.kj7nye.lorafieldops.model.FixedPositionConfig
import com.kj7nye.lorafieldops.model.GpsSource
import com.kj7nye.lorafieldops.model.LoraConfig
import com.kj7nye.lorafieldops.model.OtherConfig
import com.kj7nye.lorafieldops.model.PhgConfig
import com.kj7nye.lorafieldops.model.PttTriggerConfig
import com.kj7nye.lorafieldops.model.TcpKissConfig
import com.kj7nye.lorafieldops.model.TrackerConfig
import com.kj7nye.lorafieldops.model.WifiAPConfig
import com.kj7nye.lorafieldops.model.WifiNetworkConfig
import com.kj7nye.lorafieldops.model.WifiSTAConfig
import com.kj7nye.lorafieldops.serial.CommandResult
import com.kj7nye.lorafieldops.serial.ConnectionEvent
import com.kj7nye.lorafieldops.serial.ProtocolHandler
import com.kj7nye.lorafieldops.serial.SerialManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

// JSON parser — lenient to handle any extra fields from future firmware versions
private val json = Json { ignoreUnknownKeys = true; isLenient = true }

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val device: UsbDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/** Per-field command result for inline error display */
data class FieldError(val field: String, val message: String)

/** One AP found by `wifista scan`. */
data class WifiScanResult(val ssid: String, val rssi: Int, val secure: Boolean)

class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    val serialManager = SerialManager(app)
    private var protocol: ProtocolHandler? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _config = MutableStateFlow<TrackerConfig?>(null)
    val config: StateFlow<TrackerConfig?> = _config.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _lastError = MutableStateFlow<FieldError?>(null)
    val lastError: StateFlow<FieldError?> = _lastError.asStateFlow()

    // Parsed from the `version` command's git-describe string. Null until read,
    // or if the firmware reports something unparseable (e.g. "unknown") — treat
    // null as "can't confirm", not as "old" or "new".
    private val _firmwareVersion = MutableStateFlow<FirmwareVersion?>(null)
    val firmwareVersion: StateFlow<FirmwareVersion?> = _firmwareVersion.asStateFlow()

    private val _statusLines = MutableStateFlow<Map<String, String>>(emptyMap())
    val statusLines: StateFlow<Map<String, String>> = _statusLines.asStateFlow()

    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _wifiScanResults = MutableStateFlow<List<WifiScanResult>>(emptyList())
    val wifiScanResults: StateFlow<List<WifiScanResult>> = _wifiScanResults.asStateFlow()

    private val _wifiScanning = MutableStateFlow(false)
    val wifiScanning: StateFlow<Boolean> = _wifiScanning.asStateFlow()

    /** Currently selected log level; persists across log screen recompositions. */
    val currentLogLevel = MutableStateFlow("info")

    private var connectionEventJob: Job? = null
    private var logCollectorJob: Job? = null

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    fun connect(driver: UsbSerialDriver) {
        _connectionState.value = ConnectionState.Connecting

        val proto = ProtocolHandler(serialManager, serialManager.rxFlow)
        protocol = proto

        // Start collecting events BEFORE open() so the Opened event isn't dropped.
        // viewModelScope uses Dispatchers.Main.immediate: this launch runs immediately
        // on the main thread and suspends at collect, returning control here before
        // open() is called below — so the subscriber is active when Opened fires.
        // Track as a Job so disconnect() can cancel it before the port close triggers
        // a Lost event that would overwrite the Disconnected state we already set.
        connectionEventJob?.cancel()
        connectionEventJob = viewModelScope.launch {
            serialManager.connectionEvents.collect { event ->
                when (event) {
                    is ConnectionEvent.Opened -> {
                        _connectionState.value = ConnectionState.Connected(event.device)
                        enterSetupAndLoad(proto)
                    }
                    is ConnectionEvent.Error -> {
                        _connectionState.value = ConnectionState.Error(event.message)
                        proto.close()
                        protocol = null
                    }
                    is ConnectionEvent.Lost -> {
                        _connectionState.value = ConnectionState.Error("Disconnected: ${event.reason}")
                        proto.close()
                        protocol = null
                        snack("Device disconnected — reconnect to continue")
                    }
                }
            }
        }

        // Tap rxFlow for the live log stream. Both this collector and ProtocolHandler's
        // rxReader receive every byte since SharedFlow supports multiple subscribers.
        logCollectorJob?.cancel()
        logCollectorJob = viewModelScope.launch {
            val lineBuf = StringBuilder()
            serialManager.rxFlow.collect { bytes ->
                lineBuf.append(bytes.toString(Charsets.UTF_8))
                val raw = lineBuf.toString()
                val lastNl = raw.lastIndexOf('\n')
                if (lastNl >= 0) {
                    val complete = raw.substring(0, lastNl + 1)
                    lineBuf.clear()
                    lineBuf.append(raw.substring(lastNl + 1))
                    val newLines = complete.lines()
                        .map { it.trimEnd('\r') }
                        .filter { it.isNotEmpty() }
                    if (newLines.isNotEmpty()) {
                        _logLines.update { (it + newLines).takeLast(1000) }
                    }
                }
            }
        }

        serialManager.open(driver)
    }

    fun disconnect() {
        // Cancel the event collector FIRST so the Lost event emitted when the port
        // closes cannot overwrite the Disconnected state we're about to set.
        connectionEventJob?.cancel()
        connectionEventJob = null
        logCollectorJob?.cancel()
        logCollectorJob = null
        protocol?.close()
        protocol = null
        serialManager.close()
        _connectionState.value = ConnectionState.Disconnected
        _config.value = null
        _dirty.value = false
        _firmwareVersion.value = null
    }

    fun clearLog() { _logLines.value = emptyList() }

    private suspend fun enterSetupAndLoad(proto: ProtocolHandler) {
        when (val r = proto.enterSetupMode()) {
            is CommandResult.Timeout -> snack("Setup mode entry timed out — check baud rate")
            is CommandResult.Err     -> snack("Setup entry failed: ${r.message}")
            is CommandResult.Ok      -> { loadConfig(proto); readFirmwareVersion() }
        }
    }

    private suspend fun loadConfig(proto: ProtocolHandler) {
        when (val r = proto.exportConfig()) {
            is CommandResult.Ok -> {
                try {
                    _config.value = json.decodeFromString(TrackerConfig.serializer(), r.text)
                    _dirty.value = false
                } catch (e: Exception) {
                    snack("Config parse failed: ${e.message}")
                }
            }
            is CommandResult.Err -> snack("Export failed: ${r.message}")
            CommandResult.Timeout -> snack("Export timed out")
        }
    }

    // -------------------------------------------------------------------------
    // Save / Discard / Reboot
    // -------------------------------------------------------------------------

    fun save() = viewModelScope.launch {
        val result = protocol?.sendCommand("save") ?: return@launch
        when (result) {
            is CommandResult.Ok -> { _dirty.value = false; snack("Config saved") }
            is CommandResult.Err -> snack("Save failed: ${result.message}")
            CommandResult.Timeout -> snack("Save timed out")
        }
    }

    fun discard() = viewModelScope.launch {
        val result = protocol?.sendCommand("discard") ?: return@launch
        when (result) {
            is CommandResult.Ok -> { snack("Discarded — device rebooting"); _dirty.value = false }
            is CommandResult.Err -> snack("Discard failed: ${result.message}")
            CommandResult.Timeout -> snack("Discard timed out")
        }
    }

    fun reboot() = viewModelScope.launch {
        protocol?.sendCommand("reboot")
        snack("Rebooting…")
        disconnect()
    }

    // -------------------------------------------------------------------------
    // Export / Import
    // -------------------------------------------------------------------------

    suspend fun exportRaw(): String? {
        return when (val r = protocol?.exportConfig()) {
            is CommandResult.Ok -> r.text
            else -> { snack("Export failed"); null }
        }
    }

    fun importJson(jsonText: String) = viewModelScope.launch {
        snack("Sending config…")
        when (val r = protocol?.importConfig(jsonText)) {
            is CommandResult.Ok -> snack("Import successful — device rebooting")
            is CommandResult.Err -> snack("Import failed: ${r?.message}")
            CommandResult.Timeout -> snack("Import timed out")
            null -> snack("Not connected")
        }
    }

    // -------------------------------------------------------------------------
    // Live status reads
    // -------------------------------------------------------------------------

    fun readBattery() = viewModelScope.launch {
        val r = protocol?.sendCommand("bat read") ?: return@launch
        if (r is CommandResult.Ok) {
            // Firmware outputs: bat.voltage=X.XX bat.percent=YY
            val lines = _statusLines.value.toMutableMap()
            r.text.split(" ").forEach { token ->
                val parts = token.split("=")
                if (parts.size == 2) lines[parts[0]] = parts[1]
            }
            _statusLines.value = lines
        }
    }

    fun readGps() = viewModelScope.launch {
        val r = protocol?.sendCommand("gps read") ?: return@launch
        if (r is CommandResult.Ok) {
            val lines = _statusLines.value.toMutableMap()
            lines["gps"] = r.text
            _statusLines.value = lines
        }
    }

    fun readAprsIsStatus() = viewModelScope.launch {
        val r = protocol?.sendCommand("aprsiss status") ?: return@launch
        if (r is CommandResult.Ok) {
            val lines = _statusLines.value.toMutableMap()
            lines["aprsIS.connected"] = if (r.text.contains("true")) "true" else "false"
            _statusLines.value = lines
        }
    }

    fun readWifiStaStatus() = viewModelScope.launch {
        val r = protocol?.sendCommand("wifista status") ?: return@launch
        if (r is CommandResult.Ok) {
            val lines = _statusLines.value.toMutableMap()
            lines["wifiSTA.connected"] = if (r.text.contains("true")) "true" else "false"
            _statusLines.value = lines
        }
    }

    /**
     * Runs the firmware's blocking 2-4s WiFi scan and parses the
     * `wifiSTA.scan: rssi=X secure=0|1 ssid=Y` lines it prints.
     */
    fun scanWifiNetworks() = viewModelScope.launch {
        _wifiScanning.value = true
        val r = protocol?.sendCommand("wifista scan")
        when (r) {
            is CommandResult.Ok -> _wifiScanResults.value = r.text.lines()
                .filter { it.startsWith("wifiSTA.scan:") }
                .mapNotNull { line ->
                    val rssi = Regex("rssi=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val secure = Regex("secure=(\\d)").find(line)?.groupValues?.get(1) == "1"
                    // ssid= is last on the line and unbounded so SSIDs containing spaces aren't truncated.
                    val ssid = Regex("ssid=(.*)$").find(line)?.groupValues?.get(1)
                    if (rssi != null && ssid != null) WifiScanResult(ssid, rssi, secure) else null
                }
            is CommandResult.Err -> snack("WiFi scan failed: ${r.message}")
            CommandResult.Timeout -> snack("WiFi scan timed out")
            null -> snack("Not connected")
        }
        _wifiScanning.value = false
    }

    fun readFirmwareVersion() = viewModelScope.launch {
        val r = protocol?.sendCommand("version") ?: return@launch
        if (r is CommandResult.Ok) {
            val versionText = r.text.removePrefix("version.date=").trim()
            val lines = _statusLines.value.toMutableMap()
            lines["version"] = versionText
            _statusLines.value = lines
            _firmwareVersion.value = FirmwareVersion.parse(versionText)
        }
    }

    fun setLogLevel(level: String) = viewModelScope.launch {
        protocol?.sendCommand("log $level")
        currentLogLevel.value = level
    }

    /** Fires an out-of-cycle comment beacon; does not affect the normal beacon timer. */
    fun txCommentNow() = viewModelScope.launch {
        when (val r = protocol?.sendCommand("tx comment")) {
            is CommandResult.Ok ->
                if (r.text.startsWith("ERR")) snack("TX failed: ${r.text}") else snack("Comment beacon sent")
            is CommandResult.Err -> snack("TX failed: ${r.message}")
            CommandResult.Timeout -> snack("TX timed out")
            null -> snack("Not connected")
        }
    }

    /** Fires an out-of-cycle status beacon; does not affect the normal beacon timer. */
    fun txStatusNow() = viewModelScope.launch {
        when (val r = protocol?.sendCommand("tx status")) {
            is CommandResult.Ok ->
                if (r.text.startsWith("ERR")) snack("TX failed: ${r.text}") else snack("Status beacon sent")
            is CommandResult.Err -> snack("TX failed: ${r.message}")
            CommandResult.Timeout -> snack("TX timed out")
            null -> snack("Not connected")
        }
    }

    /**
     * Resets an nRF52 tracker into the Nordic OTA DFU bootloader. ESP32 boards don't
     * build this command in, so the firmware replies "unknown command" there instead
     * of rebooting — checked explicitly so we don't disconnect a device that never left.
     * On success the device drops off USB serial once it enters DFU mode, so we
     * disconnect right after — reflashing continues via the nRF Connect app.
     */
    fun triggerOtaDfu() = viewModelScope.launch {
        when (val r = protocol?.sendCommand("otadfu")) {
            is CommandResult.Ok ->
                if (r.text.contains("unknown command")) {
                    snack("OTA DFU is only available on nRF52 boards")
                } else {
                    snack("Entering OTA DFU mode — use nRF Connect to upload firmware")
                    disconnect()
                }
            is CommandResult.Err -> snack("OTA DFU failed: ${r.message}")
            CommandResult.Timeout -> snack("OTA DFU timed out")
            null -> snack("Not connected")
        }
    }

    // -------------------------------------------------------------------------
    // Per-field command senders
    // Each mutates the local config state immediately for snappy UI, then sends
    // the CLI command. An ERR: response sets lastError for inline display.
    // -------------------------------------------------------------------------

    // -- Beacon --

    fun setCallsign(v: String) = sendField("beacon callsign $v") {
        updateBeacon { copy(callsign = v.uppercase().trim()) }
    }
    fun setSymbol(v: String) = sendField("beacon symbol $v") {
        updateBeacon { copy(symbol = v) }
    }
    fun setOverlay(v: String) = sendField("beacon overlay $v") {
        updateBeacon { copy(overlay = v) }
    }
    fun setMicE(v: String) = sendField("beacon mice $v") {
        updateBeacon { copy(micE = v) }
    }
    fun setComment(v: String) = sendField("beacon comment $v") {
        updateBeacon { copy(comment = v) }
    }
    fun setStatus(v: String) = sendField("beacon status $v") {
        updateBeacon { copy(status = v) }
    }
    fun setTacticalCallsign(v: String) = sendField("beacon tactical $v") {
        updateBeacon { copy(tacticalCallsign = v.take(9)) }
    }
    fun setProfileLabel(v: String) = sendField("beacon label $v") {
        updateBeacon { copy(profileLabel = v) }
    }
    fun setSmartBeaconActive(v: Boolean) = sendField("beacon smart ${v.onOff}") {
        updateBeacon { copy(smartBeaconActive = v) }
    }
    fun setSmartBeaconSetting(v: Int) = sendField("beacon smartset $v") {
        updateBeacon { copy(smartBeaconSetting = v) }
    }

    // -- LoRa --

    fun setLoraFreq(v: Long) = sendField("lora freq $v") {
        updateLora { copy(frequency = v) }
    }
    fun setLoraSf(v: Int) = sendField("lora sf $v") {
        updateLora { copy(spreadingFactor = v) }
    }
    fun setLoraBw(v: Long) = sendField("lora bw $v") {
        updateLora { copy(signalBandwidth = v) }
    }
    fun setLoraCr(v: Int) = sendField("lora cr $v") {
        updateLora { copy(codingRate4 = v) }
    }
    fun setLoraPower(v: Int) = sendField("lora power $v") {
        updateLora { copy(power = v) }
    }

    // -- SmartBeacon custom --

    fun setSmartSlowRate(v: Int) = sendField("smartcustom slowrate $v") {
        updateCustomSB { copy(slowRate = v) }
    }
    fun setSmartSlowSpeed(v: Int) = sendField("smartcustom slowspeed $v") {
        updateCustomSB { copy(slowSpeed = v) }
    }
    fun setSmartFastRate(v: Int) = sendField("smartcustom fastrate $v") {
        updateCustomSB { copy(fastRate = v) }
    }
    fun setSmartFastSpeed(v: Int) = sendField("smartcustom fastspeed $v") {
        updateCustomSB { copy(fastSpeed = v) }
    }
    fun setSmartTurnMinDeg(v: Int) = sendField("smartcustom turnmindeg $v") {
        updateCustomSB { copy(turnMinDeg = v) }
    }
    fun setSmartTurnSlope(v: Int) = sendField("smartcustom turnslope $v") {
        updateCustomSB { copy(turnSlope = v) }
    }

    // -- Display --

    fun setDisplayEco(v: Boolean) = sendField("display eco ${v.onOff}") {
        updateDisplay { copy(ecoMode = v) }
    }
    fun setDisplayTimeout(v: Int) = sendField("display timeout $v") {
        updateDisplay { copy(timeout = v) }
    }
    fun setDisplayTurn180(v: Boolean) = sendField("display turn180 ${v.onOff}") {
        updateDisplay { copy(turn180 = v) }
    }
    fun setDisplayInvert(v: Boolean) = sendField("display invert ${v.onOff}") {
        updateDisplay { copy(invertDisplay = v) }
    }
    fun setDisplayLed(v: Boolean) = sendField("display led ${v.onOff}") {
        updateDisplay { copy(ledEnabled = v) }
    }

    // -- Bluetooth --

    fun setBtActive(v: Boolean) = sendField("bt ${v.onOff}") {
        updateBt { copy(active = v) }
    }
    fun setBtName(v: String) = sendField("bt name $v") {
        updateBt { copy(deviceName = v) }
    }

    // -- Battery --

    fun setBatSendVoltage(v: Boolean) = sendField("bat sendv ${v.onOff}") {
        updateBat { copy(sendVoltage = v) }
    }
    fun setBatSendVoltageAlways(v: Boolean) = sendField("bat alwaysv ${v.onOff}") {
        updateBat { copy(sendVoltageAlways = v) }
    }
    fun setBatSleepVoltage(v: Float) = sendField("bat sleepv $v") {
        updateBat { copy(sleepVoltage = v) }
    }

    // -- PTT --

    fun setPttActive(v: Boolean) = sendField("ptt ${v.onOff}") {
        updatePtt { copy(active = v) }
    }
    fun setPttPin(v: Int) = sendField("ptt pin $v") {
        updatePtt { copy(ioPin = v) }
    }
    fun setPttReverse(v: Boolean) = sendField("ptt reverse ${v.onOff}") {
        updatePtt { copy(reverse = v) }
    }
    fun setPttPreDelay(v: Int) = sendField("ptt predelay $v") {
        updatePtt { copy(preDelay = v) }
    }
    fun setPttPostDelay(v: Int) = sendField("ptt postdelay $v") {
        updatePtt { copy(postDelay = v) }
    }

    // -- PHG (Power-Height-Gain-Directivity beacon) --

    fun setPhgEnabled(v: Boolean) = sendField("phg ${v.onOff}") {
        updatePhg { copy(enabled = v) }
    }
    fun setPhgPower(v: Int) = sendField("phg power $v") {
        updatePhg { copy(power = v) }
    }
    fun setPhgHeight(v: Int) = sendField("phg height $v") {
        updatePhg { copy(height = v) }
    }
    fun setPhgGain(v: Int) = sendField("phg gain $v") {
        updatePhg { copy(gain = v) }
    }
    fun setPhgDirectivity(v: Int) = sendField("phg dir $v") {
        updatePhg { copy(directivity = v) }
    }
    fun setPhgRate(v: Int) = sendField("phg rate $v") {
        updatePhg { copy(beaconRate = v) }
    }

    // -- WiFi AP --

    fun setWifiApPassword(v: String) = sendField("wifi password $v") {
        update { copy(wifiAP = WifiAPConfig(password = v)) }
    }

    // -- WiFi STA --

    fun setWifiStaEnabled(v: Boolean) = sendField("wifista ${v.onOff}") {
        updateWifiSta { copy(enabled = v) }
    }
    // Firmware rejects a blank `wifista add` SSID, so an empty tap (the "+ Add
    // network" button) falls back to a placeholder the user then edits in place.
    fun addWifiStaNetwork(ssid: String) {
        val effectiveSsid = ssid.ifBlank { "NEW-NETWORK" }
        sendField("wifista add $effectiveSsid") {
            updateWifiSta { copy(networks = networks + WifiNetworkConfig(ssid = effectiveSsid)) }
        }
    }
    fun removeWifiStaNetwork(i: Int) = sendField("wifista remove $i") {
        updateWifiSta { copy(networks = networks.filterIndexed { idx, _ -> idx != i }) }
    }
    fun setWifiStaSsid(i: Int, v: String) = sendField("wifista ssid $i $v") {
        updateWifiSta { copy(networks = networks.mapIndexed { idx, n -> if (idx == i) n.copy(ssid = v) else n }) }
    }
    fun setWifiStaPassword(i: Int, v: String) = sendField("wifista password $i $v") {
        updateWifiSta { copy(networks = networks.mapIndexed { idx, n -> if (idx == i) n.copy(password = v) else n }) }
    }

    // -- APRS-IS --

    fun setAprsIsServer(v: String) = sendField("aprsiss server $v") {
        updateAprsIs { copy(server = v) }
    }
    fun setAprsIsPort(v: Int) = sendField("aprsiss port $v") {
        updateAprsIs { copy(port = v) }
    }
    fun setAprsIsPasscode(v: String) = sendField("aprsiss passcode $v") {
        updateAprsIs { copy(passcode = v) }
    }
    fun setAprsIsFilter(v: String) = sendField("aprsiss filter $v") {
        updateAprsIs { copy(filter = v) }
    }
    fun setAprsIsDownlink(v: Boolean) = sendField("aprsiss downlink ${v.onOff}") {
        updateAprsIs { copy(downlinkEnabled = v) }
    }

    // -- TCP KISS --

    fun setTcpKissPort(v: Int) = sendField("tcpkiss port $v") {
        update { copy(tcpKISS = TcpKissConfig(port = v)) }
    }

    // -- Role & GPS --

    fun setDeviceRole(v: Int) = sendField("role set ${DeviceRole.toCliString(v)}") {
        update { copy(deviceRole = v) }
    }
    fun setGpsSource(v: Int) = sendField("role gps ${GpsSource.toCliString(v)}") {
        update { copy(gpsSource = v) }
    }

    // -- Fixed position --

    fun setFixedLat(v: Float) = sendField("fixed latitude $v") {
        updateFixed { copy(latitude = v) }
    }
    fun setFixedLon(v: Float) = sendField("fixed longitude $v") {
        updateFixed { copy(longitude = v) }
    }
    fun setFixedElev(v: Float) = sendField("fixed elevation $v") {
        updateFixed { copy(elevation = v) }
    }

    // -- Other --

    fun setBeaconPath(v: String) = sendField("beaconpath $v") {
        updateOther { copy(beaconPath = v) }
    }
    fun setNonSmartRate(v: Int) = sendField("nonsmartrate $v") {
        updateOther { copy(nonSmartBeaconRate = v) }
    }
    fun setSendCommentAfter(v: Int) = sendField("commentafter $v") {
        updateOther { copy(sendCommentAfterXBeacons = v) }
    }
    fun setSendAltitude(v: Boolean) = sendField("sendalt ${v.onOff}") {
        updateOther { copy(sendAltitude = v) }
    }
    fun setSendSpeedCourse(v: Boolean) = sendField("sendspeed ${v.onOff}") {
        updateOther { copy(sendSpeedCourse = v) }
    }
    fun setDigiMode(v: Int) = sendField("digi ${DigiMode.toCliString(v)}") {
        updateOther { copy(digiMode = v) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun clearSnack() { _snackMessage.value = null }
    fun clearFieldError() { _lastError.value = null }

    private fun snack(msg: String) { _snackMessage.value = msg }

    /**
     * Applies [localUpdate] immediately to _config, then sends [cmd] in background.
     *
     * The local update is optimistic — applied before the firmware has actually
     * confirmed it — so on rejection the UI can be briefly out of sync with the
     * device until the user notices the error and corrects the field. A firmware
     * rejection comes back as CommandResult.Ok (the prompt still returns
     * normally) with an "ERR" response line, NOT as CommandResult.Err — that
     * variant is reserved for local I/O failures. Older/mismatched firmware is
     * exactly where this rejection path matters most, so it must actually
     * reach the user instead of being swallowed.
     */
    private fun sendField(cmd: String, localUpdate: TrackerConfig.() -> Unit) =
        viewModelScope.launch {
            _config.value?.localUpdate() ?: return@launch
            _dirty.value = true
            val field = cmd.substringBefore(" ")
            when (val result = protocol?.sendCommand(cmd)) {
                is CommandResult.Ok -> if (result.text.startsWith("ERR")) {
                    _lastError.value = FieldError(field, result.text)
                    snack("Rejected by firmware: ${result.text}")
                }
                is CommandResult.Err -> {
                    _lastError.value = FieldError(field, result.message)
                    snack("Command failed: ${result.message}")
                }
                CommandResult.Timeout -> {
                    _lastError.value = FieldError(field, "timed out")
                    snack("Command timed out: $cmd")
                }
                null -> snack("Not connected")
            }
        }

    private fun update(fn: TrackerConfig.() -> TrackerConfig) {
        _config.value = _config.value?.fn()
    }
    private fun updateBeacon(fn: BeaconConfig.() -> BeaconConfig) =
        update { copy(beacons = listOf(beacons[0].fn())) }
    private fun updateLora(fn: LoraConfig.() -> LoraConfig) =
        update { copy(lora = listOf(lora[0].fn())) }
    private fun updateDisplay(fn: DisplayConfig.() -> DisplayConfig) =
        update { copy(display = display.fn()) }
    private fun updateBt(fn: BluetoothConfig.() -> BluetoothConfig) =
        update { copy(bluetooth = bluetooth.fn()) }
    private fun updateBat(fn: BatteryConfig.() -> BatteryConfig) =
        update { copy(battery = battery.fn()) }
    private fun updatePtt(fn: PttTriggerConfig.() -> PttTriggerConfig) =
        update { copy(pttTrigger = pttTrigger.fn()) }
    private fun updatePhg(fn: PhgConfig.() -> PhgConfig) =
        update { copy(phg = phg.fn()) }
    private fun updateWifiSta(fn: WifiSTAConfig.() -> WifiSTAConfig) =
        update { copy(wifiSTA = wifiSTA.fn()) }
    private fun updateAprsIs(fn: AprsIsConfig.() -> AprsIsConfig) =
        update { copy(aprsIS = aprsIS.fn()) }
    private fun updateFixed(fn: FixedPositionConfig.() -> FixedPositionConfig) =
        update { copy(fixedPosition = fixedPosition.fn()) }
    private fun updateOther(fn: OtherConfig.() -> OtherConfig) =
        update { copy(other = other.fn()) }
    private fun updateCustomSB(fn: CustomSmartBeaconConfig.() -> CustomSmartBeaconConfig) =
        update { copy(customSmartBeacon = customSmartBeacon.fn()) }
}

private val Boolean.onOff get() = if (this) "on" else "off"
