package com.kj7nye.lorafieldops.logger

import com.kj7nye.lorafieldops.model.CoverageEntryType
import com.kj7nye.lorafieldops.serial.Ax25
import com.kj7nye.lorafieldops.serial.Ax25Address
import com.kj7nye.lorafieldops.serial.KISS_CMD_SET_HARDWARE
import com.kj7nye.lorafieldops.serial.kissEncode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketRecorderTest {

    private fun frame(src: String, ssid: Int, path: List<Ax25Address> = emptyList(), info: String = "!pos") =
        kissEncode(Ax25.encode(Ax25Address("APLRT1", 0), Ax25Address(src, ssid), path, info.toByteArray(Charsets.ISO_8859_1)))

    private fun report(text: String) = kissEncode(text.toByteArray(Charsets.ISO_8859_1), command = KISS_CMD_SET_HARDWARE)

    // ---- classification ---------------------------------------------------

    @Test
    fun ownPacketWithoutUsedHopsIsTx() {
        val r = PacketRecorder("K7ABC-9")
        r.onBytes(frame("K7ABC", 9, listOf(Ax25Address("WIDE1", 1))), 0)
        val e = r.flush(0, force = true).single()
        assertEquals(CoverageEntryType.TX, e.type)
        assertEquals("K7ABC-9>APLRT1,WIDE1-1:!pos", e.packet)
    }

    @Test
    fun ownPacketRepeatedByDigiIsRx() {
        val r = PacketRecorder("K7ABC-9")
        r.onBytes(frame("K7ABC", 9, listOf(Ax25Address("WIDE1", 1, hBit = true))), 0)
        assertEquals(CoverageEntryType.RX, r.flush(0, force = true).single().type)
    }

    @Test
    fun otherStationIsRx() {
        val r = PacketRecorder("K7ABC-9")
        r.onBytes(frame("K7ABC", 7), 0)
        assertEquals(CoverageEntryType.RX, r.flush(0, force = true).single().type)
    }

    @Test
    fun ssidZeroMatchesEitherWay() {
        val r = PacketRecorder("k7abc-0")
        r.onBytes(frame("K7ABC", 0), 0)
        assertEquals(CoverageEntryType.TX, r.flush(0, force = true).single().type)
    }

    @Test
    fun objectTrackerUsesSenderAsSource() {
        val r = PacketRecorder("K7ABC-9:Balloon1")
        r.onBytes(frame("K7ABC", 9, info = ";Balloon1 *111111z4336.66N/11612.40WO"), 0)
        assertEquals(CoverageEntryType.TX, r.flush(0, force = true).single().type)
    }

    @Test
    fun timestampIsArrivalSeconds() {
        val r = PacketRecorder("K7ABC-9")
        r.onBytes(frame("N0CALL", 1), 1_763_251_200_999)
        assertEquals(1_763_251_200L, r.flush(1_763_251_200_999, force = true).single().ts)
    }

    // ---- SetHardware RSSI/SNR ---------------------------------------------

    @Test
    fun reportAttachesToPreviousRxFrame() {
        val r = PacketRecorder("K7ABC-9")
        assertTrue(r.onBytes(frame("N0CALL", 1), 1_000).isEmpty())   // held for a report
        val e = r.onBytes(report("RSSI:-97,SNR:6.5"), 1_400).single()
        assertEquals(-97, e.rssi)
        assertEquals(6.5, e.snr!!, 0.0)
    }

    @Test
    fun reportInSameNotificationAsFrame() {
        val r = PacketRecorder("K7ABC-9")
        val e = r.onBytes(frame("N0CALL", 1) + report("RSSI:-110,SNR:-7.25"), 0).single()
        assertEquals(-110, e.rssi)
        assertEquals(-7.25, e.snr!!, 0.0)
    }

    @Test
    fun lateReportIsIgnoredAndFrameReleasedWithoutIt() {
        val r = PacketRecorder("K7ABC-9")
        r.onBytes(frame("N0CALL", 1), 1_000)
        val released = r.flush(2_001)
        assertNull(released.single().rssi)
        assertTrue(r.onBytes(report("RSSI:-97,SNR:6.5"), 2_100).isEmpty())
    }

    @Test
    fun worksWithoutReports() {
        val r = PacketRecorder("K7ABC-9")
        r.onBytes(frame("N0CALL", 1), 0)
        val first = r.onBytes(frame("N0CALL", 2), 100)   // next frame releases the previous one
        assertEquals("N0CALL-1>APLRT1:!pos", first.single().packet)
        assertNull(first.single().rssi)
    }

    @Test
    fun reportNeverAttachesToTx() {
        val r = PacketRecorder("K7ABC-9")
        r.onBytes(frame("K7ABC", 9), 0)
        r.onBytes(report("RSSI:-97,SNR:6.5"), 10)
        val e = r.flush(10, force = true).single()
        assertEquals(CoverageEntryType.TX, e.type)
        assertNull(e.rssi)
    }

    @Test
    fun otherKissCommandsIgnored() {
        val r = PacketRecorder("K7ABC-9")
        assertTrue(r.onBytes(kissEncode(byteArrayOf(50), command = 0x01), 0).isEmpty())
        assertTrue(r.flush(5_000, force = true).isEmpty())
    }

    @Test
    fun splitAcrossNotificationsWithReport() {
        val r = PacketRecorder("K7ABC-9")
        val all = frame("N0CALL", 1) + report("RSSI:-90,SNR:10")
        val out = mutableListOf<com.kj7nye.lorafieldops.model.CoverageEntry>()
        all.toList().chunked(7).forEachIndexed { i, c -> out += r.onBytes(c.toByteArray(), i * 10L) }
        assertEquals(-90, out.single().rssi)
    }

    @Test
    fun parseSignalReportVariants() {
        assertEquals(SignalReport(-97, 6.5), parseSignalReport("RSSI:-97,SNR:6.5"))
        assertEquals(SignalReport(-97, null), parseSignalReport("RSSI:-97"))
        assertEquals(SignalReport(null, null), parseSignalReport("garbage"))
    }

    // ---- filters ----------------------------------------------------------

    @Test
    fun filtersDropUnwantedTypes() {
        val r = PacketRecorder("K7ABC-9", includeTx = false, includeRx = true)
        r.onBytes(frame("K7ABC", 9), 0)
        r.onBytes(frame("N0CALL", 1), 10)
        val out = r.flush(10, force = true)
        assertEquals(CoverageEntryType.RX, out.single().type)
    }
}
