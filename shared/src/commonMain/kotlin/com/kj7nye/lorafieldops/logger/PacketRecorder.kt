package com.kj7nye.lorafieldops.logger

import com.kj7nye.lorafieldops.model.CoverageEntry
import com.kj7nye.lorafieldops.model.CoverageEntryType
import com.kj7nye.lorafieldops.serial.Ax25
import com.kj7nye.lorafieldops.serial.Ax25Packet
import com.kj7nye.lorafieldops.serial.KISS_CMD_SET_HARDWARE
import com.kj7nye.lorafieldops.serial.KissDeframer
import com.kj7nye.lorafieldops.platform.decodeLatin1
import kotlin.math.roundToInt

/** A SetHardware report only belongs to the data frame before it if it follows within this window. */
const val SIGNAL_REPORT_WINDOW_MS = 1_000L

/**
 * Turns raw KISS-link bytes into [CoverageEntry]s.
 *
 * Each decoded data frame is held briefly, because newer firmware follows a received
 * frame with a KISS SetHardware frame (`RSSI:-97,SNR:6.5`) carrying its signal report.
 * The held frame is released when that report arrives, when the next data frame
 * arrives, or once [SIGNAL_REPORT_WINDOW_MS] has passed (see [flush]). Firmware that
 * never sends reports just gets entries without rssi/snr, [SIGNAL_REPORT_WINDOW_MS] later.
 *
 * Pure logic with an injected clock (milliseconds), so it is unit tested directly.
 * Create a new instance for each BLE connection.
 */
class PacketRecorder(
    /** Device as registered on the site: "K7ABC-9", or "SENDER:OBJECT" for object trackers. */
    device: String,
    private val includeTx: Boolean = true,
    private val includeRx: Boolean = true,
) {
    private val deviceSource = normalizeCall(deviceSourceCall(device))
    private val deframer = KissDeframer()
    private var pending: CoverageEntry? = null
    private var pendingAtMs = 0L

    /** Feeds one BLE notification received at [nowMs]; returns entries now ready to queue. */
    fun onBytes(bytes: ByteArray, nowMs: Long): List<CoverageEntry> {
        val out = mutableListOf<CoverageEntry>()
        for (frame in deframer.feed(bytes)) {
            when {
                frame.isData -> {
                    releasePending(out)
                    val packet = Ax25.decode(frame.payload) ?: continue
                    pending = CoverageEntry(classify(packet), nowMs / 1000, packet = packet.toTnc2())
                    pendingAtMs = nowMs
                }
                frame.command == KISS_CMD_SET_HARDWARE -> {
                    val held = pending
                    if (held != null && held.type == CoverageEntryType.RX &&
                        nowMs - pendingAtMs <= SIGNAL_REPORT_WINDOW_MS
                    ) {
                        val report = parseSignalReport(frame.payload.decodeLatin1())
                        pending = held.copy(rssi = report.rssi, snr = report.snr)
                        releasePending(out)
                    }
                }
                else -> Unit   // other KISS commands (TXDELAY, persistence, ...) are not ours
            }
        }
        flushInto(out, nowMs, force = false)
        return out
    }

    /** Releases a held frame whose report window has closed ([force]: release regardless). */
    fun flush(nowMs: Long, force: Boolean = false): List<CoverageEntry> {
        val out = mutableListOf<CoverageEntry>()
        flushInto(out, nowMs, force)
        return out
    }

    private fun flushInto(out: MutableList<CoverageEntry>, nowMs: Long, force: Boolean) {
        if (pending != null && (force || nowMs - pendingAtMs > SIGNAL_REPORT_WINDOW_MS)) releasePending(out)
    }

    private fun releasePending(out: MutableList<CoverageEntry>) {
        val p = pending ?: return
        pending = null
        val wanted = when (p.type) {
            CoverageEntryType.TX -> includeTx
            CoverageEntryType.RX -> includeRx
            CoverageEntryType.EVENT -> true
        }
        if (wanted) out += p
    }

    /**
     * tx: the device's own transmission, i.e. its callsign as source with no path entry
     * marked used. Everything else is rx, including the device's own packet coming back
     * through a digipeater (source is the device but an H bit is set).
     */
    fun classify(packet: Ax25Packet): CoverageEntryType =
        if (deviceSource.isNotEmpty() && normalizeCall(packet.source.text) == deviceSource && !packet.repeated)
            CoverageEntryType.TX
        else
            CoverageEntryType.RX
}

data class SignalReport(val rssi: Int?, val snr: Double?)

private val RSSI_RE = Regex("""RSSI\s*[:=]\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
private val SNR_RE  = Regex("""SNR\s*[:=]\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

/** Parses the firmware's SetHardware text, e.g. `RSSI:-97,SNR:6.5`. Missing values are null. */
fun parseSignalReport(text: String): SignalReport = SignalReport(
    rssi = RSSI_RE.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt(),
    snr = SNR_RE.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
)

/** "SENDER:OBJECT" → "SENDER"; a plain callsign is returned unchanged. */
fun deviceSourceCall(device: String): String = device.substringBefore(':').trim()

/** Upper-case, and "K7ABC-0" → "K7ABC" so SSID 0 compares equal either way. */
fun normalizeCall(call: String): String {
    val c = call.trim().uppercase().removeSuffix("*")
    return if (c.endsWith("-0")) c.dropLast(2) else c
}
