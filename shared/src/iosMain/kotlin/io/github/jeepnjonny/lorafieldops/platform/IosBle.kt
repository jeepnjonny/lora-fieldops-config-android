@file:OptIn(ExperimentalForeignApi::class)

package io.github.jeepnjonny.lorafieldops.platform

import io.github.jeepnjonny.lorafieldops.serial.CONFIG_RX_CHAR_UUID
import io.github.jeepnjonny.lorafieldops.serial.CONFIG_SERVICE_UUID
import io.github.jeepnjonny.lorafieldops.serial.CONFIG_TX_CHAR_UUID
import io.github.jeepnjonny.lorafieldops.serial.ConnectionEvent
import io.github.jeepnjonny.lorafieldops.serial.KISS_SERVICE_UUID
import io.github.jeepnjonny.lorafieldops.serial.KISS_TX_CHAR_UUID
import io.github.jeepnjonny.lorafieldops.serial.TransportKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBATTErrorDomain
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBErrorDomain
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

private const val SCAN_DURATION_MS       = 10_000L
private const val READY_TIMEOUT_MS       = 5_000L
private const val CONNECT_TIMEOUT_MS     = 15_000L
/** Covers the user reading the PIN off the tracker and typing it into the iOS prompt. */
private const val PAIRING_TIMEOUT_MS     = 90_000L
private const val GATT_OP_TIMEOUT_MS     = 10_000L
/** Lets the tracker's main loop register the subscription before the first CLI bytes arrive. */
private const val LINK_SETTLE_MS         = 300L

// CoreBluetooth error codes (CBError.h / CBATTError).
private const val ATT_INSUFFICIENT_AUTHENTICATION = 5L
private const val ATT_INSUFFICIENT_ENCRYPTION     = 15L
private const val CB_PEER_REMOVED_PAIRING_INFO    = 14L
private const val CB_ENCRYPTION_TIMED_OUT         = 15L

private const val MSG_NO_CONFIG_SERVICE =
    "This tracker doesn't offer setup over Bluetooth. Over USB, turn on " +
    "\"Require PIN\" in the Bluetooth section, save, reboot, and make sure the firmware is up to date."
private const val MSG_PAIRING_FAILED =
    "The tracker and this iPhone couldn't pair. If you entered the PIN, open Settings → Bluetooth, " +
    "tap ⓘ next to the tracker, choose Forget This Device, then connect again and enter its PIN."

/** A Bluetooth failure with a message meant for the user. */
class BleException(message: String) : Exception(message)

// -----------------------------------------------------------------------------
// Central manager
// -----------------------------------------------------------------------------

/**
 * The app's one CBCentralManager. Its callbacks arrive on the main queue; scan results go
 * to [onDiscover], connection events to the [IosBleLink] that owns the peripheral.
 */
internal class BleCentral : NSObject(), CBCentralManagerDelegateProtocol {
    /** Created on first use, which is when iOS asks for Bluetooth permission. */
    val manager: CBCentralManager by lazy { CBCentralManager(delegate = this, queue = null) }

    private val _state = MutableStateFlow(CBManagerStateUnknown)

    var onDiscover: ((CBPeripheral, Map<Any?, *>, NSNumber) -> Unit)? = null
    private val links = mutableMapOf<String, IosBleLink>()

    fun register(link: IosBleLink) { links[link.peripheralId] = link }
    fun unregister(link: IosBleLink) { if (links[link.peripheralId] === link) links.remove(link.peripheralId) }

    /** Null when Bluetooth is ready, else why not. Waits briefly while iOS works out the state. */
    suspend fun readyError(): String? {
        _state.value = manager.state
        val state = withTimeoutOrNull(READY_TIMEOUT_MS) {
            _state.first { it != CBManagerStateUnknown && it != CBManagerStateResetting }
        } ?: manager.state
        return when (state) {
            CBManagerStatePoweredOn -> null
            CBManagerStatePoweredOff -> "Turn on Bluetooth, then try again"
            CBManagerStateUnauthorized -> "Bluetooth access is off for this app. Allow it in Settings → Privacy & Security → Bluetooth."
            CBManagerStateUnsupported -> "This device has no Bluetooth LE"
            else -> "Bluetooth isn't ready yet. Try again in a moment."
        }
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        _state.value = central.state
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        onDiscover?.invoke(didDiscoverPeripheral, advertisementData, RSSI)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        links[didConnectPeripheral.identifier.UUIDString]?.onConnected()
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
        links[didFailToConnectPeripheral.identifier.UUIDString]?.onConnectFailed(error)
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
        links[didDisconnectPeripheral.identifier.UUIDString]?.onDisconnected(error)
    }
}

