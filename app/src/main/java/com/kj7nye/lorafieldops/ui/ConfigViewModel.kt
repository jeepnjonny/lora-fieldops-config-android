package com.kj7nye.lorafieldops.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.kj7nye.lorafieldops.logger.CoverageLogger
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
import com.kj7nye.lorafieldops.model.KissConfig
import com.kj7nye.lorafieldops.model.LoraConfig
import com.kj7nye.lorafieldops.model.OtherConfig
import com.kj7nye.lorafieldops.model.PhgConfig
import com.kj7nye.lorafieldops.model.PttTriggerConfig
import com.kj7nye.lorafieldops.model.RemoteCfgConfig
import com.kj7nye.lorafieldops.model.TcpKissConfig
import com.kj7nye.lorafieldops.model.TrackerConfig
import com.kj7nye.lorafieldops.model.WifiAPConfig
import com.kj7nye.lorafieldops.model.WifiNetworkConfig
import com.kj7nye.lorafieldops.model.WifiSTAConfig
import com.kj7nye.lorafieldops.model.settingChangeNotes
import com.kj7nye.lorafieldops.serial.BleScanner
import com.kj7nye.lorafieldops.serial.BleTrackerInfo
import com.kj7nye.lorafieldops.serial.BleTransport
import com.kj7nye.lorafieldops.serial.CommandResult
import com.kj7nye.lorafieldops.serial.ConnectionEvent
import com.kj7nye.lorafieldops.serial.DeviceTransport
import com.kj7nye.lorafieldops.serial.LineAssembler
import com.kj7nye.lorafieldops.serial.ProtocolHandler
import com.kj7nye.lorafieldops.serial.SerialManager
import com.kj7nye.lorafieldops.serial.TransportKind
import com.kj7nye.lorafieldops.serial.WIFI_SCAN_TIMEOUT_MS
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

// JSON parser — lenient to handle any extra fields from future firmware versions
private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// Typed fields (free text / numbers) wait this long after the last keystroke before
// actually sending — otherwise "KG7KMV-9" fires 8 separate serial round-trips, one
// per character, each serialized through the command queue.
private const val FIELD_DEBOUNCE_MS = 500L

/** BLE replies arrive as many small notifications; stretch every protocol timeout by this much. */
private const val BLE_TIMEOUT_SCALE = 3L

/** Coverage logger auto-reconnect: first retry after this long, doubling up to the max. */
private const val LOGGER_RECONNECT_FIRST_MS = 5_000L
private const val LOGGER_RECONNECT_MAX_MS   = 60_000L

/** Firmware note printed on setup entry when a dropped session left unsaved edits in RAM. */
private const val INTERRUPTED_SESSION_NOTE = "unsaved edits from an interrupted session"

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val message: String = "Entering setup mode…") : ConnectionState()
    data class Connected(val label: String, val kind: TransportKind) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/** Per-field command result for inline error display */
data class FieldError(val field: String, val message: String)

/** One AP found by `wifista scan`. */
data class WifiScanResult(val ssid: String, val rssi: Int, val secure: Boolean)

/** A link the user chose, kept so it can be retried. */
private sealed interface LinkTarget {
    data class Usb(val driver: UsbSerialDriver) : LinkTarget
    data class Ble(val tracker: BleTrackerInfo) : LinkTarget
}

/** A Status screen card, for its loading spinner. */
enum class StatusGroup { BATTERY, GPS, APRS_IS, WIFI, VERSION }

/** A Save or Discard the tracker is working on. */
enum class BusyAction { SAVING, DISCARDING }

/** Progress of a config import from the Import sheet. */
sealed class ImportState {
    object Idle : ImportState()
    object Sending : ImportState()
    object Done : ImportState()
    data class Failed(val message: String) : ImportState()
}

/** Out-of-cycle beacons the tracker sends on request (`tx comment|status`). */
enum class TxBeacon(val cli: String, val label: String) {
    COMMENT("comment", "Comment"),
    STATUS("status", "Status"),
}

