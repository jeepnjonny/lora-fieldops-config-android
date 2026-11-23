package io.github.jeepnjonny.lorafieldops.firmware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.jeepnjonny.lorafieldops.serial.GATT_REFRESH_SETTLE_MS
import io.github.jeepnjonny.lorafieldops.serial.NORDIC_DFU_SERVICE_UUID
import io.github.jeepnjonny.lorafieldops.serial.NORDIC_DFU_VERSION_CHAR_UUID
import io.github.jeepnjonny.lorafieldops.serial.refreshServiceCache
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper

private val DFU_SERVICE: UUID = UUID.fromString(NORDIC_DFU_SERVICE_UUID)
private val DFU_VERSION_CHAR: UUID = UUID.fromString(NORDIC_DFU_VERSION_CHAR_UUID)

/** Services the Adafruit bootloader may list besides DFU: GAP, GATT, Device Information. */
private val BOOTLOADER_SERVICES: Set<UUID> = setOf(
    "00001800-0000-1000-8000-00805f9b34fb",
    "00001801-0000-1000-8000-00805f9b34fb",
    "0000180a-0000-1000-8000-00805f9b34fb",
).map(UUID::fromString).toSet() + DFU_SERVICE

/** DFU version 0.8 (little-endian) means "bootloader", so the library won't try another jump. */
private val BOOTLOADER_DFU_VERSION = byteArrayOf(0x08, 0x00)

/**
 * Packet Receipt Notification interval. The Adafruit bootloader 0.9.0 on every T114
 * has 8 RX buffers: PRN must be 8 or less, and the library default (10) fails with
 * "OPERATION FAILED" about 0.6 s into the transfer. Found on hardware; don't raise it.
 */
private const val PRN_VALUE = 6

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val GATT_OP_TIMEOUT_MS = 10_000L
/** The bootloader needs a moment to start advertising after the jump. */
private const val CONNECT_ATTEMPTS = 4
private const val CONNECT_RETRY_DELAY_MS = 2_000L
/** Lets the stack finish tearing down our link before the DFU library connects. */
private const val HANDOFF_DELAY_MS = 1_000L

/**
 * The T114 half of a firmware update, after the application has jumped to its
 * bootloader. Follows the rules found on hardware:
 * 1. Reconnect to the same address (the bootloader keeps it and the bond).
 * 2. Drop Android's cached service table (it still holds the application's), wait,
 *    rediscover, and check that only the bootloader's services are there and DFU
 *    version reads 0.8. Without the refresh the library waits for a Service Changed
 *    indication that never takes effect and fails with GATT INVALID HANDLE.
 * 3. Close, then run Nordic DFU with keep-bond on and PRN 6.
 */
@SuppressLint("MissingPermission")
class NordicDfuRunner(private val context: Context) {

    suspend fun run(device: BluetoothDevice, zip: ByteArray, onProgress: (percent: Int, kbPerSecond: Float) -> Unit) {
        val file = File(context.cacheDir, "dfu/heltec_t114_dfu.zip")
        file.parentFile?.mkdirs()
        file.writeBytes(zip)
        try {
            prepareBootloader(device)
            delay(HANDOFF_DELAY_MS)
            upload(device, file, onProgress)
        } finally {
            file.delete()
        }
    }

