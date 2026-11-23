package io.github.jeepnjonny.lorafieldops.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTION_USB_PERMISSION = "io.github.jeepnjonny.lorafieldops.USB_PERMISSION"
private const val READ_TIMEOUT_MS = 100
private const val WRITE_TIMEOUT_MS = 2000
private const val BAUD_RATE = 115200
private const val READ_BUFFER_SIZE = 4096

/** Raw USB CDC-ACM transport: open/close, read bytes as SharedFlow, write bytes. */
class SerialManager(private val context: Context) : DeviceTransport {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var port: UsbSerialPort? = null
    private var readScope: CoroutineScope? = null
    /** The one outstanding permission receiver, if a request is in progress. */
    private var permissionReceiver: BroadcastReceiver? = null

    override val kind = TransportKind.USB

    private val _rxFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val rxFlow: SharedFlow<ByteArray> = _rxFlow

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Returns all CDC-ACM-compatible devices currently attached. */
    fun findDevices(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    /**
     * Requests USB permission for [device] and calls [onResult] with granted=true/false.
     * Safe to call when permission is already held — onResult fires synchronously with true.
     */
    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) { onResult(true); return }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)

        // Only the latest request's callback should fire.
        unregisterPermissionReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION != intent.action) return
                if (permissionReceiver === this) unregisterPermissionReceiver()
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(granted)
            }
        }
        permissionReceiver = receiver
        // ContextCompat handles the RECEIVER_NOT_EXPORTED flag API level check internally;
        // accessing Context.RECEIVER_NOT_EXPORTED directly crashes on API < 33 in Kotlin
        // because the Kotlin compiler does not inline Java static final int fields.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        usbManager.requestPermission(device, pi)
    }

    /**
     * Opens [driver]'s first port at 115200 8N1 and starts the background read loop.
     * Emits [ConnectionEvent.Opened] on success or [ConnectionEvent.Error] on failure.
     */
    fun open(driver: UsbSerialDriver) {
        close()
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            emitEvent(ConnectionEvent.Error("Failed to open USB connection — permission denied?"))
            return
        }
        if (driver.ports.isEmpty()) {
            connection.close()
            emitEvent(ConnectionEvent.Error("USB driver reports no ports for this device"))
            return
        }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            if (driver is CdcAcmSerialDriver) {
                // TinyUSB CDC (nRF52840, ESP32-S3 native USB) checks tud_cdc_connected() before
                // writing, which requires DTR asserted by the host. Without this, Serial.print()
                // calls in the firmware are silently dropped and we never receive any response.
                p.dtr = true
                p.rts = true
            } else {
                // UART-bridge boards (CP210x/CH340/FTDI) — including the Heltec V3.2's
                // CP2102N — wire RTS to EN and DTR to GPIO0 for esptool's auto-reset
                // circuit (both active-low). Deasserted/deasserted is the neutral,
                // both-lines-released state (EN high, GPIO0 high) that leaves the
                // board running normally. Asserting DTR alone holds GPIO0 low for the
                // whole session — which on boards where GPIO0 doubles as the USR
                // button reads as a stuck button press — and asserting both together
                // is worse: it holds EN low, keeping the chip in reset the entire
                // time it's connected. So leave both deasserted on these boards.
                p.dtr = false
                p.rts = false
            }
        } catch (e: Exception) {
            // p.close() also closes the connection once p.open succeeded; close it
            // directly too in case open itself threw.
            try { p.close() } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
            emitEvent(ConnectionEvent.Error("Port open failed: ${e.message}"))
            return
        }
        port = p
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        readScope = scope
        scope.launch { readLoop(p) }
        emitEvent(ConnectionEvent.Opened(driver.device.productName ?: "USB device"))
    }

    /** Closes the port and stops the read loop. */
    override fun close() {
        readScope?.cancel()
        readScope = null
        // Clear the field first so the read loop knows the close was ours.
        val p = port
        port = null
        try { p?.close() } catch (_: Exception) {}
        unregisterPermissionReceiver()
    }

    private fun unregisterPermissionReceiver() {
        val r = permissionReceiver ?: return
        permissionReceiver = null
        try { context.unregisterReceiver(r) } catch (_: IllegalArgumentException) {}
    }

    /** Returns true if the port is currently open. */
    override val isOpen: Boolean get() = port != null

    /** Writes [data] to the serial port (blocking I/O, moved off the caller's thread). Throws on failure. */
    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        port?.write(data, WRITE_TIMEOUT_MS)
            ?: error("Serial port not open")
    }

    // ---------------------------------------------------------------------------
    // Private
    // ---------------------------------------------------------------------------

    /** Reads [p] until it fails or is closed. Runs only for the port it was started with. */
    private suspend fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (currentCoroutineContext().isActive && port === p) {
            try {
                val n = p.read(buf, READ_TIMEOUT_MS)
                if (n > 0) {
                    _rxFlow.tryEmit(buf.copyOf(n))
                }
            } catch (e: Exception) {
                // A read failing because we closed (or replaced) the port isn't a lost link;
                // reporting it would end the next session the moment it opens.
                if (currentCoroutineContext().isActive && port === p) {
                    emitEvent(ConnectionEvent.Lost(e.message ?: "IO error"))
                }
                break
            }
        }
    }

    private fun emitEvent(event: ConnectionEvent) {
        _connectionEvents.tryEmit(event)
    }
}