// -----------------------------------------------------------------------------
// Scanner
// -----------------------------------------------------------------------------

/** Finds trackers by the KISS service UUID they advertise. Scans stop on their own. */
internal class IosBleScanner(private val central: BleCentral) : BleScanner {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _results = MutableStateFlow<List<BleTrackerInfo>>(emptyList())
    override val results: StateFlow<List<BleTrackerInfo>> = _results.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    override val isSupported: Boolean = true

    private var scanJob: Job? = null

    override fun start() {
        scanJob?.cancel()
        scanJob = scope.launch {
            _error.value = null
            central.readyError()?.let { _error.value = it; return@launch }
            if (_scanning.value) central.manager.stopScan()
            _results.value = emptyList()
            central.onDiscover = { peripheral, adv, rssi ->
                val id = peripheral.identifier.UUIDString
                val name = (adv[CBAdvertisementDataLocalNameKey] as? String) ?: peripheral.name ?: id
                val entry = BleTrackerInfo(id = id, name = name, rssi = rssi.intValue, handle = peripheral)
                // Replace any earlier sighting of the same tracker (fresh RSSI), strongest first.
                _results.value = (_results.value.filterNot { it.id == id } + entry).sortedByDescending { it.rssi }
            }
            central.manager.scanForPeripheralsWithServices(listOf(CBUUID.UUIDWithString(KISS_SERVICE_UUID)), null)
            _scanning.value = true
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
        stopScan()
    }

    private fun stopScan() {
        if (!_scanning.value) return
        central.manager.stopScan()
        central.onDiscover = null
        _scanning.value = false
    }
}

// -----------------------------------------------------------------------------
// Link
// -----------------------------------------------------------------------------

/**
 * BLE client for the tracker's configuration link, on CoreBluetooth.
 *
 * Open sequence: connect → discover services (config service present?) → subscribe to
 * config TX → [ConnectionEvent.Opened]. iOS has no API to pair on request: it pairs by
 * itself when the subscription hits the tracker's encryption requirement, showing its
 * PIN prompt then. After that the link is a byte pipe: [write] sends write-with-response
 * chunks (the tracker's acknowledgements are the flow control), and notifications are
 * re-emitted on [rxFlow].
 *
 * Everything runs on the main thread, where CoreBluetooth delivers its callbacks, so the
 * state below needs no locking. One GATT operation is outstanding at a time ([gattOp]).
 */
internal class IosBleLink(
    private val central: BleCentral,
    private val peripheral: CBPeripheral,
    private val displayName: String,
) : BleLink {

    val peripheralId: String = peripheral.identifier.UUIDString

    override val kind = TransportKind.BLE

    private val _rxFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val rxFlow: SharedFlow<ByteArray> = _rxFlow

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    // Raw bytes from the KISS service, only while [setKissNotifications] is on (coverage
    // logger). Kept apart from [rxFlow] so the setup CLI never sees KISS traffic.
    private val _kissFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val kissFlow: SharedFlow<ByteArray> = _kissFlow

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val opMutex = Mutex()

    private var configRx: CBCharacteristic? = null
    private var configTx: CBCharacteristic? = null
    private var kissTx: CBCharacteristic? = null
    private var opened = false
    private var closed = false
    private var connectSignal: CompletableDeferred<NSError?>? = null
    private var pendingOp: CompletableDeferred<NSError?>? = null

    override val isOpen: Boolean get() = opened

    /** CBPeripheral holds its delegate weakly, so the link keeps it alive. */
    private val delegate = PeripheralDelegate(this)

    override fun open() {
        scope.launch {
            try {
                runOpenSequence()
                if (peripheral.state != platform.CoreBluetooth.CBPeripheralStateConnected) {
                    throw BleException("Bluetooth link dropped while connecting")
                }
                opened = true
                _connectionEvents.tryEmit(ConnectionEvent.Opened(displayName))
            } catch (e: Exception) {
                if (!closed) {
                    _connectionEvents.tryEmit(ConnectionEvent.Error(e.message ?: "Bluetooth connection failed"))
                    release()
                }
            }
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.Main) {
        val characteristic = configRx ?: throw BleException("Bluetooth link not open")
        val chunkSize = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)
            .toInt().coerceAtLeast(20)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end).toNSData()
            gattOp("write") { peripheral.writeValue(chunk, characteristic, CBCharacteristicWriteWithResponse) }
                ?.let { throw BleException("Bluetooth write failed: ${it.localizedDescription}") }
            offset = end
        }
    }

    override fun close() {
        closed = true
        opened = false
        release()
        scope.cancel()
    }

    /**
     * Turns notifications from the KISS service on or off on this (already open) link.
     * Throws if the tracker has no KISS characteristic or the subscription fails; the
     * setup link is unaffected either way.
     */
    override suspend fun setKissNotifications(enabled: Boolean) = withContext(Dispatchers.Main) {
        val c = kissTx ?: throw BleException("This tracker has no Bluetooth KISS service")
        if (!opened) throw BleException("Bluetooth link not open")
        gattOp("KISS subscribe") { peripheral.setNotifyValue(enabled, c) }
            ?.let { throw BleException("KISS subscribe failed: ${it.localizedDescription}") }
        Unit
    }

    // -------------------------------------------------------------------------
    // Open sequence
    // -------------------------------------------------------------------------

    private suspend fun runOpenSequence() {
        progress("Connecting to $displayName…")
        central.readyError()?.let { throw BleException(it) }
        peripheral.delegate = delegate
        central.register(this)

        val connected = CompletableDeferred<NSError?>()
        connectSignal = connected
        central.manager.connectPeripheral(peripheral, null)
        val connectError = try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException("Tracker didn't answer. Check it is on, in range, and not connected to another app.")
        }
        if (connectError != null) {
            throw BleException("Tracker refused the connection. It accepts one phone at a time; disconnect other apps.")
        }

        progress("Setting up link…")
        val configUuid = CBUUID.UUIDWithString(CONFIG_SERVICE_UUID)
        val kissUuid = CBUUID.UUIDWithString(KISS_SERVICE_UUID)
        gattOp("service discovery") { peripheral.discoverServices(listOf(configUuid, kissUuid)) }
            ?.let { throw BleException("Bluetooth service discovery failed: ${it.localizedDescription}") }
        val services = peripheral.services.orEmpty().filterIsInstance<CBService>()

        // A tracker with its PIN off has no config service at all: say so instead of
        // putting the user through a pointless pairing attempt.
        val config = services.firstOrNull { it.UUID.matches(CONFIG_SERVICE_UUID) }
            ?: throw BleException(MSG_NO_CONFIG_SERVICE)
        gattOp("characteristic discovery") {
            peripheral.discoverCharacteristics(
                listOf(CBUUID.UUIDWithString(CONFIG_RX_CHAR_UUID), CBUUID.UUIDWithString(CONFIG_TX_CHAR_UUID)),
                config,
            )
        }?.let { throw BleException("Bluetooth discovery failed: ${it.localizedDescription}") }
        val chars = config.characteristics.orEmpty().filterIsInstance<CBCharacteristic>()
        configRx = chars.firstOrNull { it.UUID.matches(CONFIG_RX_CHAR_UUID) } ?: throw BleException(MSG_NO_CONFIG_SERVICE)
        val tx = chars.firstOrNull { it.UUID.matches(CONFIG_TX_CHAR_UUID) } ?: throw BleException(MSG_NO_CONFIG_SERVICE)
        configTx = tx

        // Optional: only the coverage logger uses it, and only when switched on.
        services.firstOrNull { it.UUID.matches(KISS_SERVICE_UUID) }?.let { kiss ->
            val err = runCatching {
                gattOp("KISS discovery") {
                    peripheral.discoverCharacteristics(listOf(CBUUID.UUIDWithString(KISS_TX_CHAR_UUID)), kiss)
                }
            }.getOrNull()
            if (err == null) {
                kissTx = kiss.characteristics.orEmpty().filterIsInstance<CBCharacteristic>()
                    .firstOrNull { it.UUID.matches(KISS_TX_CHAR_UUID) }
            }
        }

        // The tracker requires an encrypted link for the setup CLI; if this iPhone isn't
        // paired yet, iOS shows its PIN prompt now and finishes this step afterwards.
        progress("Securing link — if iOS asks, enter the tracker's Bluetooth PIN")
        val subscribeError = gattOp("subscribe", PAIRING_TIMEOUT_MS) { peripheral.setNotifyValue(true, tx) }
        if (subscribeError != null) throw BleException(subscribeMessage(subscribeError))
        delay(LINK_SETTLE_MS)
    }

    private fun subscribeMessage(e: NSError): String = when {
        e.domain == CBATTErrorDomain && (e.code == ATT_INSUFFICIENT_AUTHENTICATION || e.code == ATT_INSUFFICIENT_ENCRYPTION) ->
            MSG_PAIRING_FAILED
        e.domain == CBErrorDomain && (e.code == CB_PEER_REMOVED_PAIRING_INFO || e.code == CB_ENCRYPTION_TIMED_OUT) ->
            MSG_PAIRING_FAILED
        else -> "Bluetooth subscribe failed: ${e.localizedDescription}"
    }

    // -------------------------------------------------------------------------
    // GATT plumbing
    // -------------------------------------------------------------------------

    /** Starts one GATT operation and waits for its callback; returns the error it reported, if any. */
    private suspend fun gattOp(name: String, timeoutMs: Long = GATT_OP_TIMEOUT_MS, start: () -> Unit): NSError? =
        opMutex.withLock {
            val done = CompletableDeferred<NSError?>()
            pendingOp = done
            try {
                start()
                withTimeout(timeoutMs) { done.await() }
            } catch (e: TimeoutCancellationException) {
                throw BleException(if (timeoutMs == PAIRING_TIMEOUT_MS) "Pairing timed out" else "Bluetooth $name timed out")
            } finally {
                pendingOp = null
            }
        }

    private fun progress(message: String) {
        _connectionEvents.tryEmit(ConnectionEvent.Progress(message))
    }

    private fun release() {
        configRx = null
        configTx = null
        kissTx = null
        central.unregister(this)
        runCatching { central.manager.cancelPeripheralConnection(peripheral) }
    }

    // Called by BleCentral (main queue).

    fun onConnected() { connectSignal?.complete(null) }

    fun onConnectFailed(error: NSError?) {
        connectSignal?.complete(error ?: NSError.errorWithDomain(CBErrorDomain, 0, null))
    }

    fun onDisconnected(error: NSError?) {
        val wasOpened = opened
        opened = false
        val reason = error?.localizedDescription ?: "link closed"
        connectSignal?.complete(error ?: NSError.errorWithDomain(CBErrorDomain, 0, null))
        pendingOp?.completeExceptionally(BleException("Bluetooth link dropped ($reason)"))
        if (closed) return
        if (wasOpened) {
            _connectionEvents.tryEmit(ConnectionEvent.Lost("Bluetooth link lost ($reason)"))
            release()
        }
        // While opening, the failed wait reports the error.
    }

    /** A GATT operation's callback arrived (main queue). */
    fun onOperationDone(error: NSError?) {
        pendingOp?.complete(error)
    }

    fun onValue(characteristic: CBCharacteristic, error: NSError?) {
        if (error != null) return
        val value = characteristic.value?.toByteArray() ?: return
        val uuid = characteristic.UUID
        when {
            uuid.matches(CONFIG_TX_CHAR_UUID) -> _rxFlow.tryEmit(value)
            uuid.matches(KISS_TX_CHAR_UUID) -> _kissFlow.tryEmit(value)
        }
    }
}

/** Forwards CBPeripheral callbacks (main queue) to [link]. */
private class PeripheralDelegate(private val link: IosBleLink) : NSObject(), CBPeripheralDelegateProtocol {
    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        link.onOperationDone(didDiscoverServices)
    }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
        link.onOperationDone(error)
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
        link.onOperationDone(error)
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
        link.onOperationDone(error)
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
        link.onValue(didUpdateValueForCharacteristic, error)
    }
}

private fun CBUUID.matches(uuid: String): Boolean = UUIDString.equals(uuid, ignoreCase = true)