class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    val serialManager = SerialManager(app)
    val bleScanner = BleScanner(app)
    private var protocol: ProtocolHandler? = null
    /** Optional coverage logger; does nothing unless the user switched it on. */
    val coverageLogger = CoverageLogger.get(app)
    /** Last tracker connected over BLE, for the logger's automatic reconnect. */
    private var lastBleTracker: BleTrackerInfo? = null
    private var reconnectJob: Job? = null
    /** Link the user last opened, for [retryConnection]. */
    private var lastTarget: LinkTarget? = null
    private val _retryLabel = MutableStateFlow<String?>(null)
    /** Label for the connection screen's Retry button; null when there is nothing to retry. */
    val retryLabel: StateFlow<String?> = _retryLabel.asStateFlow()
    /** Config as last loaded from / saved to flash; the "before" side of setting-change notes. */
    private val _savedConfig = MutableStateFlow<TrackerConfig?>(null)
    /** Config as last loaded from / saved to flash, so the editor can mark what changed. */
    val savedConfig: StateFlow<TrackerConfig?> = _savedConfig.asStateFlow()

    private val _busy = MutableStateFlow<BusyAction?>(null)
    /** A Save or Discard in progress; the editor shows it and ignores repeat taps. */
    val busy: StateFlow<BusyAction?> = _busy.asStateFlow()
    /** Link carrying the current session: [serialManager] or a [BleTransport]. */
    private var transport: DeviceTransport? = null

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

    private val _statusLoading = MutableStateFlow<Set<StatusGroup>>(emptySet())
    /** Status cards with a read in progress. */
    val statusLoading: StateFlow<Set<StatusGroup>> = _statusLoading.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

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
    private val _currentLogLevel = MutableStateFlow("info")
    val currentLogLevel: StateFlow<String> = _currentLogLevel.asStateFlow()

    // Backing store for _logLines — evicts from the front instead of rebuilding
    // a full `it + newLines` list (up to 1000+ elements) on every chunk.
    private val logBuffer = ArrayDeque<String>(1000)
    private val logTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private var connectionEventJob: Job? = null
    /** Setup-mode handshake and first config load of the current session. */
    private var setupJob: Job? = null
    private var logCollectorJob: Job? = null
    private var locationListener: LocationListener? = null
    private var locationTimeoutJob: Job? = null

    // Pending debounced sends, keyed by field (e.g. "beacon callsign") — a fresh edit
    // to the same field cancels and restarts the timer instead of queuing another send.
    private val fieldDebounceJobs = mutableMapOf<String, Job>()
    /** The command each pending debounced send will issue, by field. */
    private val pendingFieldCmds = mutableMapOf<String, String>()

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    /** Opens a setup session over USB serial. Caller has already obtained USB permission. */
    fun connectUsb(driver: UsbSerialDriver) {
        cancelLoggerReconnect()
        rememberTarget(LinkTarget.Usb(driver))
        startSession(serialManager) { serialManager.open(driver) }
    }

    /** Asks for USB permission if needed (Android shows a dialog), then connects. */
    fun connectUsbWithPermission(driver: UsbSerialDriver) {
        rememberTarget(LinkTarget.Usb(driver))
        serialManager.requestPermission(driver.device) { granted ->
            if (granted) {
                connectUsb(driver)
            } else {
                _connectionState.value = ConnectionState.Error(
                    "USB access was denied. Tap Retry and choose Allow when Android asks.",
                )
            }
        }
    }

    /** Opens the last link again (USB or Bluetooth), e.g. after it failed to open. */
    fun retryConnection() {
        when (val t = lastTarget) {
            is LinkTarget.Usb -> connectUsbWithPermission(t.driver)
            is LinkTarget.Ble -> connectBle(t.tracker)
            null -> {}
        }
    }

    /** Stops a connection attempt in progress (pairing, setup handshake). */
    fun cancelConnecting() = disconnect()

    private fun rememberTarget(t: LinkTarget) {
        lastTarget = t
        _retryLabel.value = when (t) {
            is LinkTarget.Usb -> "Retry ${t.driver.device.productName ?: "USB device"}"
            is LinkTarget.Ble -> "Retry ${t.tracker.name}"
        }
    }

    /**
     * Opens a setup session over the tracker's BLE configuration link. Caller has
     * already obtained the Bluetooth runtime permissions. Pairing (PIN entry) happens
     * inside [BleTransport]'s open sequence.
     */
    fun connectBle(tracker: BleTrackerInfo) {
        cancelLoggerReconnect()
        rememberTarget(LinkTarget.Ble(tracker))
        openBle(tracker)
    }

    private fun openBle(tracker: BleTrackerInfo) {
        lastBleTracker = tracker
        bleScanner.stop()   // scanning slows down connection setup on many phones
        val ble = BleTransport(getApplication(), tracker.device, tracker.name)
        startSession(ble) { ble.open() }
    }

    /**
     * Common session start for every transport: wires the protocol handler, connection
     * events and live log tap to [link], then calls [open] to start it.
     */
    private fun startSession(link: DeviceTransport, open: () -> Unit) {
        _connectionState.value = ConnectionState.Connecting()
        clearDeviceReadings()
        transport = link

        val proto = ProtocolHandler(
            link, link.rxFlow,
            timeoutScale = if (link.kind == TransportKind.BLE) BLE_TIMEOUT_SCALE else 1L,
        )
        protocol = proto

        // Start collecting events BEFORE open() so the Opened event isn't dropped.
        // viewModelScope uses Dispatchers.Main.immediate: this launch runs immediately
        // on the main thread and suspends at collect, returning control here before
        // open() is called below — so the subscriber is active when Opened fires.
        // Track as a Job so disconnect() can cancel it before the port close triggers
        // a Lost event that would overwrite the Disconnected state we already set.
        connectionEventJob?.cancel()
        setupJob?.cancel()
        setupJob = null
        connectionEventJob = viewModelScope.launch {
            link.connectionEvents.collect { event ->
                when (event) {
                    is ConnectionEvent.Progress -> {
                        _connectionState.value = ConnectionState.Connecting(event.message)
                    }
                    is ConnectionEvent.Opened -> {
                        _connectionState.value = ConnectionState.Connected(event.label, link.kind)
                        if (link is BleTransport) coverageLogger.attach(link)
                        // Own job: the handshake can take 45 s+ and a Lost/Error arriving
                        // meanwhile must be handled right away, not after it.
                        setupJob?.cancel()
                        setupJob = viewModelScope.launch { enterSetupAndLoad(proto, link.kind) }
                    }
                    is ConnectionEvent.Error -> {
                        _connectionState.value = ConnectionState.Error(event.message)
                        setupJob?.cancel()
                        proto.close()
                        protocol = null
                        if (link is BleTransport) coverageLogger.detach(reconnecting = reconnectJob?.isActive == true)
                        releaseBleTransport(link)
                    }
                    is ConnectionEvent.Lost -> {
                        _connectionState.value = ConnectionState.Error("Disconnected: ${event.reason}")
                        setupJob?.cancel()
                        proto.close()
                        protocol = null
                        val autoReconnect = link is BleTransport && coverageLogger.isEnabled
                        if (link is BleTransport) coverageLogger.detach(reconnecting = autoReconnect)
                        releaseBleTransport(link)
                        if (autoReconnect) {
                            snack("Tracker disconnected — reconnecting for the coverage logger")
                            scheduleLoggerReconnect()
                        } else {
                            snack("Device disconnected — reconnect to continue")
                        }
                    }
                }
            }
        }

        // Tap rxFlow for the live log stream. Both this collector and ProtocolHandler's
        // rxReader receive every byte since SharedFlow supports multiple subscribers.
        logCollectorJob?.cancel()
        logCollectorJob = viewModelScope.launch {
            val lines = LineAssembler()
            link.rxFlow.collect { bytes ->
                val newLines = lines.feed(bytes)
                if (newLines.isNotEmpty()) {
                    newLines.forEach { line ->
                        if (logBuffer.size >= 1000) logBuffer.removeFirst()
                        // Device log output carries no timestamp of its own, so stamp
                        // each line with local receipt time. This is a live tail with
                        // no backlog replay, so receipt time is accurate to within
                        // serial transmission latency.
                        logBuffer.addLast("[${logTimeFormatter.format(LocalTime.now())}] $line")
                    }
                    _logLines.value = logBuffer.toList()
                }
            }
        }

        open()
    }

    /**
     * A BLE transport is single-use: close it once its link has failed so its GATT
     * client and coroutines are released. (The USB port keeps its existing behavior.)
     */
    private fun releaseBleTransport(link: DeviceTransport) {
        if (link.kind != TransportKind.BLE) return
        link.close()
        if (transport === link) transport = null
    }

    /**
     * Coverage logger only: when a logging BLE link drops (out of range on a drive), keep
     * trying to reconnect to the same tracker so logging resumes by itself. Stops as soon
     * as the user connects or disconnects, or switches the logger off. The tracker is
     * already paired, so no PIN prompt appears.
     */
    private fun scheduleLoggerReconnect() {
        val tracker = lastBleTracker ?: return
        reconnectJob?.cancel()
        coverageLogger.setReconnecting(true)
        reconnectJob = viewModelScope.launch {
            var wait = LOGGER_RECONNECT_FIRST_MS
            try {
                while (true) {
                    delay(wait)
                    if (!coverageLogger.isEnabled || _connectionState.value !is ConnectionState.Error) break
                    openBle(tracker)
                    val outcome = _connectionState.first { it !is ConnectionState.Connecting }
                    if (outcome !is ConnectionState.Error) break
                    wait = (wait * 2).coerceAtMost(LOGGER_RECONNECT_MAX_MS)
                }
            } finally {
                if (_connectionState.value !is ConnectionState.Connected) coverageLogger.setReconnecting(false)
            }
        }
    }

    private fun cancelLoggerReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        coverageLogger.setReconnecting(false)
    }

    fun disconnect() {
        cancelLoggerReconnect()
        coverageLogger.detach()
        // Cancel the event collector FIRST so the Lost event emitted when the port
        // closes cannot overwrite the Disconnected state we're about to set.
        connectionEventJob?.cancel()
        connectionEventJob = null
        setupJob?.cancel()
        setupJob = null
        logCollectorJob?.cancel()
        logCollectorJob = null
        fieldDebounceJobs.values.forEach { it.cancel() }
        fieldDebounceJobs.clear()
        pendingFieldCmds.clear()
        protocol?.close()
        protocol = null
        transport?.close()
        transport = null
        serialManager.close()   // no-op unless it was the active transport
        _connectionState.value = ConnectionState.Disconnected
        _config.value = null
        _dirty.value = false
        clearDeviceReadings()
    }

    /**
     * Forgets everything read from the previous tracker (status values, firmware
     * version, WiFi scan results) so nothing from one device is shown while talking
     * to another. Called on disconnect and again at session start, which also covers
     * a link that dropped rather than being disconnected.
     */
    private fun clearDeviceReadings() {
        _firmwareVersion.value = null
        _statusLines.value = emptyMap()
        _wifiScanResults.value = emptyList()
        _wifiScanning.value = false
    }

    fun clearLog() { logBuffer.clear(); _logLines.value = emptyList() }

    override fun onCleared() {
        reconnectJob?.cancel()
        coverageLogger.detach()
        stopLocationFetch()
        bleScanner.stop()
        transport?.close()
        super.onCleared()
    }

    private suspend fun enterSetupAndLoad(proto: ProtocolHandler, kind: TransportKind) {
        when (val r = proto.enterSetupMode()) {
            is CommandResult.Timeout -> snack(
                if (kind == TransportKind.BLE)
                    "Tracker didn't answer over Bluetooth. If its PIN changed or it was reflashed, " +
                    "forget it in Android Bluetooth settings and reconnect."
                else
                    "Setup mode entry timed out — check baud rate"
            )
            is CommandResult.Err     -> snack("Setup entry failed: ${r.message}")
            is CommandResult.Ok      -> {
                loadConfig(proto)
                // A session that dropped mid-edit (e.g. Bluetooth out of range) leaves its
                // edits applied in the tracker's RAM but unsaved. The editor shows the saved
                // config, so surface the Save/Discard bar rather than let a later Save
                // silently persist edits the user can't see.
                if (r.text.contains(INTERRUPTED_SESSION_NOTE)) {
                    _dirty.value = true
                    snack("The tracker has unsaved edits from an interrupted session — Save or Discard them")
                }
                readFirmwareVersion()
            }
        }
    }

    private suspend fun loadConfig(proto: ProtocolHandler) {
        when (val r = proto.exportConfig()) {
            is CommandResult.Ok -> {
                try {
                    val cfg = json.decodeFromString(TrackerConfig.serializer(), r.text)
                    _config.value = cfg
                    _savedConfig.value = cfg
                    _dirty.value = false
                    coverageLogger.setTracker(cfg.beacons.firstOrNull())
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

    fun save() = runBusy(BusyAction.SAVING) { proto ->
        // Edits still waiting out their typing debounce go first, so Save includes them.
        flushPendingFields()
        if (report(proto.sendCommand("save"), "Save", ok = "Config saved")) {
            _dirty.value = false
            // Before the notes, so a callsign or object change is noted under the new device.
            _config.value?.let { coverageLogger.setTracker(it.beacons.firstOrNull()) }
            recordSettingNotes()
        }
    }

    fun discard() = runBusy(BusyAction.DISCARDING) { proto ->
        fieldDebounceJobs.values.forEach { it.cancel() }
        fieldDebounceJobs.clear()
        pendingFieldCmds.clear()
        // With unsaved edits the tracker reboots instead of printing a prompt.
        val r = proto.sendCommand("discard", completeOn = "rebooting to reload config")
        if (report(r, "Discard", ok = "Discarded — device rebooting")) _dirty.value = false
    }

    /** Runs [block] as the one Save/Discard in progress; a second tap meanwhile is ignored. */
    private fun runBusy(action: BusyAction, block: suspend (ProtocolHandler) -> Unit) {
        val proto = protocol ?: return
        if (!_busy.compareAndSet(null, action)) return
        viewModelScope.launch {
            try { block(proto) } finally { _busy.value = null }
        }
    }

    fun reboot() {
        viewModelScope.launch {
            // The tracker resets right after this line, so there is never a prompt to wait for.
            protocol?.sendCommand("reboot", completeOn = "Rebooting...")
            snack("Rebooting…")
            disconnect()
        }
    }

    /**
     * Coverage logger "Record setting changes as device notes": one event per setting
     * that differs from the last saved config. [settingChangeNotes] leaves secrets out.
     */
    private fun recordSettingNotes() {
        val after = _config.value ?: return
        val before = _savedConfig.value
        _savedConfig.value = after
        if (before == null) return
        val callsign = after.beacons.firstOrNull()?.callsign ?: return
        settingChangeNotes(before, after).forEach { coverageLogger.recordEvent(callsign, it) }
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

    /** Resets the import sheet's state; call before opening the sheet. */
    fun clearImportState() {
        if (_importState.value !is ImportState.Sending) _importState.value = ImportState.Idle
    }

    fun importJson(jsonText: String) {
        if (_importState.value is ImportState.Sending) return
        // Fail fast on malformed JSON instead of waiting up to 30s for the device
        // to reject it — importConfig() only completes on the import-success marker.
        try {
            json.decodeFromString(TrackerConfig.serializer(), jsonText)
        } catch (e: Exception) {
            _importState.value = ImportState.Failed("Invalid config JSON: ${e.message}")
            return
        }
        val proto = protocol ?: run { _importState.value = ImportState.Failed("Not connected"); return }
        _importState.value = ImportState.Sending
        viewModelScope.launch {
            _importState.value = when (val r = proto.importConfig(jsonText)) {
                is CommandResult.Ok -> {
                    // The sheet closes on success; the snackbar carries the message from there.
                    snack("Import successful — device rebooting")
                    ImportState.Done
                }
                is CommandResult.Err -> ImportState.Failed("Import failed: ${r.message}")
                CommandResult.Timeout -> ImportState.Failed("Import timed out")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Live status reads
    // -------------------------------------------------------------------------

    /** Reads every status card (the Status screen does this on entry). */
    fun refreshAllStatus() {
        readBattery(); readGps(); readAprsIsStatus(); readWifiStaStatus(); readFirmwareVersion()
    }

    /**
     * Runs one status read, marking [group] as loading meanwhile so its card can show
     * a spinner. A read of a group already in progress is skipped.
     */
    private fun readStatus(group: StatusGroup, block: suspend (ProtocolHandler) -> Unit) {
        val proto = protocol ?: return
        while (true) {
            val current = _statusLoading.value
            if (group in current) return
            if (_statusLoading.compareAndSet(current, current + group)) break
        }
        viewModelScope.launch {
            try { block(proto) } finally { _statusLoading.update { it - group } }
        }
    }

    /** Firmware: `bat.voltage=X.XX bat.percent=YY`. */
    fun readBattery() = readStatus(StatusGroup.BATTERY) { proto ->
        val r = proto.sendCommand("bat read")
        if (r is CommandResult.Ok) replaceStatusGroup("bat.", parseStatusTokens(r.text))
    }

    /** Firmware: `gps.lat=… gps.lon=… gps.alt=… gps.sats=… gps.valid=0|1`. */
    fun readGps() = readStatus(StatusGroup.GPS) { proto ->
        val r = proto.sendCommand("gps read")
        if (r is CommandResult.Ok) replaceStatusGroup("gps.", parseStatusTokens(r.text))
    }

    /**
     * Firmware: `aprsIS.connected=true host=… port=… [server=…]` or `aprsIS.connected=false`.
     * Older firmware sends only the connected flag; the extra keys are then simply absent.
     */
    fun readAprsIsStatus() = readStatus(StatusGroup.APRS_IS) { proto ->
        val r = proto.sendCommand("aprsiss status")
        if (r is CommandResult.Ok) {
            replaceStatusGroup("aprsIS.", prefixKeys("aprsIS.", parseStatusTokens(r.text)))
        }
    }

    /** Firmware: `wifiSTA.connected=true ip=… ssid=<rest of line>` or `wifiSTA.connected=false`. */
    fun readWifiStaStatus() = readStatus(StatusGroup.WIFI) { proto ->
        val r = proto.sendCommand("wifista status")
        if (r is CommandResult.Ok) {
            replaceStatusGroup("wifiSTA.", prefixKeys("wifiSTA.", parseStatusTokens(r.text, restOfLineKey = "ssid")))
        }
    }

    /**
     * Replaces every status entry whose key starts with [prefix] with [fresh], so a
     * value from an earlier reading (e.g. the SSID of a network since dropped) can't
     * linger next to a newer "connected=false".
     */
    private fun replaceStatusGroup(prefix: String, fresh: Map<String, String>) {
        _statusLines.update { lines -> lines.filterKeys { !it.startsWith(prefix) } + fresh }
    }

    /**
     * Runs the firmware's blocking 2-4s WiFi scan and parses the
     * `wifiSTA.scan: rssi=X secure=0|1 ssid=Y` lines it prints.
     */
    fun scanWifiNetworks() {
        viewModelScope.launch {
            _wifiScanning.value = true
            try {
                val r = protocol?.sendCommand("wifista scan", timeoutMs = WIFI_SCAN_TIMEOUT_MS)
                if (report(r, "WiFi scan") && r is CommandResult.Ok) _wifiScanResults.value = parseWifiScan(r.text)
            } finally {
                _wifiScanning.value = false
            }
        }
    }

    fun readFirmwareVersion() = readStatus(StatusGroup.VERSION) { proto ->
        val r = proto.sendCommand("version")
        if (r is CommandResult.Ok) {
            val versionText = r.text.removePrefix("version.date=").trim()
            _statusLines.update { it + ("version" to versionText) }
            _firmwareVersion.value = FirmwareVersion.parse(versionText)
        }
    }

    fun setLogLevel(level: String) {
        viewModelScope.launch {
            protocol?.sendCommand("log $level")
            _currentLogLevel.value = level
        }
    }

    /** Fires an out-of-cycle beacon; does not affect the normal beacon timer. */
    fun txNow(beacon: TxBeacon) {
        viewModelScope.launch {
            report(protocol?.sendCommand("tx ${beacon.cli}"), "TX", ok = "${beacon.label} beacon sent")
        }
    }

    // -------------------------------------------------------------------------
    // Per-field command senders
    // Each mutates the local config state immediately for snappy UI, then sends
    // the CLI command. An ERR: response sets lastError for inline display.
    // -------------------------------------------------------------------------

    // -- Beacon --

    fun setCallsign(v: String) = sendRequiredField("beacon callsign", v, debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(callsign = v.uppercase().trim()) }
    }
    fun setSymbol(v: String) = sendRequiredField("beacon symbol", v, debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(symbol = v) }
    }
    fun setOverlay(v: String) = sendRequiredField("beacon overlay", v, debounceMs = FIELD_DEBOUNCE_MS) {
        updateBeacon { copy(overlay = v) }
    }
    fun setMicE(v: String) = sendField("beacon mice $v") {
        updateBeacon { copy(micE = v) }
    }
    fun setComment(v: String) = sendField("beacon comment $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "beacon comment") {
        updateBeacon { copy(comment = v) }
    }
    fun setStatus(v: String) = sendField("beacon status $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "beacon status") {
        updateBeacon { copy(status = v) }
    }
    fun setTacticalCallsign(v: String) = sendField("beacon tactical $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "beacon tactical") {
        updateBeacon { copy(tacticalCallsign = v.take(9)) }
    }
    fun setProfileLabel(v: String) = sendField("beacon label $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "beacon label") {
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
    // `cad` is the v1.4.1-compatible alias of `lora cad`, so older firmware accepts it too.
    fun setCadActive(v: Boolean) = sendField("cad ${v.onOff}") {
        updateOther { copy(cadActive = v) }
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
    fun setSmartTurnMinTime(v: Int) = sendField("smartcustom turnmintime $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateCustomSB { copy(turnMinTime = v) }
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
    fun setBtName(v: String) = sendField("bt name $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "bt name") {
        updateBt { copy(deviceName = v) }
    }
    fun setBtPinEnabled(v: Boolean) = sendField("bt pin ${v.onOff}") {
        updateBt { copy(pinEnabled = v) }
    }
    /** Caller must pass a valid 4-6 digit PIN; the firmware rejects anything else. */
    fun setBtPin(v: String) = sendField("bt pin $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateBt { copy(pin = v) }
    }

    // -- KISS output --

    fun setKissRssi(v: Boolean) = sendField("kiss rssi ${v.onOff}") {
        update { copy(kiss = (kiss ?: KissConfig()).copy(rssi = v)) }
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

    // -- Remote config (CourseSentry) --

    fun setRemoteCfgEnabled(v: Boolean) = sendField("remotecfg ${v.onOff}") {
        updateRemoteCfg { copy(enabled = v) }
    }
    fun setRemoteCfgToken(v: String) = sendField("remotecfg token $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "remotecfg token") {
        updateRemoteCfg { copy(token = v) }
    }
    fun setRemoteCfgWindow(v: Int) = sendField("remotecfg window $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateRemoteCfg { copy(unlockWindowSec = v) }
    }

    // -- WiFi AP --

    fun setWifiApPassword(v: String) = sendField("wifi password $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "wifi password") {
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
    fun setWifiStaSsid(i: Int, v: String) = sendRequiredField("wifista ssid $i", v, debounceMs = FIELD_DEBOUNCE_MS) {
        updateWifiSta { copy(networks = networks.mapIndexed { idx, n -> if (idx == i) n.copy(ssid = v) else n }) }
    }
    fun setWifiStaPassword(i: Int, v: String) = sendField("wifista password $i $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "wifista password $i") {
        updateWifiSta { copy(networks = networks.mapIndexed { idx, n -> if (idx == i) n.copy(password = v) else n }) }
    }

    // -- APRS-IS --

    fun setAprsIsServer(v: String) = sendRequiredField("aprsiss server", v, debounceMs = FIELD_DEBOUNCE_MS) {
        updateAprsIs { copy(server = v) }
    }
    fun setAprsIsPort(v: Int) = sendField("aprsiss port $v", debounceMs = FIELD_DEBOUNCE_MS) {
        updateAprsIs { copy(port = v) }
    }
    // Blank is meaningful for both (passcode: auto-compute from callsign; filter: none),
    // so unlike the required fields these send an empty value to clear the setting.
    fun setAprsIsPasscode(v: String) = sendField("aprsiss passcode $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "aprsiss passcode") {
        updateAprsIs { copy(passcode = v) }
    }
    fun setAprsIsFilter(v: String) = sendField("aprsiss filter $v", debounceMs = FIELD_DEBOUNCE_MS, fieldKey = "aprsiss filter") {
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

    fun setDeviceRole(v: DeviceRole) = sendField("role set ${v.cli}") {
        update { copy(deviceRole = v.code) }
    }
    fun setGpsSource(v: GpsSource) = sendField("role gps ${v.cli}") {
        update { copy(gpsSource = v.code) }
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
        updateOther { copy(nonSmartBeaconRateSec = v) }
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
    fun setDigiMode(v: DigiMode) = sendField("digi ${v.cli}") {
        updateOther { copy(digiMode = v.code) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun clearSnack() { _snackMessage.value = null }
    fun clearFieldError() { _lastError.value = null }

    private fun snack(msg: String) { _snackMessage.value = msg }

    /**
     * Shows the outcome of a one-shot command and returns true if it succeeded. A
     * firmware "ERR" reply arrives as [CommandResult.Ok] and counts as a failure.
     * [ok] is shown on success when given.
     */
    private fun report(r: CommandResult?, what: String, ok: String? = null): Boolean = when (r) {
        null -> { snack("Not connected"); false }
        is CommandResult.Err -> { snack("$what failed: ${r.message}"); false }
        CommandResult.Timeout -> { snack("$what timed out"); false }
        is CommandResult.Ok ->
            if (r.text.startsWith("ERR")) { snack("$what failed: ${r.text}"); false }
            else { ok?.let(::snack); true }
    }

    /**
     * For fields the firmware insists on a value for: it answers an empty one with
     * "ERR" (e.g. "symbol must be 1 char"). An empty box is just the moment between
     * deleting the old value and typing the new one, so it stays a local edit: nothing
     * is sent, and any still-pending send of the previous value is cancelled so the
     * tracker doesn't receive a value the user has already deleted. [cmdPrefix] is the
     * command without its value, e.g. "beacon symbol".
     */
    private fun sendRequiredField(
        cmdPrefix: String,
        value: String,
        debounceMs: Long = 0L,
        localUpdate: TrackerConfig.() -> Unit,
    ) {
        if (value.isNotBlank()) {
            sendField("$cmdPrefix $value", debounceMs, fieldKey = cmdPrefix, localUpdate = localUpdate)
            return
        }
        _config.value?.localUpdate() ?: return
        fieldDebounceJobs.remove(cmdPrefix)?.cancel()
        pendingFieldCmds.remove(cmdPrefix)
    }

    /**
     * Applies [localUpdate] immediately to _config for a responsive UI, then sends [rawCmd].
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
     *
     * [fieldKey] identifies the field for debouncing and error display. The default
     * (command minus its last word) is only right for single-word values; callers whose
     * value can contain spaces (SSIDs, filters) pass the command prefix explicitly, or
     * every keystroke would get its own key and its own send.
     */
    private fun sendField(
        rawCmd: String,
        debounceMs: Long = 0L,
        fieldKey: String = rawCmd.substringBeforeLast(" "),
        localUpdate: TrackerConfig.() -> Unit,
    ) {
        // A pasted line break would end the command early and run the rest as a second
        // command on the tracker; no setting value needs control characters.
        val cmd = rawCmd.filterNot { it.isISOControl() }
        _config.value?.localUpdate() ?: return
        _dirty.value = true

        fieldDebounceJobs.remove(fieldKey)?.cancel()
        pendingFieldCmds.remove(fieldKey)

        if (debounceMs <= 0L) {
            viewModelScope.launch { dispatchField(cmd, fieldKey) }
        } else {
            pendingFieldCmds[fieldKey] = cmd
            fieldDebounceJobs[fieldKey] = viewModelScope.launch {
                delay(debounceMs)
                fieldDebounceJobs.remove(fieldKey)
                pendingFieldCmds.remove(fieldKey)
                dispatchField(cmd, fieldKey)
            }
        }
    }

    /** Sends every debounced edit now instead of after its delay. */
    private suspend fun flushPendingFields() {
        val pending = pendingFieldCmds.toMap()
        pending.keys.forEach { fieldDebounceJobs.remove(it)?.cancel() }
        pendingFieldCmds.clear()
        pending.forEach { (key, cmd) -> dispatchField(cmd, key) }
    }

    /**
     * A firmware-side rejection comes back as CommandResult.Ok (the prompt still
     * returns normally) with an "ERR" response line, NOT as CommandResult.Err —
     * that variant is reserved for local I/O failures. Older/mismatched firmware
     * is exactly where this rejection path matters most, so it has to be treated
     * the same as Err/Timeout below rather than read as a silent success.
     */
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
            is CommandResult.Ok -> if (result.text.startsWith("ERR")) {
                _lastError.value = FieldError(fieldKey, result.text)
                resyncFromDevice()
            } else if (_lastError.value?.field == fieldKey) {
                _lastError.value = null
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
    private fun updateRemoteCfg(fn: RemoteCfgConfig.() -> RemoteCfgConfig) =
        update { copy(remoteCfg = remoteCfg.fn()) }
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

/**
 * Parses the firmware's `key=value key=value` status lines. A value runs to the next
 * space, except for [restOfLineKey], which the firmware prints last precisely so it
 * can contain spaces (SSIDs): its value is everything after the `=` to end of line.
 */
internal fun parseStatusTokens(text: String, restOfLineKey: String? = null): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (line in text.lines()) {
        var rest = line.trim()
        if (restOfLineKey != null) {
            val marker = Regex("""(?:^|\s)${Regex.escape(restOfLineKey)}=""").find(rest)
            if (marker != null) {
                result[restOfLineKey] = rest.substring(marker.range.last + 1).trim()
                rest = rest.substring(0, marker.range.first)
            }
        }
        rest.split(' ').forEach { token ->
            val eq = token.indexOf('=')
            if (eq > 0) result[token.substring(0, eq)] = token.substring(eq + 1)
        }
    }
    return result
}

/**
 * Parses the `wifiSTA.scan: rssi=X secure=0|1 ssid=Y` lines of `wifista scan`. The
 * SSID is last on the line and runs to its end, so SSIDs with spaces stay whole.
 */
internal fun parseWifiScan(text: String): List<WifiScanResult> = text.lines()
    .filter { it.startsWith("wifiSTA.scan:") }
    .mapNotNull { line ->
        val rssi = Regex("rssi=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        val secure = Regex("secure=(\\d)").find(line)?.groupValues?.get(1) == "1"
        val ssid = Regex("ssid=(.*)$").find(line)?.groupValues?.get(1)
        if (rssi != null && ssid != null) WifiScanResult(ssid, rssi, secure) else null
    }

/** Puts [prefix] on keys that don't already carry it (`ip` → `wifiSTA.ip`). */
private fun prefixKeys(prefix: String, map: Map<String, String>): Map<String, String> =
    map.mapKeys { (k, _) -> if (k.startsWith(prefix)) k else prefix + k }
