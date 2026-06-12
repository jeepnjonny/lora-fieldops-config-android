package com.kj7nye.lorafieldops.serial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// ---------------------------------------------------------------------------
// Constants matching the firmware's serial_setup.cpp
// ---------------------------------------------------------------------------

private const val PROMPT = "\n> "                // bytes: 0x0A 0x3E 0x20
private const val BANNER_SETUP = "SETUP MODE ACTIVE"
private const val BANNER_KISS  = "KISS TNC"
private const val BANNER_LOG   = "[LOG]"
private const val EXPORT_BEGIN = "---- BEGIN tracker_conf.json ----"
private const val EXPORT_END   = "---- END tracker_conf.json ----"
private const val IMPORT_SUCCESS = "[import] config written"

private const val CMD_TIMEOUT_MS  = 10_000L
private const val ENTRY_DELAY1_MS = 150L
private const val ENTRY_DELAY2_MS = 400L
private const val SETUP_ENTRY_TIMEOUT_MS = 15_000L

/** Result wrapper for a single serial command exchange. */
sealed class CommandResult {
    data class Ok(val text: String) : CommandResult()
    data class Err(val message: String) : CommandResult()
    object Timeout : CommandResult()
}

/** Current mode of the firmware's serial port. */
enum class SerialMode { KISS, SETUP, LOG, UNKNOWN }

/**
 * Protocol state machine wrapping [SerialManager].
 *
 * Responsibilities:
 * - Tracks the current [SerialMode] by watching banner strings.
 * - Executes the 3-step entry sequence to enter SETUP mode.
 * - Queues commands sequentially; each resolves when the prompt (`\n> `) appears.
 * - Handles the import paste-mode protocol (brace-balanced auto-end).
 * - Parses the export response (between BEGIN/END markers).
 */
