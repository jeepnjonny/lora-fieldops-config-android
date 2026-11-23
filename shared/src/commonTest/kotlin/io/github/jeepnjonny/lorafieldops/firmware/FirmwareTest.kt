package io.github.jeepnjonny.lorafieldops.firmware

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Sha256Test {
    private fun hash(s: String) = Sha256.hex(Sha256.digest(s.encodeToByteArray()))

    @Test
    fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun paddingBoundaries() {
        // 55, 56 and 64 bytes straddle the one-vs-two padding block boundary.
        assertEquals("9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318", hash("a".repeat(55)))
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", hash("a".repeat(56)))
        assertEquals("ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb", hash("a".repeat(64)))
    }
}

class OtaCodecTest {
    @Test
    fun startLayout() {
        val sha = ByteArray(32) { it.toByte() }
        val start = OtaCodec.start(0x0102_0304, sha, "heltec_v3_433_aprs", "v1.5.0")
        assertEquals(0x01, start[0].toInt())
        assertEquals(1, start[1].toInt())
        assertContentEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), start.copyOfRange(2, 6))
        assertContentEquals(sha, start.copyOfRange(6, 38))
        assertEquals(18, start[38].toInt())
        assertEquals("heltec_v3_433_aprs", start.copyOfRange(39, 57).decodeToString())
        assertEquals(6, start[57].toInt())
        assertEquals("v1.5.0", start.copyOfRange(58, 64).decodeToString())
        assertEquals(64, start.size)
    }

    @Test
    fun dataPacket() {
        val image = ByteArray(600) { it.toByte() }
        val packet = OtaCodec.data(300, image, 3)
        assertContentEquals(byteArrayOf(0x2C, 0x01, 0, 0, 0x2C, 0x2D, 0x2E), packet)
    }

    @Test
    fun parsesNotifications() {
        assertEquals(OtaNotification.Ready(1, 12288, 4096),
            OtaCodec.parse(byteArrayOf(0x81.toByte(), 1, 0x00, 0x30, 0, 0, 0x00, 0x10, 0, 0)))
        assertEquals(OtaNotification.Ack(70000), OtaCodec.parse(byteArrayOf(0x82.toByte(), 0x70, 0x11, 0x01, 0)))
        assertEquals(OtaNotification.Resync(244), OtaCodec.parse(byteArrayOf(0x83.toByte(), 244.toByte(), 0, 0, 0)))
        assertEquals(OtaNotification.Done, OtaCodec.parse(byteArrayOf(0x84.toByte())))
        assertEquals(OtaNotification.Error(3, "wrong board"),
            OtaCodec.parse(byteArrayOf(0x85.toByte(), 3) + "wrong board".encodeToByteArray()))
        assertEquals(OtaNotification.Aborted, OtaCodec.parse(byteArrayOf(0x86.toByte())))
        assertNull(OtaCodec.parse(byteArrayOf(0x82.toByte(), 1)))  // truncated
        assertNull(OtaCodec.parse(byteArrayOf(0x42)))
    }
}

class BoardSupportTest {
    @Test
    fun parsesTwoLineVersion() {
        val info = TrackerVersionInfo.parse("version.date=v1.4.7-7-g4d54093\nversion.board=heltec_v3_433_aprs\n")
        assertEquals(TrackerVersionInfo("v1.4.7-7-g4d54093", "heltec_v3_433_aprs"), info)
    }

    @Test
    fun parsesOldSingleLineVersion() {
        assertEquals(TrackerVersionInfo("v1.3.2", null), TrackerVersionInfo.parse("version.date=v1.3.2\r\n"))
        assertEquals(TrackerVersionInfo("v1.0.0", null), TrackerVersionInfo.parse("v1.0.0"))
    }

    @Test
    fun boardRules() {
        assertIs<BoardUpdateSupport.NotSupported>(BoardUpdateSupport.forBoard(null))
        assertIs<BoardUpdateSupport.NotSupported>(BoardUpdateSupport.forBoard("unknown"))
        assertIs<BoardUpdateSupport.NotSupported>(BoardUpdateSupport.forBoard("tbeam_433_aprs"))
        assertIs<BoardUpdateSupport.NotSupported>(BoardUpdateSupport.forBoard("lilygo_t3_433_aprs"))
        assertEquals(
            BoardUpdateSupport.Supported("heltec_t114", UpdateMethod.NORDIC_DFU, "heltec_t114_dfu.zip"),
            BoardUpdateSupport.forBoard("heltec_t114"),
        )
        assertEquals(
            BoardUpdateSupport.Supported("LoRanger_V2", UpdateMethod.ESP32_OTA, "LoRanger_V2_firmware.bin"),
            BoardUpdateSupport.forBoard("LoRanger_V2"),
        )
    }

    @Test
    fun fileChecks() {
        val v3 = BoardUpdateSupport.forBoard("heltec_v3_433_aprs") as BoardUpdateSupport.Supported
        val esp = byteArrayOf(0xE9.toByte(), 1, 2)
        assertNull(checkFirmwareFile(v3, "heltec_v3_433_aprs_firmware.bin", esp))
        assertNotNull(checkFirmwareFile(v3, "heltec_v3_433_aprs_web_factory.bin", esp))
        assertNotNull(checkFirmwareFile(v3, "heltec_v3_433_aprs_ota_update.bin", esp))
        assertNotNull(checkFirmwareFile(v3, "LoRanger_V1_firmware.bin", esp))
        assertNotNull(checkFirmwareFile(v3, "heltec_v3_433_aprs_firmware.bin", byteArrayOf(0, 1)))

        val t114 = BoardUpdateSupport.forBoard("heltec_t114") as BoardUpdateSupport.Supported
        val zip = byteArrayOf(0x50, 0x4B, 3, 4, 9)
        assertNull(checkFirmwareFile(t114, "heltec_t114_dfu.zip", zip))
        assertNotNull(checkFirmwareFile(t114, "heltec_t114_firmware.bin", zip))
        assertNotNull(checkFirmwareFile(t114, "heltec_t114_dfu.zip", esp))
    }
}

class ReleaseSourceTest {
    @Test
    fun parsesGitHubRelease() {
        val body = """
            {"tag_name":"v1.5.0","html_url":"https://github.com/x/y/releases/tag/v1.5.0","draft":false,
             "assets":[
               {"name":"heltec_v3_433_aprs_firmware.bin","size":1600000,"browser_download_url":"https://example/a.bin","label":null},
               {"name":"heltec_t114_dfu.zip","size":312000,"browser_download_url":"https://example/t.zip"}
             ]}
        """.trimIndent()
        val r = parseGitHubRelease(body)
        assertEquals("v1.5.0", r.tag)
        assertEquals(io.github.jeepnjonny.lorafieldops.model.FirmwareVersion(1, 5, 0), r.version)
        assertEquals("https://example/t.zip", r.asset("heltec_t114_dfu.zip")?.url)
        assertNull(r.asset("LoRanger_V1_firmware.bin"))
    }
}
