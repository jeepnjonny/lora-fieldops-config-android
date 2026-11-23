package io.github.jeepnjonny.lorafieldops.model

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class SettingNotesTest {

    @Test
    fun describesChangedSettingWithUnit() {
        val before = TrackerConfig()
        val after = before.copy(other = before.other.copy(nonSmartBeaconRateSec = 60))
        assertEquals(listOf("beacon rate 900 → 60 s"), settingChangeNotes(before, after))
    }

    @Test
    fun kissBlockOptionalInFirmwareJson() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        assertEquals(null, json.decodeFromString(TrackerConfig.serializer(), "{}").kiss)
        assertEquals(true, json.decodeFromString(TrackerConfig.serializer(), """{"kiss":{"rssi":true}}""").kiss?.rssi)
        val before = TrackerConfig(kiss = KissConfig(rssi = false))
        assertEquals(listOf("RSSI/SNR reports false → true"), settingChangeNotes(before, before.copy(kiss = KissConfig(rssi = true))))
    }

    @Test
    fun noChangesNoNotes() {
        assertTrue(settingChangeNotes(TrackerConfig(), TrackerConfig()).isEmpty())
    }

    @Test
    fun secretsNeverAppear() {
        val before = TrackerConfig()
        val after = before.copy(
            wifiAP = WifiAPConfig(password = "hunter2hunter2"),
            wifiSTA = WifiSTAConfig(networks = listOf(WifiNetworkConfig(ssid = "Home", password = "wifikey123"))),
            bluetooth = before.bluetooth.copy(pin = "654321"),
            aprsIS = before.aprsIS.copy(passcode = "12345"),
            remoteCfg = before.remoteCfg.copy(token = "unlocktoken"),
        )
        val notes = settingChangeNotes(before, after).joinToString("\n")
        listOf("hunter2hunter2", "wifikey123", "654321", "12345", "unlocktoken").forEach {
            assertTrue(!notes.contains(it), "leaked $it in: $notes")
        }
        assertTrue(!notes.contains("Home"), "SSID left out: $notes")
    }

    @Test
    fun privateSettingsNeverAppear() {
        val before = TrackerConfig()
        val after = before.copy(
            fixedPosition = FixedPositionConfig(latitude = 43.6111f, longitude = -116.2067f, elevation = 824f),
            aprsIS = before.aprsIS.copy(server = "my.private.host", filter = "r/43.61/-116.20/50"),
            bluetooth = before.bluetooth.copy(deviceName = "Jane's tracker"),
            other = before.other.copy(nonSmartBeaconRateSec = 60),
        )
        val notes = settingChangeNotes(before, after)
        assertEquals(listOf("beacon rate 900 → 60 s"), notes)
    }
}
