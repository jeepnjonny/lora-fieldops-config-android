package io.github.jeepnjonny.lorafieldops.serial

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import io.github.jeepnjonny.lorafieldops.firmware.OtaLink
import io.github.jeepnjonny.lorafieldops.platform.BleLink
import io.github.jeepnjonny.lorafieldops.platform.TrackerUpdateServices
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

// ---------------------------------------------------------------------------
// GATT layout — must match the firmware's ble_utils.cpp
// ---------------------------------------------------------------------------

/** APRS.fi KISS TNC service: advertised by the tracker (used to find it in a scan). */
val KISS_SERVICE: UUID = UUID.fromString(KISS_SERVICE_UUID)
/** KISS service: tracker -> phone notifications carrying KISS frames (heard packets and its own TX). */
private val KISS_TX_CHAR: UUID = UUID.fromString(KISS_TX_CHAR_UUID)

/** Configuration link (Nordic UART Service layout): carries the setup CLI. Not advertised. */
private val CONFIG_SERVICE: UUID = UUID.fromString(CONFIG_SERVICE_UUID)
private val CONFIG_RX_CHAR: UUID = UUID.fromString(CONFIG_RX_CHAR_UUID)  // phone writes
private val CONFIG_TX_CHAR: UUID = UUID.fromString(CONFIG_TX_CHAR_UUID)  // tracker notifies
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** ESP32 firmware update service (ble_ota.h). */
private val OTA_SERVICE: UUID = UUID.fromString(OTA_SERVICE_UUID)
private val OTA_CONTROL_CHAR: UUID = UUID.fromString(OTA_CONTROL_CHAR_UUID)
private val OTA_DATA_CHAR: UUID = UUID.fromString(OTA_DATA_CHAR_UUID)
/** Nordic legacy DFU (T114): buttonless entry into the bootloader. */
private val DFU_SERVICE: UUID = UUID.fromString(NORDIC_DFU_SERVICE_UUID)
private val DFU_CONTROL_CHAR: UUID = UUID.fromString(NORDIC_DFU_CONTROL_CHAR_UUID)
/** DFU control point: start DFU, image type "application". */
private val DFU_START_APPLICATION = byteArrayOf(0x01, 0x04)

private const val REQUESTED_MTU          = 247
private const val DEFAULT_ATT_MTU        = 23
private const val CONNECT_TIMEOUT_MS     = 15_000L
/** Covers the user reading the PIN off the tracker and typing it into the system dialog. */
private const val BOND_TIMEOUT_MS        = 90_000L
private const val BOND_POLL_INTERVAL_MS  = 500L
private const val GATT_OP_TIMEOUT_MS     = 10_000L
/** Lets the tracker's main loop register the subscription before the first CLI bytes arrive. */
private const val LINK_SETTLE_MS         = 300L
/** Write-without-response queue full: wait this long before trying the packet again. */
private const val WRITE_BUSY_RETRY_MS    = 5L
private const val WRITE_BUSY_RETRIES     = 400

// ATT errors that mean "this link isn't paired the way the tracker requires".
private const val GATT_INSUFFICIENT_AUTHENTICATION = 0x05
private const val GATT_INSUFFICIENT_ENCRYPTION     = 0x0F
// ATT error: the attribute handle doesn't exist on the tracker (stale service cache).
private const val GATT_INVALID_HANDLE              = 0x01

private const val MSG_NO_CONFIG_SERVICE =
    "This tracker doesn't offer setup over Bluetooth. Over USB, turn on " +
    "\"Require PIN\" in the Bluetooth section, save, reboot, and make sure the firmware is up to date."
private const val MSG_STALE_SERVICE_CACHE =
    "Android has an outdated copy of this tracker's Bluetooth services (its PIN setting or " +
    "firmware changed). Forget the tracker in Android Bluetooth settings, then connect again. " +
    "Bluetooth setup also needs \"Require PIN\" turned on in the tracker's Bluetooth section."
