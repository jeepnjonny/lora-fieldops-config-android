package io.github.jeepnjonny.lorafieldops.firmware

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Simulates the tracker side of `ble_ota.h`: answers START with READY, writes
 * in-order packets, ACKs every [ackInterval] bytes and at the end, and answers
 * out-of-order packets with RESYNC.
 */
private class FakeTracker(
    val window: Int = 12288,
    val ackInterval: Int = 4096,
    override val maxWritePayload: Int = 244,
    val dropPacketAt: Int? = null,
    val failStartWith: Int? = null,
) : OtaLink {
    private val notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096)
    override val otaNotifications: SharedFlow<ByteArray> = notifications

    var subscribed = false
    var expectedSize = 0
    val received = mutableListOf<Byte>()
    var lastAck = 0
    var maxInFlight = 0
    var dropped = false
    var aborted = false
    var awaitingResync = false

    override suspend fun enableOtaNotifications() { subscribed = true }

    override suspend fun writeOtaControl(bytes: ByteArray) {
        check(subscribed) { "START before subscribing" }
        when (bytes[0].toInt()) {
            0x01 -> {
                expectedSize = OtaCodec.readU32(bytes, 2)
                if (failStartWith != null) {
                    notify(byteArrayOf(0x85.toByte(), failStartWith.toByte()) + "nope".encodeToByteArray())
                } else {
                    notify(byteArrayOf(0x81.toByte(), 1) + OtaCodec.u32(window) + OtaCodec.u32(ackInterval))
                }
            }
            0x02 -> notify(byteArrayOf(if (received.size == expectedSize) 0x84.toByte() else 0x85.toByte()))
            0x03 -> { aborted = true; notify(byteArrayOf(0x86.toByte())) }
        }
    }

    override suspend fun writeOtaData(bytes: ByteArray) {
        val offset = OtaCodec.readU32(bytes, 0)
        val payload = bytes.copyOfRange(4, bytes.size)
        check(payload.size <= maxWritePayload - 4) { "packet too large" }
        if (!dropped && dropPacketAt != null && offset >= dropPacketAt) {
            dropped = true  // lost in the air
            return
        }
        if (offset != received.size) {
            if (!awaitingResync) { awaitingResync = true; notify(byteArrayOf(0x83.toByte()) + OtaCodec.u32(received.size)) }
            return
        }
        awaitingResync = false
        received.addAll(payload.toList())
        maxInFlight = maxOf(maxInFlight, received.size - lastAck)
        check(received.size - lastAck <= window) { "window exceeded" }
        if (received.size - lastAck >= ackInterval || received.size == expectedSize) {
            lastAck = received.size
            notify(byteArrayOf(0x82.toByte()) + OtaCodec.u32(lastAck))
        }
        yield()
    }

    private fun notify(bytes: ByteArray) { check(notifications.tryEmit(bytes)) }
}

class OtaUploaderTest {
    private val image = ByteArray(50_000) { (it * 31 + 7).toByte() }
    private val sha = Sha256.digest(image)

    @Test
    fun uploadsWholeImageWithinWindow() = runBlocking {
        val tracker = FakeTracker()
        val progress = mutableListOf<Int>()
        OtaUploader(tracker, image, sha, "heltec_v3_433_aprs", "v1.5.0") { acked, _ -> progress += acked }.run()
        assertContentEquals(image, tracker.received.toByteArray())
        assertTrue(tracker.maxInFlight <= tracker.window)
        assertEquals(image.size, progress.last())
    }

    @Test
    fun resendsFromResyncOffset() = runBlocking {
        val tracker = FakeTracker(dropPacketAt = 10_000)
        OtaUploader(tracker, image, sha, "b", "v").run()
        assertTrue(tracker.dropped)
        assertContentEquals(image, tracker.received.toByteArray())
    }

    @Test
    fun smallMtuStillWorks() = runBlocking {
        val tracker = FakeTracker(maxWritePayload = 20)
        OtaUploader(tracker, image.copyOf(3000), Sha256.digest(image.copyOf(3000)), "b", "v").run()
        assertEquals(3000, tracker.received.size)
    }

    @Test
    fun trackerErrorIsReported() = runBlocking {
        val e = assertFailsWith<OtaException> {
            OtaUploader(FakeTracker(failStartWith = OtaError.WRONG_BOARD), image, sha, "b", "v").run()
        }
        assertEquals(OtaError.WRONG_BOARD, e.code)
        assertTrue(e.message!!.contains("different board"))
    }

    @Test
    fun cancelSendsAbort() = runBlocking {
        val tracker = FakeTracker()
        val job = async {
            OtaUploader(tracker, image, sha, "b", "v") { acked, _ -> if (acked >= 8192) throw kotlinx.coroutines.CancellationException("user") }.run()
        }
        runCatching { job.await() }
        assertTrue(tracker.aborted)
    }
}
