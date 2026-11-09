package com.kj7nye.lorafieldops.serial

import kotlinx.coroutines.flow.SharedFlow

/** Physical link to the tracker's setup CLI. */
enum class TransportKind { USB, BLE }

/**
 * A byte pipe to the tracker's text setup CLI (firmware serial_setup.cpp).
 *
 * The CLI protocol is identical on every transport, so [ProtocolHandler] and
 * everything above it is transport-agnostic: USB serial ([SerialManager]) and
 * the BLE configuration link ([BleTransport]) both implement this interface.
 */
interface DeviceTransport {
    val kind: TransportKind

    /** Raw bytes received from the tracker, in arrival order. */
    val rxFlow: SharedFlow<ByteArray>

    /** Lifecycle events: [ConnectionEvent.Opened] once the link is usable, then Lost/Error. */
    val connectionEvents: SharedFlow<ConnectionEvent>

    val isOpen: Boolean

    /**
     * Sends [data] to the tracker, returning once the transport has accepted all of it
     * (for BLE: every chunk acknowledged by the tracker). Throws on I/O failure.
     */
    suspend fun write(data: ByteArray)

    /** Closes the link. Safe to call repeatedly; emits no further events. */
    fun close()
}

sealed class ConnectionEvent {
    /** Link is ready for CLI traffic. [label] is a human-readable device name. */
    data class Opened(val label: String) : ConnectionEvent()
    /** Intermediate step while opening (e.g. "Pairing…"), for UI progress text. */
    data class Progress(val message: String) : ConnectionEvent()
    /** Opening failed. */
    data class Error(val message: String) : ConnectionEvent()
    /** An open link dropped. */
    data class Lost(val reason: String) : ConnectionEvent()
}
