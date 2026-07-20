package com.kj7nye.lorafieldops.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Data model — mirrors tracker_conf.json exactly so import/export round-trips
// without field loss. All @SerialName annotations match the firmware JSON keys.
// ---------------------------------------------------------------------------

@Serializable
data class TrackerConfig(
    val beacons: List<BeaconConfig> = listOf(BeaconConfig()),
    val lora: List<LoraConfig> = listOf(LoraConfig()),
    val display: DisplayConfig = DisplayConfig(),
    val battery: BatteryConfig = BatteryConfig(),
    val other: OtherConfig = OtherConfig(),
    val bluetooth: BluetoothConfig = BluetoothConfig(),
    val wifiAP: WifiAPConfig = WifiAPConfig(),
    val wifiSTA: WifiSTAConfig = WifiSTAConfig(),
    val aprsIS: AprsIsConfig = AprsIsConfig(),
    val tcpKISS: TcpKissConfig = TcpKissConfig(),
    val fixedPosition: FixedPositionConfig = FixedPositionConfig(),
    val deviceRole: Int = 0,
    val gpsSource: Int = 0,
    val customSmartBeacon: CustomSmartBeaconConfig = CustomSmartBeaconConfig(),
    @SerialName("pttTrigger")
    val pttTrigger: PttTriggerConfig = PttTriggerConfig(),
    val phg: PhgConfig = PhgConfig(),
)

@Serializable
data class BeaconConfig(
    val callsign: String = "NOCALL-7",
    val symbol: String = ">",
    val overlay: String = "/",
    @SerialName("micE")
    val micE: String = "",
    val comment: String = "LoRa APRS",
    val status: String = "",
    val smartBeaconActive: Boolean = true,
    val smartBeaconSetting: Int = 2,
    val profileLabel: String = "",
    val tacticalCallsign: String = "",
)

@Serializable
data class LoraConfig(
    val frequency: Long = 433775000L,
    val spreadingFactor: Int = 8,
    val signalBandwidth: Long = 62500L,
    val codingRate4: Int = 5,
    val power: Int = 20,
)

@Serializable
data class DisplayConfig(
    val ecoMode: Boolean = false,
    val timeout: Int = 4,
    val turn180: Boolean = false,
    val invertDisplay: Boolean = false,
    val ledEnabled: Boolean = true,
)

@Serializable
data class BatteryConfig(
    val sendVoltage: Boolean = false,
    val sendVoltageAlways: Boolean = false,
    val sleepVoltage: Float = 2.9f,
)

@Serializable
data class OtherConfig(
    val sendCommentAfterXBeacons: Int = 10,
    val beaconPath: String = "WIDE1-1",
    val nonSmartBeaconRate: Int = 15,
    val sendSpeedCourse: Boolean = true,
    val sendAltitude: Boolean = true,
    val digiMode: Int = 0,
)

@Serializable
data class BluetoothConfig(
    val active: Boolean = false,
    val deviceName: String = "LoRaAPRS",
)

@Serializable
data class WifiAPConfig(
    val password: String = "1234567890",
)

@Serializable
data class WifiNetworkConfig(
    val ssid: String = "",
    val password: String = "",
)

@Serializable
data class WifiSTAConfig(
    val enabled: Boolean = false,
    val networks: List<WifiNetworkConfig> = emptyList(),
)

@Serializable
data class AprsIsConfig(
    val server: String = "rotate.aprs.net",
    val port: Int = 14580,
    val passcode: String = "",
    val filter: String = "m/20",
    val downlinkEnabled: Boolean = true,
)

@Serializable
data class TcpKissConfig(
    val port: Int = 8001,
)

@Serializable
data class FixedPositionConfig(
    val latitude: Float = 0.0f,
    val longitude: Float = 0.0f,
    val elevation: Float = 0.0f,
)

@Serializable
data class CustomSmartBeaconConfig(
    val slowRate: Int = 120,
    val slowSpeed: Int = 5,
    val fastRate: Int = 60,
    val fastSpeed: Int = 40,
    val turnMinDeg: Int = 12,
    val turnSlope: Int = 60,
)

@Serializable
data class PttTriggerConfig(
    val active: Boolean = false,
    @SerialName("io_pin")
    val ioPin: Int = -1,
    val preDelay: Int = 0,
    val postDelay: Int = 0,
    val reverse: Boolean = false,
)

/** APRS PHG (Power-Height-Gain-Directivity) station capability beacon. */
@Serializable
data class PhgConfig(
    val enabled: Boolean = false,
    val power: Int = 7,
    val height: Int = 2,
    val gain: Int = 3,
    val directivity: Int = 0,
    val beaconRate: Int = 10,
)

// ---------------------------------------------------------------------------
// Enum helpers — kept as constants for command building; not part of JSON model
// ---------------------------------------------------------------------------

object DeviceRole {
    const val TRACKER = 0
    const val IGATE = 1
    const val DIGIPEATER = 2

    fun toCliString(v: Int) = when (v) {
        TRACKER -> "tracker"
        IGATE -> "igate"
        DIGIPEATER -> "digipeater"
        else -> "tracker"
    }
    fun label(v: Int) = when (v) {
        TRACKER -> "Tracker"
        IGATE -> "iGate"
        DIGIPEATER -> "Digipeater"
        else -> "Tracker"
    }
}

object GpsSource {
    const val INTERNAL = 0
    const val FIXED = 1
    const val NONE = 2

    fun toCliString(v: Int) = when (v) {
        INTERNAL -> "internal"
        FIXED -> "fixed"
        NONE -> "none"
        else -> "internal"
    }
    fun label(v: Int) = when (v) {
        INTERNAL -> "Internal GPS"
        FIXED -> "Fixed Position"
        NONE -> "None (TNC only)"
        else -> "Internal GPS"
    }
}

object DigiMode {
    const val OFF = 0
    const val WIDE1 = 1
    const val WIDE1_WIDE2 = 2

    fun toCliString(v: Int) = when (v) {
        OFF -> "off"
        WIDE1 -> "wide1"
        WIDE1_WIDE2 -> "wide1+wide2"
        else -> "off"
    }
    fun label(v: Int) = when (v) {
        OFF -> "Off"
        WIDE1 -> "WIDE1-1 (fill-in)"
        WIDE1_WIDE2 -> "WIDE1-1 + WIDE2 (infrastructure)"
        else -> "Off"
    }
}

/** SmartBeacon preset labels — index matches beacon.smartBeaconSetting */
val SMARTBEACON_LABELS = listOf("Runner", "Bike", "Car", "Jetboat", "Custom")

/** Firmware-documented beacon TX path presets (matches the web-config's `beaconpath` options). */
val BEACON_PATH_OPTIONS = listOf(
    "DIRECT" to "DIRECT (no repeat, iGate/digi)",
    "WIDE1-1" to "WIDE1-1 (1 hop)",
    "WIDE1-1,WIDE2-1" to "WIDE1-1,WIDE2-1 (2 hops)",
    "WIDE1-1,WIDE2-2" to "WIDE1-1,WIDE2-2 (3 hops)",
)
