package com.kj7nye.lorafieldops.serial

import com.kj7nye.lorafieldops.platform.ByteBuilder
import com.kj7nye.lorafieldops.platform.decodeLatin1

private const val ADDRESS_LEN   = 7
private const val MAX_DIGIS     = 8
private const val H_BIT         = 0x80
private const val LAST_ADDR_BIT = 0x01
private const val CONTROL_UI    = 0x03
private const val PID_NO_L3     = 0xF0

/** One AX.25 address. [hBit] is the "has been repeated" bit (only meaningful on path entries). */
data class Ax25Address(val callsign: String, val ssid: Int, val hBit: Boolean = false) {
    /** "K7ABC-9", or "K7ABC" for SSID 0 (TNC2 convention). */
    val text: String get() = if (ssid == 0) callsign else "$callsign-$ssid"
}

/** A decoded AX.25 UI frame. [info] holds the information field bytes exactly as received. */
class Ax25Packet(
    val destination: Ax25Address,
    val source: Ax25Address,
    val path: List<Ax25Address>,
    val info: ByteArray,
) {
    /** True when any path entry has its H bit set: the frame was heard via a digipeater. */
    val repeated: Boolean get() = path.any { it.hBit }

    /**
     * TNC2 text: `SRC-SSID>DEST,PATH1*,PATH2:info`. Every path entry whose H bit is set
     * gets a `*`. The information field is mapped byte-for-byte through ISO-8859-1, so no
     * byte is lost or altered.
     */
    fun toTnc2(): String = buildString {
        append(source.text).append('>').append(destination.text)
        path.forEach { append(',').append(it.text); if (it.hBit) append('*') }
        append(':')
        append(info.decodeLatin1())
    }
}

object Ax25 {

    /**
     * Decodes a binary AX.25 UI frame (the payload of a KISS data frame). Returns null
     * for anything that isn't a plausible UI frame: too short, bad address characters,
     * more than 8 digipeaters, or a control field other than UI.
     */
    fun decode(frame: ByteArray): Ax25Packet? {
        val addresses = mutableListOf<Ax25Address>()
        var pos = 0
        while (true) {
            if (pos + ADDRESS_LEN > frame.size) return null
            val addr = decodeAddress(frame, pos) ?: return null
            addresses += addr
            val last = (frame[pos + ADDRESS_LEN - 1].toInt() and LAST_ADDR_BIT) != 0
            pos += ADDRESS_LEN
            if (last) break
            if (addresses.size >= 2 + MAX_DIGIS) return null
        }
        if (addresses.size < 2) return null
        if (pos >= frame.size || (frame[pos].toInt() and 0xFF) != CONTROL_UI) return null
        pos++
        // PID is required on UI frames; be lenient if a frame ends right after control.
        if (pos < frame.size) {
            if ((frame[pos].toInt() and 0xFF) != PID_NO_L3) return null
            pos++
        }
        return Ax25Packet(
            destination = addresses[0].copy(hBit = false),
            source = addresses[1].copy(hBit = false),
            path = addresses.drop(2),
            info = frame.copyOfRange(pos, frame.size),
        )
    }

    private fun decodeAddress(frame: ByteArray, offset: Int): Ax25Address? {
        val sb = StringBuilder(6)
        for (i in 0 until 6) {
            val c = (frame[offset + i].toInt() and 0xFF) ushr 1
            if (c == ' '.code) continue
            if (!(c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c in 'a'.code..'z'.code)) return null
            sb.append(c.toChar())
        }
        if (sb.isEmpty()) return null
        val ssidByte = frame[offset + 6].toInt() and 0xFF
        return Ax25Address(sb.toString(), (ssidByte ushr 1) and 0x0F, (ssidByte and H_BIT) != 0)
    }

    /**
     * Encodes a UI frame. Mirrors the firmware's encodeKISS (kiss_utils.cpp): SSID byte
     * `0b011SSSS0`, H bit on repeated path entries, extension bit on the last address.
     */
    fun encode(destination: Ax25Address, source: Ax25Address, path: List<Ax25Address>, info: ByteArray): ByteArray {
        val out = ByteBuilder()
        val all = listOf(destination, source) + path
        all.forEachIndexed { i, a ->
            val call = a.callsign.uppercase().padEnd(6, ' ').take(6)
            call.forEach { out.write(it.code shl 1) }
            var ssid = 0x60 or ((a.ssid and 0x0F) shl 1)
            if (i >= 2 && a.hBit) ssid = ssid or H_BIT
            if (i == all.lastIndex) ssid = ssid or LAST_ADDR_BIT
            out.write(ssid)
        }
        out.write(CONTROL_UI)
        out.write(PID_NO_L3)
        out.write(info)
        return out.toByteArray()
    }
}
