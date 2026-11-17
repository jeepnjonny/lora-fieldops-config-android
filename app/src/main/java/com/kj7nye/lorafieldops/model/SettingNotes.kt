package com.kj7nye.lorafieldops.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val noteJson = Json { encodeDefaults = true }

/**
 * Leaf names whose values must never leave the phone in a note: Wi-Fi and AP passwords,
 * the Bluetooth PIN, the APRS-IS passcode and the remote-config unlock token.
 */
private val SECRET_LEAVES = setOf("password", "pin", "passcode", "token")

/** Friendly label and unit for the settings people compare most often. Others use their JSON path. */
private val LABELS: Map<String, Pair<String, String>> = mapOf(
    "beacons.callsign" to ("callsign" to ""),
    "beacons.comment" to ("comment" to ""),
    "beacons.status" to ("status" to ""),
    "beacons.symbol" to ("symbol" to ""),
    "beacons.smartBeaconActive" to ("SmartBeacon" to ""),
    "beacons.smartBeaconSetting" to ("SmartBeacon profile" to ""),
    "lora.frequency" to ("LoRa frequency" to " Hz"),
    "lora.spreadingFactor" to ("LoRa SF" to ""),
    "lora.signalBandwidth" to ("LoRa bandwidth" to " Hz"),
    "lora.codingRate4" to ("LoRa CR 4/" to ""),
    "lora.power" to ("TX power" to " dBm"),
    "other.nonSmartBeaconRateSec" to ("beacon rate" to " s"),
    "other.beaconPath" to ("beacon path" to ""),
    "other.digiMode" to ("digipeater mode" to ""),
    "other.cadActive" to ("CAD" to ""),
    "customSmartBeacon.slowRate" to ("SmartBeacon slow rate" to " s"),
    "customSmartBeacon.slowSpeed" to ("SmartBeacon slow speed" to " km/h"),
    "customSmartBeacon.fastRate" to ("SmartBeacon fast rate" to " s"),
    "customSmartBeacon.fastSpeed" to ("SmartBeacon fast speed" to " km/h"),
    "customSmartBeacon.turnMinDeg" to ("SmartBeacon min turn" to "°"),
    "customSmartBeacon.turnSlope" to ("SmartBeacon turn slope" to ""),
    "customSmartBeacon.turnMinTime" to ("SmartBeacon min turn time" to " s"),
    "kiss.rssi" to ("RSSI/SNR reports" to ""),
    "deviceRole" to ("role" to ""),
    "gpsSource" to ("GPS source" to ""),
)

/**
 * Human-readable notes for every setting that differs between [before] and [after],
 * e.g. `beacon rate 120 → 60 s`. Secret values are never included: a changed secret
 * isn't mentioned at all.
 */
fun settingChangeNotes(before: TrackerConfig, after: TrackerConfig): List<String> {
    val old = flatten(noteJson.encodeToJsonElement(TrackerConfig.serializer(), before))
    val new = flatten(noteJson.encodeToJsonElement(TrackerConfig.serializer(), after))
    return (old.keys + new.keys).distinct().mapNotNull { path ->
        if (path.split('.').any { it.substringBefore('[').lowercase() in SECRET_LEAVES }) return@mapNotNull null
        val a = old[path]; val b = new[path]
        if (a == b) return@mapNotNull null
        // Single-element lists (beacons[0], lora[0]) read better without the index.
        val key = path.replace("[0]", "")
        val (label, unit) = LABELS[key] ?: (key to "")
        "$label ${a ?: "—"} → ${b ?: "—"}$unit"
    }
}

private fun flatten(e: JsonElement, prefix: String = "", out: MutableMap<String, String> = linkedMapOf()): Map<String, String> {
    when (e) {
        is JsonObject -> e.forEach { (k, v) -> flatten(v, if (prefix.isEmpty()) k else "$prefix.$k", out) }
        is JsonArray -> e.forEachIndexed { i, v -> flatten(v, "$prefix[$i]", out) }
        is JsonNull -> out[prefix] = "unset"
        is JsonPrimitive -> out[prefix] = e.content
    }
    return out
}
