package io.github.jeepnjonny.lorafieldops.firmware

/**
 * The tracker's answer to `version`. Current firmware sends two lines:
 * ```
 * version.date=v1.4.7-7-g4d54093
 * version.board=heltec_v3_433_aprs
 * ```
 * Older firmware sends only the first. [board] is null when it wasn't reported.
 */
data class TrackerVersionInfo(val version: String, val board: String?) {
    companion object {
        fun parse(response: String): TrackerVersionInfo {
            val fields = response.lineSequence()
                .map { it.trim() }
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1).trim() } }
                .toMap()
            val version = fields["version.date"] ?: response.trim()
            return TrackerVersionInfo(version, fields["version.board"]?.takeIf { it.isNotEmpty() })
        }
    }
}

enum class UpdateMethod {
    /** `ble_ota.h` protocol with `<board>_firmware.bin`. */
    ESP32_OTA,
    /** Nordic legacy DFU through the Adafruit bootloader with `heltec_t114_dfu.zip`. */
    NORDIC_DFU,
}

/** Whether a board id (from `version.board`) can be updated over Bluetooth, and with which file. */
sealed interface BoardUpdateSupport {
    data class Supported(val board: String, val method: UpdateMethod, val assetName: String) : BoardUpdateSupport
    /** Shown to the user as is. */
    data class NotSupported(val reason: String) : BoardUpdateSupport

    companion object {
        const val T114_BOARD = "heltec_t114"
        const val T114_ASSET = "heltec_t114_dfu.zip"

        /** ESP32 boards with a single app slot: no BLE update service, USB only. */
        private val SINGLE_SLOT_BOARDS = setOf("tbeam_433_aprs", "lilygo_t3_433_aprs")

        fun forBoard(board: String?): BoardUpdateSupport = when {
            board == null -> NotSupported(
                "This tracker's firmware doesn't report its board type. Update it once over USB with the web flasher.")
            board == "unknown" -> NotSupported(
                "This tracker runs a development build. Update it over USB.")
            board in SINGLE_SLOT_BOARDS -> NotSupported(
                "This board has room for only one firmware copy, so it can't be updated over Bluetooth. " +
                    "Use the USB web flasher.")
            board == T114_BOARD -> Supported(board, UpdateMethod.NORDIC_DFU, T114_ASSET)
            else -> Supported(board, UpdateMethod.ESP32_OTA, esp32AssetName(board))
        }

        fun esp32AssetName(board: String) = "${board}_firmware.bin"
    }
}

/** ESP-IDF application images start with this byte. */
private const val ESP_IMAGE_MAGIC: Byte = 0xE9.toByte()
/** Zip local-file header "PK\u0003\u0004". */
private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/**
 * Checks that [bytes] (named [fileName]) is the right kind of file for [support] before
 * anything is sent to the tracker. Returns a user-facing problem, or null when it looks right.
 * The tracker verifies the image again (board id, SHA-256, image header) either way.
 */
fun checkFirmwareFile(support: BoardUpdateSupport.Supported, fileName: String, bytes: ByteArray): String? {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    if (bytes.isEmpty()) return "The file is empty."
    return when (support.method) {
        UpdateMethod.ESP32_OTA -> when {
            name.endsWith("_web_factory.bin") || name.endsWith("_ota_update.bin") ->
                "\"$name\" is a USB-flasher image. Pick ${support.assetName} instead."
            !name.endsWith("_firmware.bin") ->
                "Pick the file named ${support.assetName}."
            name != support.assetName ->
                "\"$name\" is for a different board. This tracker needs ${support.assetName}."
            bytes[0] != ESP_IMAGE_MAGIC -> "\"$name\" isn't an ESP32 firmware image."
            else -> null
        }
        UpdateMethod.NORDIC_DFU -> when {
            !name.endsWith(".zip") || name.endsWith("_firmware.bin") ->
                "Pick the file named ${support.assetName}."
            name != support.assetName ->
                "\"$name\" is for a different board. This tracker needs ${support.assetName}."
            bytes.size < 4 || !bytes.copyOf(4).contentEquals(ZIP_MAGIC) -> "\"$name\" isn't a DFU package."
            else -> null
        }
    }
}