private const val MSG_STALE_PAIRING =
    "The tracker rejected this phone's pairing. In Android Bluetooth settings, " +
    "forget the tracker, then connect again and enter its PIN."

/**
 * BLE client for the tracker's configuration link.
 *
 * Open sequence: connect → MTU → discover services (config service present?) →
 * pair (PIN; the system dialog prompts the user) → subscribe to config TX →
 * [ConnectionEvent.Opened]. After that
 * the link is a plain byte pipe: [write] splits data into MTU-sized write-with-response
 * chunks (the tracker's acknowledgements are the flow control), and notifications are
 * re-emitted on [rxFlow].
 *
 * Android allows a single outstanding GATT operation per connection, so every
 * operation goes through [gattOperation], which serializes them with a mutex and
 * completes each from the matching [BluetoothGattCallback] method.
 *
 * Callers must hold BLUETOOTH_CONNECT (API 31+) before constructing this class.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val displayName: String,
) : BleLink {

    override val kind = TransportKind.BLE

    private val _rxFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val rxFlow: SharedFlow<ByteArray> = _rxFlow

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    // Raw bytes from the KISS service, only while [setKissNotifications] is on (coverage
    // logger). Kept apart from [rxFlow] so the setup CLI never sees KISS traffic.
    private val _kissFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val kissFlow: SharedFlow<ByteArray> = _kissFlow

    // Control-characteristic notifications of the ESP32 update service.
    private val _otaFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    private val scope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gattMutex = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var configRxChar: BluetoothGattCharacteristic? = null
    @Volatile private var kissTxChar: BluetoothGattCharacteristic? = null
    @Volatile private var otaControlChar: BluetoothGattCharacteristic? = null
    @Volatile private var otaDataChar: BluetoothGattCharacteristic? = null
    @Volatile private var dfuControlChar: BluetoothGattCharacteristic? = null
    @Volatile private var attMtu = DEFAULT_ATT_MTU
    @Volatile private var opened = false
    @Volatile private var closed = false

    // Completed from BluetoothGattCallback (binder thread); awaited by the open sequence / writers.
    @Volatile private var connectSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingOperation: CompletableDeferred<Int>? = null
    /**
     * Completed (with the reason) when the link drops. Lets waits that aren't GATT
     * operations (pairing, the settle delay) notice, and makes "opened" vs "dropped"
     * a single decision under [linkLock].
     */
    private val linkDead = CompletableDeferred<Unit>()
    @Volatile private var dropReason: String? = null
    private val linkLock = Any()

    override val isOpen: Boolean get() = opened

    /** Starts the open sequence; the outcome arrives on [connectionEvents]. */
    override fun open() {
        scope.launch {
            try {
                runOpenSequence()
                // Atomic with the disconnect callback: either the link is reported Opened
                // and a later drop emits Lost, or the drop already happened and this fails.
                synchronized(linkLock) {
                    if (linkDead.isCompleted || gatt == null) throw IOException(linkDropMessage())
                    opened = true
                }
                _connectionEvents.tryEmit(ConnectionEvent.Opened(displayName))
            } catch (e: Exception) {
                if (!closed) {
                    _connectionEvents.tryEmit(ConnectionEvent.Error(e.message ?: "Bluetooth connection failed"))
                    releaseGatt()
                }
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        val characteristic = configRxChar ?: throw IOException("Bluetooth link not open")
        // ATT write payload is MTU - 3; never below the BLE minimum of 20.
        val chunkSize = (attMtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            val status = gattOperation("write") { g -> writeCharacteristic(g, characteristic, chunk) }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth write failed (status $status)")
            offset = end
        }
    }

    override fun close() {
        closed = true
        opened = false
        releaseGatt()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Open sequence
    // -------------------------------------------------------------------------

    private suspend fun runOpenSequence() {
        progress("Connecting to $displayName…")
        val connected = CompletableDeferred<Boolean>()
        connectSignal = connected
        // Deprecated on the newest SDK, but its replacement isn't available back to minSdk 26.
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Bluetooth connection could not be started")
        val ok = try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Tracker didn't answer. Check it is on, in range, and not connected to another app.")
        }
        if (!ok) throw IOException("Tracker refused the connection. It accepts one phone at a time; disconnect other apps.")

        progress("Setting up link…")
        // MTU is best-effort: a tracker that keeps the 23-byte default still works, just slower.
        runCatching { gattOperation("MTU") { g -> g.requestMtu(REQUESTED_MTU) } }

        // Discover BEFORE pairing: service discovery needs no encryption, and a tracker
        // with its PIN off has no config service at all, so it must be rejected with a
        // clear message instead of first being put through a pointless pairing attempt.
        val configService = discoverConfigService()
        val txChar = configService.getCharacteristic(CONFIG_TX_CHAR)
            ?: throw IOException(MSG_NO_CONFIG_SERVICE)
        configRxChar = configService.getCharacteristic(CONFIG_RX_CHAR)
            ?: throw IOException(MSG_NO_CONFIG_SERVICE)
        // Optional: only the coverage logger uses it, and only when switched on.
        kissTxChar = gatt?.getService(KISS_SERVICE)?.getCharacteristic(KISS_TX_CHAR)
        // Firmware update support is detected by service presence (after the cache
        // refresh in discoverConfigService), never by version number.
        gatt?.getService(OTA_SERVICE)?.let { ota ->
            otaControlChar = ota.getCharacteristic(OTA_CONTROL_CHAR)
            otaDataChar = ota.getCharacteristic(OTA_DATA_CHAR)
        }
        dfuControlChar = gatt?.getService(DFU_SERVICE)?.getCharacteristic(DFU_CONTROL_CHAR)

        ensureBonded()

        enableNotifications(txChar)
        delay(LINK_SETTLE_MS)
    }

    private fun linkDropMessage(): String =
        "Bluetooth link dropped while connecting (${dropReason ?: "no link"})"

    /**
     * The tracker only exposes the config service on PIN-protected links, so pair up
     * front: the system shows its PIN dialog now, rather than in the middle of the
     * setup handshake where a timeout would be confusing.
     */
    private suspend fun ensureBonded() {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        progress("Pairing — enter the tracker's Bluetooth PIN when Android asks")
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val changed = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE   -> bonded.complete(false)
                }
            }
        }
        // Must be EXPORTED: BOND_STATE_CHANGED is sent by the Bluetooth service, which runs
        // under its own UID (not system), so a NOT_EXPORTED receiver never gets it and
        // pairing appeared to hang until the timeout. Exporting is safe because it is a
        // protected broadcast that only the platform can send.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Belt and braces: some OEM builds delay or drop the broadcast, so also watch the
        // bond state directly. Only success is detected this way; a failure still comes
        // from the broadcast (or the timeout), since BOND_NONE is also the state right
        // before pairing starts and polling can't tell those apart.
        val bondPoller = scope.launch {
            while (!bonded.isCompleted) {
                if (device.bondState == BluetoothDevice.BOND_BONDED) bonded.complete(true)
                delay(BOND_POLL_INTERVAL_MS)
            }
        }
        // A link that drops while the PIN dialog is up fails now, not after BOND_TIMEOUT_MS.
        val dropWatcher = scope.launch {
            linkDead.await()
            bonded.completeExceptionally(IOException(linkDropMessage()))
        }
        try {
            if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) {
                throw IOException("Android couldn't start pairing with the tracker")
            }
            val ok = try {
                withTimeout(BOND_TIMEOUT_MS) { bonded.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Pairing timed out. Reconnect and enter the tracker's PIN.")
            }
            if (!ok) throw IOException("Pairing failed — check the PIN (Bluetooth section of the tracker config).")
        } finally {
            bondPoller.cancel()
            dropWatcher.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Android caches a bonded device's service table and does not re-read it on
     * connect. The tracker's table legitimately changes (the config service exists only
     * while its PIN is required, and firmware updates add services), so a cached copy
     * can show a service the tracker no longer has, or hide one it gained. Drop the
     * cache before discovering so the result reflects the tracker as it is now.
     */
    private suspend fun discoverConfigService(): android.bluetooth.BluetoothGattService {
        if (gatt?.refreshServiceCache() == true) delay(GATT_REFRESH_SETTLE_MS)
        val status = gattOperation("service discovery") { g -> g.discoverServices() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth service discovery failed (status $status)")
        return gatt?.getService(CONFIG_SERVICE) ?: throw IOException(MSG_NO_CONFIG_SERVICE)
    }

    /**
     * Turns notifications from the KISS service on or off on this (already open) link.
     * The tracker sends every packet it hears and every packet it transmits there, also
     * while a setup session is running. Throws IOException if the tracker has no KISS
     * characteristic or the subscription fails; the setup link is unaffected either way.
     */
    override suspend fun setKissNotifications(enabled: Boolean) {
        val char = kissTxChar ?: throw IOException("This tracker has no Bluetooth KISS service")
        val g = gatt ?: throw IOException("Bluetooth link not open")
        if (!g.setCharacteristicNotification(char, enabled)) throw IOException("Couldn't change KISS notifications")
        val cccd = char.getDescriptor(CCCD_UUID) ?: throw IOException("KISS characteristic can't notify")
        val value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        val status = gattOperation("KISS subscribe") { gg -> writeDescriptor(gg, cccd, value) }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("KISS subscribe failed (status $status)")
    }

    private suspend fun enableNotifications(txChar: BluetoothGattCharacteristic) {
        val g = gatt ?: throw IOException("Bluetooth link not open")
        if (!g.setCharacteristicNotification(txChar, true)) throw IOException("Couldn't enable Bluetooth notifications")
        val cccd = txChar.getDescriptor(CCCD_UUID) ?: throw IOException(MSG_NO_CONFIG_SERVICE)
        val status = gattOperation("subscribe") { gg ->
            writeDescriptor(gg, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> Unit
            GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> throw IOException(MSG_STALE_PAIRING)
            // The handle came from a service table the tracker no longer has: the cache
            // refresh above is hidden API and can be unavailable on some Android builds.
            GATT_INVALID_HANDLE -> throw IOException(MSG_STALE_SERVICE_CACHE)
            else -> throw IOException("Bluetooth subscribe failed (status $status)")
        }
    }

    // -------------------------------------------------------------------------
    // Firmware updates
    // -------------------------------------------------------------------------

    override val updateServices: TrackerUpdateServices
        get() = TrackerUpdateServices(
            esp32Ota = otaControlChar != null && otaDataChar != null,
            nordicDfu = dfuControlChar != null,
        )

    override val otaLink: OtaLink?
        get() = if (updateServices.esp32Ota) esp32Ota else null

    private val esp32Ota = object : OtaLink {
        override val otaNotifications: SharedFlow<ByteArray> = _otaFlow

        override suspend fun enableOtaNotifications() =
            subscribe(otaControlChar ?: throw IOException("No update service"), "update service")

        override suspend fun writeOtaControl(bytes: ByteArray) {
            val c = otaControlChar ?: throw IOException("No update service")
            // START exceeds MTU − 3 only on a 23-byte MTU; Android then sends it as a long write.
            val status = gattOperation("update command") { g -> writeCharacteristic(g, c, bytes) }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth write failed (status $status)")
        }

        override suspend fun writeOtaData(bytes: ByteArray) {
            val c = otaDataChar ?: throw IOException("No update service")
            // Each packet waits for onCharacteristicWrite (the stack has taken it), so the
            // phone's own queue is never overrun. A full queue refuses the write
            // (Android 13+: ERROR_GATT_WRITE_REQUEST_BUSY): wait a moment and retry.
            repeat(WRITE_BUSY_RETRIES) {
                if (linkDead.isCompleted) throw IOException(linkDropMessage())
                try {
                    gattOperation("data write") { g ->
                        writeCharacteristic(g, c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    }
                    return
                } catch (_: GattBusyException) {
                    delay(WRITE_BUSY_RETRY_MS)
                }
            }
            throw IOException("Bluetooth stayed busy; the transfer stalled")
        }

        override val maxWritePayload: Int get() = (attMtu - 3).coerceAtLeast(20)

        override fun setHighThroughput(fast: Boolean) {
            runCatching {
                gatt?.requestConnectionPriority(
                    if (fast) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
                )
            }
        }
    }

    override suspend fun requestNordicDfuJump() {
        val c = dfuControlChar ?: throw IOException("This tracker has no DFU service")
        subscribe(c, "DFU control point")
        // The tracker reboots into its bootloader as soon as it takes this, so the write
        // response often never arrives; the caller treats a drop here as expected.
        gattOperation("DFU start") { g -> writeCharacteristic(g, c, DFU_START_APPLICATION) }
    }

    /** Enables notifications on [char] (bonded link; same error mapping as the config link). */
    private suspend fun subscribe(char: BluetoothGattCharacteristic, what: String) {
        val g = gatt ?: throw IOException("Bluetooth link not open")
        if (!g.setCharacteristicNotification(char, true)) throw IOException("Couldn't enable $what notifications")
        val cccd = char.getDescriptor(CCCD_UUID) ?: throw IOException("The $what can't notify")
        val status = gattOperation("$what subscribe") { gg ->
            writeDescriptor(gg, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> Unit
            GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> throw IOException(MSG_STALE_PAIRING)
            GATT_INVALID_HANDLE -> throw IOException(MSG_STALE_SERVICE_CACHE)
            else -> throw IOException("Bluetooth subscribe failed (status $status)")
        }
    }

    // -------------------------------------------------------------------------
    // GATT plumbing
    // -------------------------------------------------------------------------

    /** Android refused to queue the operation (its queue is busy), as opposed to the link failing. */
    private class GattBusyException(message: String) : IOException(message)

    /**
     * Runs one GATT operation and returns the status from its callback.
     * [start] returns false if Android refused to queue the operation.
     */
    private suspend fun gattOperation(name: String, start: (BluetoothGatt) -> Boolean): Int =
        gattMutex.withLock {
            val g = gatt ?: throw IOException("Bluetooth link not open")
            val done = CompletableDeferred<Int>()
            pendingOperation = done
            try {
                if (!start(g)) throw GattBusyException("Bluetooth $name could not be started")
                withTimeout(GATT_OP_TIMEOUT_MS) { done.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Bluetooth $name timed out")
            } finally {
                pendingOperation = null
            }
        }

    private fun writeCharacteristic(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = writeType
                c.value = value
                g.writeCharacteristic(c)
            }
        }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }

    private fun progress(message: String) {
        _connectionEvents.tryEmit(ConnectionEvent.Progress(message))
    }

    private fun releaseGatt() {
        configRxChar = null
        kissTxChar = null
        otaControlChar = null
        otaDataChar = null
        dfuControlChar = null
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectSignal?.complete(true)
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return

            // Unblock whatever the open sequence or a writer is waiting on.
            val wasOpened = synchronized(linkLock) {
                dropReason = "status $status"
                linkDead.complete(Unit)
                opened.also { opened = false }
            }
            connectSignal?.complete(false)
            pendingOperation?.completeExceptionally(IOException("Bluetooth link dropped (status $status)"))

            if (closed) return
            if (wasOpened) {
                _connectionEvents.tryEmit(ConnectionEvent.Lost("Bluetooth link lost (status $status)"))
                releaseGatt()
            }
            // While opening, the failed await above reports the error.
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu
            pendingOperation?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingOperation?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOperation?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOperation?.complete(status)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            route(c, value)
        }

        // API < 33 (not called on 33+ because the overload above is overridden)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            route(c, value)
        }

        private fun route(c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                CONFIG_TX_CHAR -> _rxFlow.tryEmit(value.copyOf())
                KISS_TX_CHAR   -> _kissFlow.tryEmit(value.copyOf())
                OTA_CONTROL_CHAR -> _otaFlow.tryEmit(value.copyOf())
            }
        }
    }
}
