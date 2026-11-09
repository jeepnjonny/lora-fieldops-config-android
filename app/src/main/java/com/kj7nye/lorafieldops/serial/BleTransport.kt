package com.kj7nye.lorafieldops.serial

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
import java.io.IOException
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

/** APRS.fi KISS TNC service: the UUID the tracker advertises (used to find it in a scan). */
val KISS_SERVICE_UUID: UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")

/** Configuration link (Nordic UART Service layout): carries the setup CLI. Not advertised. */
val CONFIG_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val CONFIG_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")  // phone writes
private val CONFIG_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")  // tracker notifies
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val REQUESTED_MTU          = 247
private const val DEFAULT_ATT_MTU        = 23
private const val CONNECT_TIMEOUT_MS     = 15_000L
/** Covers the user reading the PIN off the tracker and typing it into the system dialog. */
private const val BOND_TIMEOUT_MS        = 90_000L
private const val BOND_POLL_INTERVAL_MS  = 500L
private const val GATT_OP_TIMEOUT_MS     = 10_000L
/** Lets the tracker's main loop register the subscription before the first CLI bytes arrive. */
private const val LINK_SETTLE_MS         = 300L
/** Gives the Bluetooth stack time to drop its service cache before rediscovery. */
private const val GATT_REFRESH_SETTLE_MS = 500L

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
) : DeviceTransport {

    override val kind = TransportKind.BLE

    private val _rxFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val rxFlow: SharedFlow<ByteArray> = _rxFlow

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    private val scope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gattMutex = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var configRxChar: BluetoothGattCharacteristic? = null
    @Volatile private var attMtu = DEFAULT_ATT_MTU
    @Volatile private var opened = false
    @Volatile private var closed = false

    // Completed from BluetoothGattCallback (binder thread); awaited by the open sequence / writers.
    @Volatile private var connectSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingOperation: CompletableDeferred<Int>? = null

    override val isOpen: Boolean get() = opened

    /** Starts the open sequence; the outcome arrives on [connectionEvents]. */
    fun open() {
        scope.launch {
            try {
                runOpenSequence()
                opened = true
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
        val txChar = configService.getCharacteristic(CONFIG_TX_CHAR_UUID)
            ?: throw IOException(MSG_NO_CONFIG_SERVICE)
        configRxChar = configService.getCharacteristic(CONFIG_RX_CHAR_UUID)
            ?: throw IOException(MSG_NO_CONFIG_SERVICE)

        ensureBonded()

        enableNotifications(txChar)
        delay(LINK_SETTLE_MS)
    }

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
                val changed: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
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
        if (refreshGattCache()) delay(GATT_REFRESH_SETTLE_MS)
        val status = gattOperation("service discovery") { g -> g.discoverServices() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth service discovery failed (status $status)")
        return gatt?.getService(CONFIG_SERVICE_UUID) ?: throw IOException(MSG_NO_CONFIG_SERVICE)
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
    // GATT plumbing
    // -------------------------------------------------------------------------

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
                if (!start(g)) throw IOException("Bluetooth $name could not be started")
                withTimeout(GATT_OP_TIMEOUT_MS) { done.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Bluetooth $name timed out")
            } finally {
                pendingOperation = null
            }
        }

    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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

    /** BluetoothGatt.refresh() is hidden API; it is the only way to drop a stale service cache. */
    private fun refreshGattCache(): Boolean = try {
        val g = gatt
        g != null && (g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false)
    } catch (_: Exception) {
        false
    }

    private fun progress(message: String) {
        _connectionEvents.tryEmit(ConnectionEvent.Progress(message))
    }

    private fun releaseGatt() {
        configRxChar = null
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
            connectSignal?.complete(false)
            pendingOperation?.completeExceptionally(IOException("Bluetooth link dropped (status $status)"))

            if (closed) return
            if (opened) {
                opened = false
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
            if (c.uuid == CONFIG_TX_CHAR_UUID) _rxFlow.tryEmit(value.copyOf())
        }

        // API < 33 (not called on 33+ because the overload above is overridden)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            if (c.uuid == CONFIG_TX_CHAR_UUID) _rxFlow.tryEmit(value.copyOf())
        }
    }
}