class ProtocolHandler(
    private val serial: SerialManager,
    rxFlow: SharedFlow<ByteArray>,
) {
    var mode: SerialMode = SerialMode.UNKNOWN
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cmdQueue = Channel<PendingCommand>(Channel.UNLIMITED)

    // Accumulator for incoming bytes; shared across the reader coroutine
    private val accum = StringBuilder(8192)

    // Currently pending command (set by dispatcher, consumed by reader)
    @Volatile private var pending: PendingCommand? = null

    // Import-specific state
    @Volatile private var importPending: PendingCommand? = null
    @Volatile private var inImportPaste = false

    // Export accumulation
    private var exportCapturing = false
    private val exportBuf = StringBuilder()

    init {
        scope.launch { rxReader(rxFlow) }
        scope.launch { cmdDispatcher() }
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Executes the 3-step KISS→SETUP mode-entry handshake. Returns success/failure. */
    suspend fun enterSetupMode(): CommandResult {
        return try {
            withTimeout(SETUP_ENTRY_TIMEOUT_MS) {
                // Step 1: clear any partially-typed KISS escape
                serial.write("discard\r\n".toByteArray())
                kotlinx.coroutines.delay(ENTRY_DELAY1_MS)
                // Step 2: exit any existing SETUP or LOG mode
                serial.write("exit\r\n".toByteArray())
                kotlinx.coroutines.delay(ENTRY_DELAY2_MS)
                // Step 3: enter setup
                sendCommandInternal("setup\r\n", awaitBanner = BANNER_SETUP)
            }
        } catch (e: TimeoutCancellationException) {
            CommandResult.Timeout
        } catch (e: Exception) {
            CommandResult.Err(e.message ?: "entry failed")
        }
    }

    /**
     * Sends a text command and waits for the `\n> ` prompt.
     * The returned text is everything the device printed since the last prompt.
     */
    suspend fun sendCommand(cmdText: String): CommandResult =
        sendCommandInternal("$cmdText\r\n", awaitBanner = null)

    /**
     * Sends the `export` command and returns the JSON between BEGIN/END markers,
     * or an error if not found within the timeout.
     */
    suspend fun exportConfig(): CommandResult {
        accum.clear()
        exportCapturing = false
        exportBuf.clear()
        return when (val r = sendCommand("export")) {
            is CommandResult.Ok -> {
                val text = r.text
                val start = text.indexOf(EXPORT_BEGIN)
                val end   = text.indexOf(EXPORT_END)
                if (start >= 0 && end > start) {
                    val json = text.substring(start + EXPORT_BEGIN.length, end).trim()
                    CommandResult.Ok(json)
                } else {
                    CommandResult.Err("export markers not found in response")
                }
            }
            else -> r
        }
    }

    /**
     * Sends `import\r\n`, then streams [json] bytes, and waits for the
     * `[import] config written` success string or a timeout.
     */
    suspend fun importConfig(json: String): CommandResult {
        return try {
            withTimeout(CMD_TIMEOUT_MS * 3) {
                // Tell the firmware to start paste mode
                val primeResult = sendCommand("import")
                if (primeResult is CommandResult.Err) return@withTimeout primeResult

                // Mark that we are now in paste mode so the reader watches for import success
                inImportPaste = true
                val importCmd = PendingCommand()
                importPending = importCmd

                // Small delay then send the JSON bytes
                kotlinx.coroutines.delay(200)
                serial.write(json.toByteArray(Charsets.UTF_8))

                importCmd.await()
            }
        } catch (e: TimeoutCancellationException) {
            inImportPaste = false
            importPending = null
            CommandResult.Timeout
        } catch (e: Exception) {
            inImportPaste = false
            importPending = null
            CommandResult.Err(e.message ?: "import failed")
        }
    }

    fun close() { scope.cancel() }

    // ---------------------------------------------------------------------------
    // Private — reader coroutine
    // ---------------------------------------------------------------------------

    private suspend fun rxReader(flow: SharedFlow<ByteArray>) {
        flow.collect { bytes ->
            val text = bytes.toString(Charsets.UTF_8)
            accum.append(text)

            // Detect mode banners
            when {
                accum.contains(BANNER_SETUP) -> mode = SerialMode.SETUP
                accum.contains(BANNER_KISS)  -> mode = SerialMode.KISS
                accum.contains(BANNER_LOG)   -> mode = SerialMode.LOG
            }

            // Check for import success
            if (inImportPaste && accum.contains(IMPORT_SUCCESS)) {
                inImportPaste = false
                importPending?.complete(CommandResult.Ok(accum.toString()))
                importPending = null
                accum.clear()
                return@collect
            }

            // Resolve pending command when prompt appears
            val p = pending
            if (p != null && accum.contains(PROMPT)) {
                val response = accum.toString()
                // Trim the trailing prompt
                val trimmed = response.substringBeforeLast(PROMPT).trim()
                pending = null
                accum.clear()
                p.complete(CommandResult.Ok(trimmed))
            }

            // Prevent unbounded accumulator growth between commands
            if (accum.length > 65536) accum.delete(0, accum.length - 32768)
        }
    }

    // ---------------------------------------------------------------------------
    // Private — command dispatcher coroutine
    // ---------------------------------------------------------------------------

    private suspend fun cmdDispatcher() {
        for (cmd in cmdQueue) {
            pending = cmd
            try {
                serial.write(cmd.text.toByteArray())
            } catch (e: Exception) {
                pending = null
                cmd.complete(CommandResult.Err("write failed: ${e.message}"))
                continue
            }
            // Wait for the reader to resolve this command (or timeout)
            try {
                withTimeout(CMD_TIMEOUT_MS) {
                    cmd.await()
                    // await() returns the result; dispatcher just drains the queue
                }
            } catch (e: TimeoutCancellationException) {
                pending = null
                cmd.complete(CommandResult.Timeout)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private suspend fun sendCommandInternal(
        raw: String,
        awaitBanner: String?,
    ): CommandResult {
        return if (awaitBanner != null) {
            // For mode-entry we don't use the queue; we just write and poll accum
            accum.clear()
            serial.write(raw.toByteArray())
            try {
                withTimeout(SETUP_ENTRY_TIMEOUT_MS) {
                    while (!accum.contains(awaitBanner) && !accum.contains(PROMPT)) {
                        kotlinx.coroutines.delay(50)
                    }
                    CommandResult.Ok(accum.toString())
                }
            } catch (e: TimeoutCancellationException) {
                CommandResult.Timeout
            }
        } else {
            val cmd = PendingCommand(raw)
            cmdQueue.send(cmd)
            cmd.await()
        }
    }
}

/** A command in the queue with its own deferred result. */
private class PendingCommand(val text: String = "") {
    private val deferred = kotlinx.coroutines.CompletableDeferred<CommandResult>()

    fun complete(result: CommandResult) {
        deferred.complete(result)
    }

    suspend fun await(): CommandResult = deferred.await()
}
