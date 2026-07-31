package com.kj7nye.lorafieldops.model

/**
 * Parses the firmware's `version` response — a `git describe --tags --always
 * --dirty` string such as "v1.2.6", "v1.2.6-5-gabc1234", or
 * "v1.2.6-5-gabc1234-dirty" — into a comparable (major, minor, patch) triple.
 *
 * Firmware built without reachable git tags reports "unknown", which fails to
 * parse; callers should treat a null result as "can't confirm the version"
 * rather than as a specific old or new version.
 */
data class FirmwareVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<FirmwareVersion> {
    override fun compareTo(other: FirmwareVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val TAG_PREFIX = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

        fun parse(raw: String): FirmwareVersion? {
            val m = TAG_PREFIX.find(raw.trim()) ?: return null
            val (maj, min, pat) = m.destructured
            return FirmwareVersion(maj.toInt(), min.toInt(), pat.toInt())
        }

        // Feature floors, verified against tagged firmware source at
        // github.com/KJ7NYE/LoRa_FieldOps_APRS_Tracker (src/serial_setup.cpp).
        /** PHG beacon, OTA-DFU, and on-demand tx comment/status commands. */
        val MIN_PHG = FirmwareVersion(1, 1, 0)
        /** `aprsiss downlink` toggle and `wifista scan|status`. */
        val MIN_APRSIS_DOWNLINK = FirmwareVersion(1, 1, 8)
        /**
         * Indexed multi-network WiFi STA (`wifista add|remove|ssid <i>|password
         * <i>`). Firmware older than this parses `wifista ssid <i> <ssid>` as a
         * flat single-network command and silently stores the index digit as
         * part of the SSID (e.g. "0 MyNetwork") instead of rejecting it — so
         * this floor must be enforced by hiding the UI, not by surfacing an
         * error after the fact.
         */
        val MIN_WIFI_MULTI_NETWORK = FirmwareVersion(1, 2, 0)
    }
}
