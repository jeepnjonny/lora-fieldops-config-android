package io.github.jeepnjonny.lorafieldops.platform

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
