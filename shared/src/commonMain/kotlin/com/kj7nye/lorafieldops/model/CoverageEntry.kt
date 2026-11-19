package com.kj7nye.lorafieldops.model

/** Entry types understood by the APRS Coverage site's /api/devicelog. */
enum class CoverageEntryType(val wire: String) {
    TX("tx"),       // the tracker's own transmission
    RX("rx"),       // a packet the tracker heard (including its own packet repeated by a digi)
    EVENT("event"); // a note, e.g. a setting the user changed

    companion object {
        fun fromWire(s: String): CoverageEntryType? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * One logged item, waiting to be uploaded. [ts] is unix seconds from the phone's clock
 * when the frame arrived (KISS frames carry no timestamp). [packet] is the TNC2 line for
 * tx/rx; [text] the note for events. [rssi]/[snr] are only present when the firmware
 * reported them for a received frame.
 */
data class CoverageEntry(
    val type: CoverageEntryType,
    val ts: Long,
    val packet: String? = null,
    val text: String? = null,
    val rssi: Int? = null,
    val snr: Double? = null,
)
