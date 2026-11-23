package io.github.jeepnjonny.lorafieldops.platform

import androidx.compose.runtime.Composable
import io.github.jeepnjonny.lorafieldops.firmware.OtaLink
import io.github.jeepnjonny.lorafieldops.logger.CoverageLogger
import io.github.jeepnjonny.lorafieldops.serial.DeviceTransport
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the shared app needs from the phone it runs on. Android and iOS each
 * provide one instance for the life of the process.
 */
interface AppPlatform {
    /** "Android" or "iOS", for messages such as "forget it in iOS Bluetooth settings". */
    val osName: String
    val bleScanner: BleScanner
    /** A new, unopened Bluetooth link to [tracker]. Single use: make another to reconnect. */
    fun bleLink(tracker: BleTrackerInfo): BleLink
    /** USB serial, or null where the phone can't talk to the tracker over USB (iPhone). */
    val usb: UsbSupport?
    val location: LocationSource
    val coverageLogger: CoverageLogger
    /** Hands [text] to the system share sheet. */
    fun shareText(text: String, subject: String)
    /** Firmware updates over Bluetooth, or null where the app doesn't offer them yet. */
    val firmware: FirmwarePlatform? get() = null
}

/** A tracker seen in a Bluetooth scan. [id] is stable on this phone; [handle] is the platform's device object. */
data class BleTrackerInfo(val id: String, val name: String, val rssi: Int, val handle: Any)

/** Scans for trackers by the KISS service UUID they advertise. Scans stop on their own. */
interface BleScanner {
    val results: StateFlow<List<BleTrackerInfo>>
    val scanning: StateFlow<Boolean>
    val error: StateFlow<String?>
    val isSupported: Boolean
    fun start()
    fun stop()
}

/**
 * The tracker's Bluetooth link: the setup CLI (as a [DeviceTransport]) plus the KISS
 * notifications the coverage logger listens to.
 */
interface BleLink : DeviceTransport {
    /** Raw KISS bytes from the tracker, while notifications are on. */
    val kissFlow: SharedFlow<ByteArray>
    /** Starts connecting; progress and the outcome arrive as [connectionEvents]. */
    fun open()
    /** Turns the KISS notifications on or off. Throws when the tracker has no KISS service. */
    suspend fun setKissNotifications(enabled: Boolean)

    /** Firmware update services the tracker offered when the link opened. */
    val updateServices: TrackerUpdateServices get() = TrackerUpdateServices()
    /** The ESP32 update service on this link, when [TrackerUpdateServices.esp32Ota] is set. */
    val otaLink: OtaLink? get() = null
    /**
     * T114: asks the running firmware to reboot into its DFU bootloader through the
     * buttonless DFU service (`01 04` on the control point). The link drops right after.
     */
    suspend fun requestNordicDfuJump() {
        throw UnsupportedOperationException("Nordic DFU isn't available on this platform")
    }
}

/** Update services found by service discovery: support is detected this way, never by version number. */
data class TrackerUpdateServices(val esp32Ota: Boolean = false, val nordicDfu: Boolean = false)

/** A file the user picked; [name] is its display name. */
class PickedFile(val name: String, val bytes: ByteArray)

/** The platform half of firmware updates. */
interface FirmwarePlatform {
    /** HTTP GET, following redirects. Throws with a user-facing message on failure. */
    suspend fun httpGet(url: String, onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> }): ByteArray

    /**
     * T114 after the jump to the bootloader: reconnects to [tracker]'s address,
     * drops the stale service cache, checks that the bootloader is there, then runs
     * the Nordic DFU upload of [zip]. Throws with a user-facing message on failure.
     */
    suspend fun runNordicDfu(tracker: BleTrackerInfo, zip: ByteArray, onProgress: (percent: Int, kbPerSecond: Float) -> Unit)

    /** Keeps the display on while composed. */
    @Composable fun KeepScreenOn()

    /** Returns a launcher for the system file picker; [onPicked] gets null when cancelled or unreadable. */
    @Composable fun rememberFilePicker(onPicked: (PickedFile?) -> Unit): () -> Unit
}

/** A USB serial device found on the phone's USB port. */
data class UsbDeviceInfo(val title: String, val subtitle: String, val handle: Any)

interface UsbSupport {
    /** The USB serial link; one per app. */
    val transport: DeviceTransport
    fun findDevices(): List<UsbDeviceInfo>
    /** Asks the user for access to [device] if needed, then reports the answer. */
    fun requestPermission(device: UsbDeviceInfo, onResult: (granted: Boolean) -> Unit)
    /** Opens [device]; the outcome arrives as [transport]'s connection events. */
    fun open(device: UsbDeviceInfo)
}

/** A one-shot position fix from the phone. */
sealed interface LocationResult {
    data class Fix(val latitude: Double, val longitude: Double, val altitude: Double?, val accuracyM: Float?) : LocationResult
    data class Failed(val message: String) : LocationResult
}

interface LocationSource {
    /**
     * Waits for one fix. Permission has been granted (Android asks in the UI first; iOS
     * asks here). Cancel to stop waiting.
     */
    suspend fun currentFix(): LocationResult
}
