package com.kj7nye.lorafieldops.serial

import com.kj7nye.lorafieldops.platform.ByteBuilder

// KISS special bytes (firmware kiss_utils.h)
const val KISS_FEND: Int  = 0xC0
const val KISS_FESC: Int  = 0xDB
const val KISS_TFEND: Int = 0xDC
const val KISS_TFESC: Int = 0xDD

/** KISS command nibble: AX.25 data frame. */
const val KISS_CMD_DATA: Int = 0x00
/** KISS command nibble: SetHardware. The firmware uses it to report a received frame's RSSI/SNR. */
const val KISS_CMD_SET_HARDWARE: Int = 0x06

/** Longest frame kept; anything bigger is a lost FEND and is dropped. */
private const val MAX_FRAME_BYTES = 1024

/** One unescaped KISS frame. [command] is the low nibble of the type byte, [port] the high nibble. */
class KissFrame(val port: Int, val command: Int, val payload: ByteArray) {
    val isData: Boolean get() = command == KISS_CMD_DATA
}

/**
 * Reassembles KISS frames from a byte stream that arrives in arbitrary pieces. BLE
 * notifications can split one frame across several notifications (the ESP32 firmware
 * sends large frames in chunks) or carry the end of one frame and the start of the
 * next, so state is kept between [feed] calls.
 *
 * Bytes before the first FEND are discarded: a stream joined mid-frame can't be trusted.
 * Empty frames (back-to-back FENDs) are skipped. Not thread-safe; feed it from one place.
 */
class KissDeframer {
    private val buf = ByteBuilder()
    private var synced = false
    private var escaped = false
    private var overflow = false

    fun reset() {
        buf.reset()
        synced = false
        escaped = false
        overflow = false
    }

    fun feed(bytes: ByteArray): List<KissFrame> {
        val out = mutableListOf<KissFrame>()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == KISS_FEND) {
                if (synced && !overflow && buf.size() > 0) {
                    val raw = buf.toByteArray()
                    val type = raw[0].toInt() and 0xFF
                    out += KissFrame(port = type shr 4, command = type and 0x0F, payload = raw.copyOfRange(1, raw.size))
                }
                buf.reset()
                synced = true
                escaped = false
                overflow = false
                continue
            }
            if (!synced) continue
            val byte = if (escaped) {
                escaped = false
                when (v) {
                    KISS_TFEND -> KISS_FEND
                    KISS_TFESC -> KISS_FESC
                    else -> v   // protocol violation: keep the byte rather than lose the frame
                }
            } else if (v == KISS_FESC) {
                escaped = true
                continue
            } else v
            if (buf.size() >= MAX_FRAME_BYTES) overflow = true else buf.write(byte)
        }
        return out
    }
}

/** Escapes [payload] into a complete KISS frame (FEND type data FEND). Used by tests and tools. */
fun kissEncode(payload: ByteArray, command: Int = KISS_CMD_DATA, port: Int = 0): ByteArray {
    val out = ByteBuilder()
    out.write(KISS_FEND)
    out.write(((port and 0x0F) shl 4) or (command and 0x0F))
    for (b in payload) {
        when (val v = b.toInt() and 0xFF) {
            KISS_FEND -> { out.write(KISS_FESC); out.write(KISS_TFEND) }
            KISS_FESC -> { out.write(KISS_FESC); out.write(KISS_TFESC) }
            else -> out.write(v)
        }
    }
    out.write(KISS_FEND)
    return out.toByteArray()
}
