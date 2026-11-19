package com.kj7nye.lorafieldops.serial

import com.kj7nye.lorafieldops.platform.encodeLatin1
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class Ax25Test {

    private fun addr(call: String, ssid: Int = 0, h: Boolean = false) = Ax25Address(call, ssid, h)
    private fun info(s: String) = s.encodeLatin1()

    @Test
    fun noDigis() {
        val frame = Ax25.encode(addr("APLRT1"), addr("K7ABC", 9), emptyList(), info("!4336.66N/11612.40W>test"))
        val p = Ax25.decode(frame)!!
        assertEquals("K7ABC-9>APLRT1:!4336.66N/11612.40W>test", p.toTnc2())
        assertFalse(p.repeated)
    }

    @Test
    fun unusedPath() {
        val frame = Ax25.encode(addr("APLRT1"), addr("K7ABC", 9), listOf(addr("WIDE1", 1), addr("WIDE2", 1)), info(">hi"))
        assertEquals("K7ABC-9>APLRT1,WIDE1-1,WIDE2-1:>hi", Ax25.decode(frame)!!.toTnc2())
    }

    @Test
    fun usedHopsGetStar() {
        val path = listOf(addr("KF7XYZ", 10, h = true), addr("WIDE1", 0, h = true), addr("WIDE2", 1))
        val frame = Ax25.encode(addr("APLRT1"), addr("K7ABC", 9), path, info(":msg"))
        val p = Ax25.decode(frame)!!
        assertEquals("K7ABC-9>APLRT1,KF7XYZ-10*,WIDE1*,WIDE2-1::msg", p.toTnc2())
        assertTrue(p.repeated)
    }

    @Test
    fun ssidZeroAndFifteen() {
        val frame = Ax25.encode(addr("APRS", 0), addr("N0CALL", 15), listOf(addr("RELAY", 0)), info("x"))
        assertEquals("N0CALL-15>APRS,RELAY:x", Ax25.decode(frame)!!.toTnc2())
    }

    @Test
    fun matchesFirmwareByteLayout() {
        // Bytes as the firmware's encodeKISS builds them for "K7ABC-9>APLRT1,WIDE1-1*:>t"
        val expected = byteArrayOf(
            ('A'.code shl 1).toByte(), ('P'.code shl 1).toByte(), ('L'.code shl 1).toByte(),
            ('R'.code shl 1).toByte(), ('T'.code shl 1).toByte(), ('1'.code shl 1).toByte(), 0x60,
            ('K'.code shl 1).toByte(), ('7'.code shl 1).toByte(), ('A'.code shl 1).toByte(),
            ('B'.code shl 1).toByte(), ('C'.code shl 1).toByte(), (' '.code shl 1).toByte(), (0x60 or (9 shl 1)).toByte(),
            ('W'.code shl 1).toByte(), ('I'.code shl 1).toByte(), ('D'.code shl 1).toByte(),
            ('E'.code shl 1).toByte(), ('1'.code shl 1).toByte(), (' '.code shl 1).toByte(), (0x60 or 0x80 or (1 shl 1) or 1).toByte(),
            0x03, 0xF0.toByte(), '>'.code.toByte(), 't'.code.toByte(),
        )
        assertEquals("K7ABC-9>APLRT1,WIDE1-1*:>t", Ax25.decode(expected)!!.toTnc2())
    }

    @Test
    fun infoBytesKeptExactly() {
        val raw = ByteArray(256) { it.toByte() }
        val p = Ax25.decode(Ax25.encode(addr("APRS"), addr("K7ABC"), emptyList(), raw))!!
        val text = p.toTnc2().substringAfter(':')
        assertEquals(256, text.length)
        raw.forEachIndexed { i, b -> assertEquals(b.toInt() and 0xFF, text[i].code) }
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Ax25.decode(ByteArray(0)))
        assertNull(Ax25.decode(ByteArray(10)))
        // Valid addresses but a non-UI control field
        val frame = Ax25.encode(addr("APRS"), addr("K7ABC"), emptyList(), info("x"))
        frame[14] = 0x3F
        assertNull(Ax25.decode(frame))
    }
}
