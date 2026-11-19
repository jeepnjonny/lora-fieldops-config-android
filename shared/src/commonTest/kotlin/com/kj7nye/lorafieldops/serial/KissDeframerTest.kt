package com.kj7nye.lorafieldops.serial

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class KissDeframerTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun singleFrame() {
        val frames = KissDeframer().feed(bytes(0xC0, 0x00, 0x41, 0x42, 0xC0))
        assertEquals(1, frames.size)
        assertTrue(frames[0].isData)
        assertContentEquals(bytes(0x41, 0x42), frames[0].payload)
    }

    @Test
    fun escapedBytesAreRestored() {
        // FESC TFEND -> FEND, FESC TFESC -> FESC
        val frames = KissDeframer().feed(bytes(0xC0, 0x00, 0x01, 0xDB, 0xDC, 0x02, 0xDB, 0xDD, 0x03, 0xC0))
        assertContentEquals(bytes(0x01, 0xC0, 0x02, 0xDB, 0x03), frames.single().payload)
    }

    @Test
    fun encodeRoundTripsEveryByteValue() {
        val payload = ByteArray(256) { it.toByte() }
        val frames = KissDeframer().feed(kissEncode(payload))
        assertContentEquals(payload, frames.single().payload)
    }

    @Test
    fun frameSplitAcrossNotifications() {
        val d = KissDeframer()
        assertTrue(d.feed(bytes(0xC0, 0x00, 0x41)).isEmpty())
        assertTrue(d.feed(bytes(0x42)).isEmpty())
        val frames = d.feed(bytes(0x43, 0xC0))
        assertContentEquals(bytes(0x41, 0x42, 0x43), frames.single().payload)
    }

    @Test
    fun escapeSplitBetweenNotifications() {
        val d = KissDeframer()
        assertTrue(d.feed(bytes(0xC0, 0x00, 0x41, 0xDB)).isEmpty())
        val frames = d.feed(bytes(0xDC, 0x42, 0xC0))
        assertContentEquals(bytes(0x41, 0xC0, 0x42), frames.single().payload)
    }

    @Test
    fun severalFramesInOneNotificationAndSharedFend() {
        // Two frames sharing the FEND between them, then the start of a third.
        val d = KissDeframer()
        val frames = d.feed(bytes(0xC0, 0x00, 0x01, 0xC0, 0x06, 0x52, 0xC0, 0xC0, 0x00, 0x09))
        assertEquals(2, frames.size)
        assertEquals(KISS_CMD_DATA, frames[0].command)
        assertEquals(KISS_CMD_SET_HARDWARE, frames[1].command)
        assertContentEquals(bytes(0x52), frames[1].payload)
        assertContentEquals(bytes(0x09), d.feed(bytes(0xC0)).single().payload)
    }

    @Test
    fun bytesBeforeFirstFendAreDropped() {
        val frames = KissDeframer().feed(bytes(0x41, 0x42, 0xC0, 0x00, 0x43, 0xC0))
        assertContentEquals(bytes(0x43), frames.single().payload)
    }

    @Test
    fun portIsHighNibble() {
        val f = KissDeframer().feed(bytes(0xC0, 0x16, 0x41, 0xC0)).single()
        assertEquals(1, f.port)
        assertEquals(KISS_CMD_SET_HARDWARE, f.command)
    }

    @Test
    fun oversizedFrameIsDroppedAndStreamRecovers() {
        val d = KissDeframer()
        val big = ByteArray(5000) { 0x41 }
        assertTrue(d.feed(bytes(0xC0, 0x00) + big + bytes(0xC0)).isEmpty())
        assertContentEquals(bytes(0x42), d.feed(bytes(0x00, 0x42, 0xC0)).single().payload)
    }
}
