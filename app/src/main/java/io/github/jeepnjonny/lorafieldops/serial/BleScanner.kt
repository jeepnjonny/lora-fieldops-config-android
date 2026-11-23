package io.github.jeepnjonny.lorafieldops.serial

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.github.jeepnjonny.lorafieldops.platform.BleTrackerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SCAN_DURATION_MS = 10_000L

/**
 * Finds trackers by the KISS TNC service UUID they advertise. The config service is
 * not advertised (no room in the 31-byte packet); [BleTransport] checks for it after
 * connecting. Scans stop on their own after [SCAN_DURATION_MS].
 */
@SuppressLint("MissingPermission")   // callers check the Bluetooth permission first
class BleScanner(context: Context) : io.github.jeepnjonny.lorafieldops.platform.BleScanner {

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _results = MutableStateFlow<List<BleTrackerInfo>>(emptyList())
    override val results: StateFlow<List<BleTrackerInfo>> = _results.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    override val isSupported: Boolean get() = adapter != null

    private val stopRunnable = Runnable { stop() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // The ESP32 trackers' name doesn't fit next to the 128-bit service UUID, so it
            // comes only in the scan response; results carrying just the advertisement have
            // no name. Keep the last real name seen rather than falling back to the address.
            val previous = _results.value.firstOrNull { it.id == result.device.address }?.name
                ?.takeIf { it != result.device.address }
            val name = result.scanRecord?.deviceName
                ?: previous
                ?: runCatching { result.device.name }.getOrNull()
                ?: result.device.address
            val entry = BleTrackerInfo(id = result.device.address, name = name, rssi = result.rssi, handle = result.device)
            // Replace any earlier sighting of the same tracker (fresh RSSI), strongest first.
            _results.value = (_results.value.filterNot { it.id == result.device.address } + entry)
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _error.value = "Bluetooth scan failed (code $errorCode)"
            _scanning.value = false
        }
    }

    override fun start() {
        _error.value = null
        val bt = adapter
        if (bt == null) { _error.value = "This phone has no Bluetooth LE"; return }
        if (!bt.isEnabled) { _error.value = "Turn on Bluetooth, then scan again"; return }
        val scanner = bt.bluetoothLeScanner ?: run { _error.value = "Bluetooth scanner unavailable"; return }
        if (_scanning.value) stop()

        _results.value = emptyList()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(KISS_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        _scanning.value = true
        mainHandler.postDelayed(stopRunnable, SCAN_DURATION_MS)
    }

    override fun stop() {
        mainHandler.removeCallbacks(stopRunnable)
        if (!_scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
    }
}
