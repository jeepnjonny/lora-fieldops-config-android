package com.kj7nye.lorafieldops.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
import com.kj7nye.lorafieldops.serial.WIFI_SCAN_TIMEOUT_MS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

// JSON parser — lenient to handle any extra fields from future firmware versions
private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// Typed fields (free text / numbers) wait this long after the last keystroke before
// actually sending — otherwise "KG7KMV-9" fires 8 separate serial round-trips, one
// per character, each serialized through the command queue.
private const val FIELD_DEBOUNCE_MS = 500L

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

    private val _locationFetching = MutableStateFlow(false)
    val locationFetching: StateFlow<Boolean> = _locationFetching.asStateFlow()

    /** Currently selected log level; persists across log screen recompositions. */
    val currentLogLevel = MutableStateFlow("info")

    // Backing store for _logLines — evicts from the front instead of rebuilding
    // a full `it + newLines` list (up to 1000+ elements) on every chunk.
    private val logBuffer = ArrayDeque<String>(1000)

    private var connectionEventJob: Job? = null
    private var logCollectorJob: Job? = null
    private var locationListener: LocationListener? = null
    private var locationTimeoutJob: Job? = null

    // Pending debounced sends, keyed by field (e.g. "beacon callsign") — a fresh edit
    // to the same field cancels and restarts the timer instead of queuing another send.
    private val fieldDebounceJobs = mutableMapOf<String, Job>()

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
                        newLines.forEach { line ->
                            if (logBuffer.size >= 1000) logBuffer.removeFirst()
                            logBuffer.addLast(line)
                        }
                        _logLines.value = logBuffer.toList()
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
        fieldDebounceJobs.values.forEach { it.cancel() }
        fieldDebounceJobs.clear()
        protocol?.close()
        protocol = null
        serialManager.close()
        _connectionState.value = ConnectionState.Disconnected
        _config.value = null
        _dirty.value = false
    }

    fun clearLog() { logBuffer.clear(); _logLines.value = emptyList() }

    override fun onCleared() {
        stopLocationFetch()
        super.onCleared()
    }

    private suspend fun enterSetupAndLoad(proto: ProtocolHandler) {
        when (val r = proto.enterSetupMode()) {
            is CommandResult.Timeout -> snack("Setup mode entry timed out — check baud rate")
            is CommandResult.Err     -> snack("Setup entry failed: ${r.message}")
            is CommandResult.Ok      -> loadConfig(proto)
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
        // Fail fast on malformed JSON instead of waiting up to 30s for the device
        // to reject it — importConfig() only completes on the import-success marker.
        try {
            json.decodeFromString(TrackerConfig.serializer(), jsonText)
        } catch (e: Exception) {
            snack("Invalid config JSON: ${e.message}")
            return@launch
        }
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
        val r = protocol?.sendCommand("wifista scan", timeoutMs = WIFI_SCAN_TIMEOUT_MS)
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
            val lines = _statusLines.value.toMutableMap()
            lines["version"] = r.text.removePrefix("version.date=").trim()
            _statusLines.value = lines
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

    fun setCallsign(v: String) = sendField("beacon callsign $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(callsign = v.uppercase().trim()) }
    }
    fun setSymbol(v: String) = sendField("beacon symbol $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(symbol = v) }
    }
    fun setOverlay(v: String) = sendField("beacon overlay $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(overlay = v) }
    }
    fun setMicE(v: String) = sendField("beacon mice $v") {
        updateBeacon { copy(micE = v) }
    }
    fun setComment(v: String) = sendField("beacon comment $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(comment = v) }
    }
    fun setStatus(v: String) = sendField("beacon status $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(status = v) }
    }
    fun setTacticalCallsign(v: String) = sendField("beacon tactical $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(tacticalCallsign = v.take(9)) }
    }
    fun setProfileLabel(v: String) = sendField("beacon label $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(profileLabel = v) }
    }
    fun setSmartBeaconActive(v: Boolean) = sendField("beacon smart ${v.onOff}") {
        updateBeacon { copy(smartBeaconActive = v) }
    }
    fun setSmartBeaconSetting(v: Int) = sendField("beacon smartset $v") {
        updateBeacon { copy(smartBeaconSetting = v) }
    }

    // -- LoRa --

    fun setLoraFreq(v: Long) = sendField("lora freq $v", debounceMs = FIELD_DEBOUNCE_MS) {
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

    fun setSmartSlowRate(v: Int) = sendField("smartcustom slowrate $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateCustomSB { copy(slowRate = v) }
    }
    fun setSmartSlowSpeed(v: Int) = sendField("smartcustom slowspeed $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateCustomSB { copy(slowSpeed = v) }
    }
    fun setSmartFastRate(v: Int) = sendField("smartcustom fastrate $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateCustomSB { copy(fastRate = v) }
    }
    fun setSmartFastSpeed(v: Int) = sendField("smartcustom fastspeed $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateCustomSB { copy(fastSpeed = v) }
    }
    fun setSmartTurnMinDeg(v: Int) = sendField("smartcustom turnmindeg $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateCustomSB { copy(turnMinDeg = v) }
    }
    fun setSmartTurnSlope(v: Int) = sendField("smartcustom turnslope $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateCustomSB { copy(turnSlope = v) }
    }

    // -- Display --

    fun setDisplayEco(v: Boolean) = sendField("display eco ${v.onOff}") {
        updateDisplay { copy(ecoMode = v) }
    }
    fun setDisplayTimeout(v: Int) = sendField("display timeout $v", debounceMs = FIELD_DEBOUNCE_MS) {
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
    fun setBtName(v: String) = sendField("bt name $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBt { copy(deviceName = v) }
    }

    // -- Battery --

    fun setBatSendVoltage(v: Boolean) = sendField("bat sendv ${v.onOff}") {
        updateBat { copy(sendVoltage = v) }
    }
    fun setBatSendVoltageAlways(v: Boolean) = sendField("bat alwaysv ${v.onOff}") {
        updateBat { copy(sendVoltageAlways = v) }
    }
    fun setBatSleepVoltage(v: Float) = sendField("bat sleepv $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBat { copy(sleepVoltage = v) }
    }

    // -- PTT --

    fun setPttActive(v: Boolean) = sendField("ptt ${v.onOff}") {
        updatePtt { copy(active = v) }
    }
    fun setPttPin(v: Int) = sendField("ptt pin $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updatePtt { copy(ioPin = v) }
    }
    fun setPttReverse(v: Boolean) = sendField("ptt reverse ${v.onOff}") {
        updatePtt { copy(reverse = v) }
    }
    fun setPttPreDelay(v: Int) = sendField("ptt predelay $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updatePtt { copy(preDelay = v) }
    }
    fun setPttPostDelay(v: Int) = sendField("ptt postdelay $v", debounceMs = FIELD_DEBOUNCE_MS) {
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
    fun setPhgRate(v: Int) = sendField("phg rate $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updatePhg { copy(beaconRate = v) }
    }

    // -- WiFi AP --

    fun setWifiApPassword(v: String) = sendField("wifi password $v", debounceMs = FIELD_DEBOUNCE_MS) {
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
    fun setWifiStaSsid(i: Int, v: String) = sendField("wifista ssid $i $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateWifiSta { copy(networks = networks.mapIndexed { idx, n -> if (idx == i) n.copy(ssid = v) else n }) }
    }
    fun setWifiStaPassword(i: Int, v: String) = sendField("wifista password $i $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateWifiSta { copy(networks = networks.mapIndexed { idx, n -> if (idx == i) n.copy(password = v) else n }) }
    }

    // -- APRS-IS --

    fun setAprsIsServer(v: String) = sendField("aprsiss server $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateAprsIs { copy(server = v) }
    }
    fun setAprsIsPort(v: Int) = sendField("aprsiss port $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateAprsIs { copy(port = v) }
    }
    fun setAprsIsPasscode(v: String) = sendField("aprsiss passcode $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateAprsIs { copy(passcode = v) }
    }
    fun setAprsIsFilter(v: String) = sendField("aprsiss filter $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateAprsIs { copy(filter = v) }
    }
    fun setAprsIsDownlink(v: Boolean) = sendField("aprsiss downlink ${v.onOff}") {
        updateAprsIs { copy(downlinkEnabled = v) }
    }

    // -- TCP KISS --

    fun setTcpKissPort(v: Int) = sendField("tcpkiss port $v", debounceMs = FIELD_DEBOUNCE_MS) {
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

    fun setFixedLat(v: Float) = sendField("fixed latitude $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateFixed { copy(latitude = v) }
    }
    fun setFixedLon(v: Float) = sendField("fixed longitude $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateFixed { copy(longitude = v) }
    }
    fun setFixedElev(v: Float) = sendField("fixed elevation $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateFixed { copy(elevation = v) }
    }

    /**
     * Fetches a one-shot device location fix via [LocationManager] — no Play Services
     * dependency, consistent with the rest of this app — and fills the fixed lat/lon/
     * elevation fields from it. Caller must have already confirmed ACCESS_FINE_LOCATION
     * (or ACCESS_COARSE_LOCATION) is granted; this method assumes that check was done.
     */
    @SuppressLint("MissingPermission")
    fun useDeviceLocation() {
        if (_locationFetching.value) return
        val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            snack("Enable device location (GPS) to use this")
            return
        }

        _locationFetching.value = true
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = applyDeviceLocation(location)
        }
        locationListener = listener
        // Providers can hang indefinitely indoors/without a fix — give up after 20s.
        locationTimeoutJob = viewModelScope.launch {
            delay(20_000)
            stopLocationFetch()
            snack("Timed out waiting for a location fix")
        }
        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
    }

    private fun applyDeviceLocation(location: Location) {
        stopLocationFetch()
        setFixedLat(location.latitude.toFloat())
        setFixedLon(location.longitude.toFloat())
        if (location.hasAltitude()) setFixedElev(location.altitude.toFloat())
        val acc = if (location.hasAccuracy()) " (±${location.accuracy.roundToInt()}m)" else ""
        snack("Location filled$acc — review and Save.")
    }

    fun locationPermissionDenied() {
        snack("Location permission denied — can't fill in device location")
    }

    private fun stopLocationFetch() {
        locationTimeoutJob?.cancel()
        locationTimeoutJob = null
        locationListener?.let { listener ->
            val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(listener)
        }
        locationListener = null
        _locationFetching.value = false
    }

    // -- Other --

    fun setBeaconPath(v: String) = sendField("beaconpath $v") {
        updateOther { copy(beaconPath = v) }
    }
    fun setNonSmartRate(v: Int) = sendField("nonsmartrate $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateOther { copy(nonSmartBeaconRate = v) }
    }
    fun setSendCommentAfter(v: Int) = sendField("commentafter $v", debounceMs = FIELD_DEBOUNCE_MS) {
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
     * Applies [localUpdate] immediately to _config for a responsive UI, then sends [cmd].
     *
     * [debounceMs] > 0 coalesces rapid repeated calls for the same field (e.g. one per
     * keystroke while typing a callsign) into a single send after the user pauses —
     * only the final value goes over the wire instead of one command per character.
     * Toggles/dropdowns/sliders fire once per user action already, so they pass the
     * default 0 (send immediately).
     *
     * If the firmware rejects the value or the command times out, [lastError] is set
     * and the local config is re-synced from the device so the UI doesn't keep showing
     * a value the device never actually accepted.
     */
    private fun sendField(cmd: String, debounceMs: Long = 0L, localUpdate: TrackerConfig.() -> Unit) {
        _config.value?.localUpdate() ?: return
        _dirty.value = true

        val fieldKey = cmd.substringBeforeLast(" ")
        fieldDebounceJobs.remove(fieldKey)?.cancel()

        if (debounceMs <= 0L) {
            viewModelScope.launch { dispatchField(cmd, fieldKey) }
        } else {
            fieldDebounceJobs[fieldKey] = viewModelScope.launch {
                delay(debounceMs)
                fieldDebounceJobs.remove(fieldKey)
                dispatchField(cmd, fieldKey)
            }
        }
    }

    private suspend fun dispatchField(cmd: String, fieldKey: String) {
        when (val result = protocol?.sendCommand(cmd)) {
            is CommandResult.Err -> {
                _lastError.value = FieldError(fieldKey, result.message)
                resyncFromDevice()
            }
            CommandResult.Timeout -> {
                _lastError.value = FieldError(fieldKey, "timed out — re-checking device")
                resyncFromDevice()
            }
            is CommandResult.Ok -> {
                if (_lastError.value?.field == fieldKey) _lastError.value = null
            }
            null -> {}
        }
    }

    /**
     * Re-fetches the live config from the device so a rejected/timed-out field's
     * optimistic local value (applied immediately by [sendField], before the firmware
     * had a chance to confirm it) is corrected back to what the device actually holds.
     * Best-effort: if the resync itself fails, the previous local state is left as-is
     * rather than being cleared.
     */
    private suspend fun resyncFromDevice() {
        val proto = protocol ?: return
        val r = proto.exportConfig()
        if (r is CommandResult.Ok) {
            try {
                _config.value = json.decodeFromString(TrackerConfig.serializer(), r.text)
            } catch (_: Exception) {
            }
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