    private suspend fun prepareBootloader(device: BluetoothDevice) {
        var last: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(CONNECT_RETRY_DELAY_MS)
            try {
                checkBootloader(device)
                return
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Couldn't reach the tracker's bootloader")
    }

    private suspend fun checkBootloader(device: BluetoothDevice) {
        val probe = GattProbe(context, device)
        try {
            probe.connect()
            // The bonded link encrypts by itself when the bootloader asks for it.
            probe.gatt.refreshServiceCache()
            delay(GATT_REFRESH_SETTLE_MS)
            probe.discoverServices()
            val services = probe.gatt.services.map { it.uuid }.toSet()
            if (DFU_SERVICE !in services) {
                throw IOException("The tracker didn't start its update mode (no DFU service).")
            }
            if (!BOOTLOADER_SERVICES.containsAll(services)) {
                throw IOException("The tracker is still running its normal firmware, not its update mode.")
            }
            val versionChar = probe.gatt.getService(DFU_SERVICE)?.getCharacteristic(DFU_VERSION_CHAR)
                ?: throw IOException("The tracker's bootloader has no DFU version.")
            val version = probe.read(versionChar)
            if (!version.contentEquals(BOOTLOADER_DFU_VERSION)) {
                throw IOException("Unexpected DFU version ${version.joinToString(" ") { "%02x".format(it) }} " +
                    "(expected the bootloader's 08 00).")
            }
        } finally {
            probe.close()
        }
    }

    private suspend fun upload(device: BluetoothDevice, file: File, onProgress: (Int, Float) -> Unit) {
        DfuServiceInitiator.createDfuNotificationChannel(context)
        val done = CompletableDeferred<Unit>()
        val listener = object : DfuProgressListenerAdapter() {
            override fun onProgressChanged(
                deviceAddress: String, percent: Int, speed: Float, avgSpeed: Float, currentPart: Int, partsTotal: Int,
            ) {
                // The library reports bytes per millisecond, i.e. kB/s.
                onProgress(percent, avgSpeed * 1000f / 1024f)
            }

            override fun onDfuCompleted(deviceAddress: String) { done.complete(Unit) }

            override fun onDfuAborted(deviceAddress: String) {
                done.completeExceptionally(IOException("The update was stopped."))
            }

            override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                done.completeExceptionally(IOException("Bluetooth update error: ${message ?: "code $error"}."))
            }
        }
        DfuServiceListenerHelper.registerProgressListener(context, listener, device.address)
        try {
            val controller = DfuServiceInitiator(device.address)
                .setDeviceName(device.name)
                // The bootloader accepts the encrypted reconnect only with the bond.
                .setKeepBond(true)
                .setPacketsReceiptNotificationsEnabled(true)
                .setPacketsReceiptNotificationsValue(PRN_VALUE)
                .setZip(file.absolutePath)
                .start(context, TrackerDfuService::class.java)
            try {
                done.await()
            } catch (e: CancellationException) {
                controller.abort()
                throw e
            }
        } finally {
            DfuServiceListenerHelper.unregisterProgressListener(context, listener)
        }
    }
}

/** A short-lived GATT client: connect, discover, read one characteristic, close. */
@SuppressLint("MissingPermission")
private class GattProbe(private val context: Context, private val device: BluetoothDevice) {
    private var connected = CompletableDeferred<Boolean>()
    @Volatile private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private var _gatt: BluetoothGatt? = null
    val gatt: BluetoothGatt get() = _gatt ?: throw IOException("Not connected")

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                    connected.complete(true)
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    connected.complete(false)
                    pending?.completeExceptionally(IOException("Bluetooth link dropped (status $status)"))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status to null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(status to value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pending?.complete(status to c.value)
        }
    }

    suspend fun connect() {
        @Suppress("DEPRECATION")
        _gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Bluetooth connection could not be started")
        val ok = try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        } catch (_: TimeoutCancellationException) {
            throw IOException("The tracker's bootloader didn't answer.")
        }
        if (!ok) throw IOException("The tracker's bootloader refused the connection.")
    }

    suspend fun discoverServices() {
        val (status, _) = operation("service discovery") { it.discoverServices() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Service discovery failed (status $status)")
    }

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray {
        val (status, value) = operation("read") { it.readCharacteristic(c) }
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) throw IOException("Bluetooth read failed (status $status)")
        return value
    }

    private suspend fun operation(name: String, start: (BluetoothGatt) -> Boolean): Pair<Int, ByteArray?> {
        val done = CompletableDeferred<Pair<Int, ByteArray?>>()
        pending = done
        try {
            if (!start(gatt)) throw IOException("Bluetooth $name could not be started")
            return withTimeout(GATT_OP_TIMEOUT_MS) { done.await() }
        } catch (_: TimeoutCancellationException) {
            throw IOException("Bluetooth $name timed out")
        } finally {
            pending = null
        }
    }

    fun close() {
        _gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        _gatt = null
    }
}
