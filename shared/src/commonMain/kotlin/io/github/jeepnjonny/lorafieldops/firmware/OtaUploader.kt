package io.github.jeepnjonny.lorafieldops.firmware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The tracker's update service on an open, PIN-bonded Bluetooth link. Each platform's
 * BLE transport implements this; [OtaUploader] holds the protocol logic.
 */
interface OtaLink {
    /** Raw notifications from the control characteristic (`62970002`). */
    val otaNotifications: Flow<ByteArray>
    /** Subscribes to the control characteristic's notifications. */
    suspend fun enableOtaNotifications()
    /** Write with response to the control characteristic. */
    suspend fun writeOtaControl(bytes: ByteArray)
    /**
     * Write without response to the data characteristic. Returns once the phone's
     * Bluetooth stack has accepted the packet, so a fast sender can't overrun it.
     */
    suspend fun writeOtaData(bytes: ByteArray)
    /** Largest ATT write payload on this link right now (negotiated MTU − 3). */
    val maxWritePayload: Int
    /** Asks for a short connection interval while [fast] (best effort). */
    fun setHighThroughput(fast: Boolean) {}
}

/** The update failed; [message] is ready to show. [code] is the tracker's error code, if it sent one. */
class OtaException(message: String, val code: Int? = null) : Exception(message)

/**
 * Streams one image to an ESP32 tracker (`ble_ota.h` flow, ported from
 * `tools/ble_ota_client.py`). [run] returns after DONE; the tracker then reboots and
 * the link drops. Cancelling [run] sends ABORT; the tracker keeps its old firmware
 * after any failure, so retrying is always safe.
 */
class OtaUploader(
    private val link: OtaLink,
    private val image: ByteArray,
    private val sha256: ByteArray,
    private val board: String,
    private val version: String,
    /** Bytes the tracker has committed to flash, from its ACKs. */
    private val onProgress: (acked: Int, total: Int) -> Unit = { _, _ -> },
) {
    suspend fun run() = coroutineScope {
        val events = Channel<OtaNotification>(Channel.UNLIMITED)
        // Collect before subscribing so READY can't slip past.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            link.otaNotifications.collect { raw -> OtaCodec.parse(raw)?.let { events.trySend(it) } }
        }
        link.setHighThroughput(true)
        try {
            transfer(events)
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) abortQuietly(events)
            throw e
        } finally {
            link.setHighThroughput(false)
            collector.cancel()
        }
    }

    private suspend fun transfer(events: Channel<OtaNotification>) {
        val total = image.size
        link.enableOtaNotifications()
        link.writeOtaControl(OtaCodec.start(total, sha256, board, version))
        val ready = when (val first = await(events, READY_TIMEOUT_MS, "start the update")) {
            is OtaNotification.Ready -> first
            else -> throw unexpected(first)
        }
        if (ready.protocol != OtaCodec.PROTOCOL_VERSION) {
            throw OtaException(OtaError.describe(OtaError.PROTOCOL_VERSION, ""), OtaError.PROTOCOL_VERSION)
        }

        val payload = (link.maxWritePayload - OtaCodec.DATA_HEADER_BYTES).coerceAtLeast(1)
        var offset = 0
        var acked = 0
        onProgress(0, total)

        fun handle(event: OtaNotification) {
            when (event) {
                is OtaNotification.Ack -> if (event.bytesWritten > acked) {
                    acked = event.bytesWritten
                    onProgress(acked, total)
                }
                is OtaNotification.Resync -> offset = event.expectedOffset
                else -> throw unexpected(event)
            }
        }

        while (acked < total) {
            while (true) handle(events.tryReceive().getOrNull() ?: break)
            if (acked >= total) break
            val length = minOf(payload, total - offset)
            // Credit-based flow control: never more than `window` bytes sent but not ACKed.
            if (offset < total && offset + length - acked <= ready.window) {
                link.writeOtaData(OtaCodec.data(offset, image, length))
                offset += length
            } else {
                handle(await(events, ACK_TIMEOUT_MS, "acknowledge data"))
            }
        }

        link.writeOtaControl(OtaCodec.end())
        while (true) {
            when (val event = await(events, VERIFY_TIMEOUT_MS, "verify the firmware")) {
                OtaNotification.Done -> return
                is OtaNotification.Ack, is OtaNotification.Resync -> Unit  // late duplicates
                else -> throw unexpected(event)
            }
        }
    }

    private suspend fun await(events: Channel<OtaNotification>, timeoutMs: Long, what: String): OtaNotification =
        try {
            withTimeout(timeoutMs) { events.receive() }
        } catch (e: TimeoutCancellationException) {
            throw OtaException("The tracker didn't $what in time. Try again.")
        }

    private fun unexpected(event: OtaNotification): OtaException = when (event) {
        is OtaNotification.Error -> OtaException(OtaError.describe(event.code, event.text), event.code)
        OtaNotification.Aborted -> OtaException("The tracker cancelled the update.")
        else -> OtaException("The tracker sent an unexpected reply ($event).")
    }

    /** Best effort: tell the tracker to stop now instead of waiting out its 10 s timeout. */
    private suspend fun abortQuietly(events: Channel<OtaNotification>) = withContext(NonCancellable) {
        runCatching {
            withTimeout(ABORT_TIMEOUT_MS) {
                link.writeOtaControl(OtaCodec.abort())
                while (true) {
                    val e = events.receive()
                    if (e == OtaNotification.Aborted || e is OtaNotification.Error) break
                }
            }
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 10_000L
        const val ACK_TIMEOUT_MS = 15_000L
        /** END triggers SHA-256 and image validation over the whole slot. */
        const val VERIFY_TIMEOUT_MS = 30_000L
        const val ABORT_TIMEOUT_MS = 3_000L
    }
}
