package io.github.jeepnjonny.lorafieldops.firmware

/**
 * Wire format of the ESP32 firmware update over BLE. The normative spec is
 * `include/ble_ota.h` in KJ7NYE/LoRa_FieldOps_APRS_Tracker; if this file
 * disagrees with it, the header wins. All integers are little-endian.
 */
object OtaCodec {
    const val PROTOCOL_VERSION = 1
    /** Each data packet starts with the u32 offset of its payload. */
    const val DATA_HEADER_BYTES = 4
    /** The firmware ignores anything longer (matches the reference client). */
    private const val MAX_VERSION_BYTES = 32
    private const val MAX_BOARD_BYTES = 255

    private const val CMD_START: Byte = 0x01
    private const val CMD_END: Byte = 0x02
    private const val CMD_ABORT: Byte = 0x03

    private const val NOTIFY_READY = 0x81
    private const val NOTIFY_ACK = 0x82
    private const val NOTIFY_RESYNC = 0x83
    private const val NOTIFY_DONE = 0x84
    private const val NOTIFY_ERROR = 0x85
    private const val NOTIFY_ABORTED = 0x86

    /** `0x01 START [u8 protocol][u32 imageSize][32 B SHA-256][u8 n][board][u8 m][version]` */
    fun start(imageSize: Int, sha256: ByteArray, board: String, version: String): ByteArray {
        require(sha256.size == 32) { "SHA-256 must be 32 bytes" }
        val boardBytes = board.encodeToByteArray().let { it.copyOf(minOf(it.size, MAX_BOARD_BYTES)) }
        val versionBytes = version.encodeToByteArray().let { it.copyOf(minOf(it.size, MAX_VERSION_BYTES)) }
        return byteArrayOf(CMD_START, PROTOCOL_VERSION.toByte()) + u32(imageSize) + sha256 +
            byteArrayOf(boardBytes.size.toByte()) + boardBytes +
            byteArrayOf(versionBytes.size.toByte()) + versionBytes
    }

    fun end(): ByteArray = byteArrayOf(CMD_END)
    fun abort(): ByteArray = byteArrayOf(CMD_ABORT)

    /** `[u32 offset][payload]` for the data characteristic. */
    fun data(offset: Int, image: ByteArray, length: Int): ByteArray =
        u32(offset) + image.copyOfRange(offset, offset + length)

    /** Decodes a control notification; null for anything unrecognised or truncated. */
    fun parse(bytes: ByteArray): OtaNotification? {
        if (bytes.isEmpty()) return null
        return when (bytes[0].toInt() and 0xff) {
            NOTIFY_READY -> if (bytes.size >= 10)
                OtaNotification.Ready(bytes[1].toInt() and 0xff, readU32(bytes, 2), readU32(bytes, 6)) else null
            NOTIFY_ACK -> if (bytes.size >= 5) OtaNotification.Ack(readU32(bytes, 1)) else null
            NOTIFY_RESYNC -> if (bytes.size >= 5) OtaNotification.Resync(readU32(bytes, 1)) else null
            NOTIFY_DONE -> OtaNotification.Done
            NOTIFY_ERROR -> OtaNotification.Error(
                code = if (bytes.size >= 2) bytes[1].toInt() and 0xff else 0,
                text = if (bytes.size > 2) bytes.copyOfRange(2, bytes.size).decodeToString() else "",
            )
            NOTIFY_ABORTED -> OtaNotification.Aborted
            else -> null
        }
    }

    internal fun u32(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    internal fun readU32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or (b[at + 1].toInt() and 0xff shl 8) or
            (b[at + 2].toInt() and 0xff shl 16) or (b[at + 3].toInt() and 0xff shl 24)
}

sealed interface OtaNotification {
    data class Ready(val protocol: Int, val window: Int, val ackInterval: Int) : OtaNotification
    /** Bytes committed to flash. */
    data class Ack(val bytesWritten: Int) : OtaNotification
    /** A packet arrived out of order: resend from [expectedOffset]. */
    data class Resync(val expectedOffset: Int) : OtaNotification
    /** Verified and installed; the tracker reboots in ~1.5 s. */
    data object Done : OtaNotification
    /** The tracker closed the session. */
    data class Error(val code: Int, val text: String) : OtaNotification
    data object Aborted : OtaNotification
}

/** Tracker error codes from `BLE_OTA::ErrorCode`. */
object OtaError {
    const val BAD_REQUEST = 1
    const val PROTOCOL_VERSION = 2
    const val WRONG_BOARD = 3
    const val IMAGE_SIZE = 4
    const val BUSY = 5
    const val NO_MEMORY = 6
    const val FLASH = 7
    const val VERIFY = 8
    const val TIMEOUT = 9
    const val FLOW = 10
    const val INCOMPLETE = 11

    /** A user-facing sentence for [code], followed by the tracker's own text when it sent one. */
    fun describe(code: Int, text: String): String {
        val summary = when (code) {
            BAD_REQUEST -> "The tracker rejected the update request."
            PROTOCOL_VERSION -> "The tracker uses a different update protocol. Update the app, or update the tracker over USB."
            WRONG_BOARD -> "This firmware file is for a different board."
            IMAGE_SIZE -> "The firmware file is empty or too large for this tracker."
            BUSY -> "The tracker is already being updated (from its web page?). Try again when that finishes."
            NO_MEMORY -> "The tracker ran out of memory. Reboot it and try again."
            FLASH -> "The tracker couldn't write the firmware to flash."
            VERIFY -> "The firmware didn't verify on the tracker (damaged download or wrong file)."
            TIMEOUT -> "The tracker stopped waiting for data. Keep the phone close and try again."
            FLOW -> "The transfer got out of step with the tracker. Try again."
            INCOMPLETE -> "The tracker didn't receive the whole file. Try again."
            else -> "The tracker reported error $code."
        }
        val detail = text.trim()
        return if (detail.isEmpty()) summary else "$summary ($detail)"
    }
}
