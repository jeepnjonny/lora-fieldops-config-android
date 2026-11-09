package com.kj7nye.lorafieldops.serial

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SCAN_DURATION_MS = 10_000L

/** A tracker seen in a BLE scan. */
data class BleTrackerInfo(val device: BluetoothDevice, val name: String, val rssi: Int)

/** Runtime permissions needed to scan for and connect to trackers over BLE. */
fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Before Android 12, BLE scan results require location permission.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasBlePermissions(context: Context): Boolean =
    requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Finds trackers by the KISS TNC service UUID they advertise. The config service is
 * not advertised (no room in the 31-byte packet); [BleTransport] checks for it after
 * connecting. Scans stop on their own after [SCAN_DURATION_MS].
 */
@SuppressLint("MissingPermission")   // callers check hasBlePermissions() first
class BleScanner(context: Context) {

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _results = MutableStateFlow<List<BleTrackerInfo>>(emptyList())
    val results: StateFlow<List<BleTrackerInfo>> = _results.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isSupported: Boolean get() = adapter != null

    private val stopRunnable = Runnable { stop() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
                ?: result.device.address
            val entry = BleTrackerInfo(result.device, name, result.rssi)
            // Replace any earlier sighting of the same tracker (fresh RSSI), strongest first.
            _results.value = (_results.value.filterNot { it.device.address == result.device.address } + entry)
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _error.value = "Bluetooth scan failed (code $errorCode)"
            _scanning.value = false
        }
    }

    fun start() {
        _error.value = null
        val bt = adapter
        if (bt == null) { _error.value = "This phone has no Bluetooth LE"; return }
        if (!bt.isEnabled) { _error.value = "Turn on Bluetooth, then scan again"; return }
        val scanner = bt.bluetoothLeScanner ?: run { _error.value = "Bluetooth scanner unavailable"; return }
        if (_scanning.value) stop()

        _results.value = emptyList()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(KISS_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        _scanning.value = true
        mainHandler.postDelayed(stopRunnable, SCAN_DURATION_MS)
    }

    fun stop() {
        mainHandler.removeCallbacks(stopRunnable)
        if (!_scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
    }
}
