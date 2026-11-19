package com.kj7nye.lorafieldops.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusParsingTest {

    @Test
    fun keyValueTokens() {
        assertEquals(
            mapOf("gps.lat" to "43.6111", "gps.lon" to "-116.2067", "gps.sats" to "9", "gps.valid" to "1"),
            parseStatusTokens("gps.lat=43.6111 gps.lon=-116.2067 gps.sats=9 gps.valid=1"),
        )
    }

    @Test
    fun restOfLineKeyKeepsSpaces() {
        assertEquals(
            mapOf("wifiSTA.connected" to "true", "ip" to "192.168.1.20", "ssid" to "My Home Wi-Fi"),
            parseStatusTokens("wifiSTA.connected=true ip=192.168.1.20 ssid=My Home Wi-Fi", restOfLineKey = "ssid"),
        )
    }

    @Test
    fun ignoresTokensWithoutValue() {
        assertEquals(mapOf("bat.voltage" to "4.10"), parseStatusTokens("ok bat.voltage=4.10 =x"))
    }

    @Test
    fun wifiScanLines() {
        val text = """
            wifista scan
            wifiSTA.scan: rssi=-48 secure=1 ssid=Home Net
            wifiSTA.scan: rssi=-80 secure=0 ssid=Cafe
            something else
        """.trimIndent()
        assertEquals(
            listOf(WifiScanResult("Home Net", -48, true), WifiScanResult("Cafe", -80, false)),
            parseWifiScan(text),
        )
    }

    @Test
    fun aprsIsServerText() {
        val all = mapOf("aprsIS.host" to "rotate.aprs.net", "aprsIS.port" to "14580", "aprsIS.server" to "T2USASW")
        assertEquals("T2USASW (rotate.aprs.net:14580)", aprsIsServerText(all))
        assertEquals("rotate.aprs.net", aprsIsServerText(mapOf("aprsIS.host" to "rotate.aprs.net")))
        assertNull(aprsIsServerText(emptyMap()))
    }

    @Test
    fun aprsIsLoginText() {
        assertEquals("Passcode rejected (receive-only)", aprsIsLoginText("unverified"))
        assertNull(aprsIsLoginText(null))
    }
}
