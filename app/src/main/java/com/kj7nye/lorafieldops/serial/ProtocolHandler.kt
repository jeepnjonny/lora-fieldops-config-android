package com.kj7nye.lorafieldops.serial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// ---------------------------------------------------------------------------
// Constants matching the firmware's serial_setup.cpp
// ---------------------------------------------------------------------------

private const val PROMPT          = "\n> "               // 0x0A 0x3E 0x20
private const val BANNER_SETUP    = "SETUP MODE ACTIVE"
private const val IMPORT_SUCCESS  = "[import] config written"
private const val EXPORT_BEGIN    = "---- BEGIN tracker_conf.json ----"
private const val EXPORT_END      = "---- END tracker_conf.json ----"

private const val CMD_TIMEOUT_MS          = 10_000L
private const val SETUP_ENTRY_TIMEOUT_MS  = 15_000L
private const val ENTRY_DELAY1_MS         = 150L
private const val ENTRY_DELAY2_MS         = 400L

/** The firmware's `wifista scan` blocks the device for several seconds; give it more room
 *  than the default command timeout so a slightly slow scan doesn't false-timeout. */
const val WIFI_SCAN_TIMEOUT_MS = 20_000L

/** Result wrapper for a single serial command exchange. */
sealed class CommandResult {
    data class Ok(val text: String)  : CommandResult()
    data class Err(val message: String) : CommandResult()
    object Timeout : CommandResult()
}

/** Current mode of the firmware's serial port. */
enum class SerialMode { KISS, SETUP, LOG, UNKNOWN }

/**
 * Protocol state machine wrapping [SerialManager].
 *
 * All state is mutated exclusively inside the single [rxReader] coroutine
 * (Dispatchers.IO), so there are no concurrent accesses to the accumulator or
 * pending-command references.  The public suspend functions hand off work via
 * [CompletableDeferred] / [Channel] and then await the result.
 */
class ProtocolHandler(
    private val serial: SerialManager,
    rxFlow: SharedFlow<ByteArray>,
) {
    var mode: SerialMode = SerialMode.UNKNOWN
        private set

    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cmdQueue = Channel<PendingCommand>(Channel.UNLIMITED)

    // Only ever accessed inside rxReader — no @Volatile needed.
    private val accum = StringBuilder(8192)

    // Set by the caller before writing bytes; completed by rxReader.
    // @Volatile so the write in the caller thread is visible to rxReader.
    @Volatile private var pending:       PendingCommand? = null
    @Volatile private var entryDeferred: CompletableDeferred<CommandResult>? = null
    @Volatile private var importDeferred: CompletableDeferred<CommandResult>? = null

    init {
        scope.launch { rxReader(rxFlow) }
        scope.launch { cmdDispatcher() }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * 3-step KISS → SETUP entry handshake.
     *
     * Registers a deferred BEFORE writing any bytes so rxReader can complete it
     * as soon as the banner/prompt arrives — no polling, no thread-visibility race.
     */
    suspend fun enterSetupMode(): CommandResult {
        // ProtocolHandler is freshly created per connection, so accum is always empty here.
        val deferred = CompletableDeferred<CommandResult>()
        entryDeferred = deferred

        return try {
            withTimeout(SETUP_ENTRY_TIMEOUT_MS) {
                // Step 1: clear any in-progress KISS buffer / unsaved SETUP edits
                serial.write("discard\r\n".toByteArray())
                delay(ENTRY_DELAY1_MS)
                // Step 2: exit LOG or SETUP mode if already active
                serial.write("exit\r\n".toByteArray())
                delay(ENTRY_DELAY2_MS)
                // Step 3: enter SETUP mode
                serial.write("setup\r\n".toByteArray())
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            entryDeferred = null
            CommandResult.Timeout
        } catch (e: Exception) {
            entryDeferred = null
            CommandResult.Err(e.message ?: "entry failed")
        }
    }

    /** Send a text command and wait for the `\n> ` prompt, timing out after [timeoutMs]. */
    suspend fun sendCommand(cmdText: String, timeoutMs: Long = CMD_TIMEOUT_MS): CommandResult {
        val cmd = PendingCommand("$cmdText\r\n", timeoutMs)
        cmdQueue.send(cmd)
        return cmd.await()
    }

    /**
     * Send `export` and return the JSON extracted from the BEGIN/END markers,
     * or an error if markers are missing.
     */
    suspend fun exportConfig(): CommandResult {
        return when (val r = sendCommand("export")) {
            is CommandResult.Ok -> {
                val start = r.text.indexOf(EXPORT_BEGIN)
                val end   = r.text.indexOf(EXPORT_END)
                if (start >= 0 && end > start) {
                    CommandResult.Ok(r.text.substring(start + EXPORT_BEGIN.length, end).trim())
                } else {
                    CommandResult.Err("export markers not found in response")
                }
            }
            else -> r
        }
    }

    /**
     * Send `import`, stream [json] bytes, and wait for the import-success string.
     */
    suspend fun importConfig(json: String): CommandResult {
        // Tell the firmware to enter paste mode
        val primeResult = sendCommand("import")
        if (primeResult is CommandResult.Err) return primeResult

        val deferred = CompletableDeferred<CommandResult>()
        importDeferred = deferred

        return try {
            delay(200)
            serial.write(json.toByteArray(Charsets.UTF_8))
            withTimeout(CMD_TIMEOUT_MS * 3) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            importDeferred = null
            CommandResult.Timeout
        } catch (e: Exception) {
            importDeferred = null
            CommandResult.Err(e.message ?: "import failed")
        }
    }

    fun close() { scope.cancel() }

    // -------------------------------------------------------------------------
    // rxReader — sole owner of `accum`; runs on Dispatchers.IO
    // -------------------------------------------------------------------------

    private suspend fun rxReader(flow: SharedFlow<ByteArray>) {
        flow.collect { bytes ->
            accum.append(bytes.toString(Charsets.UTF_8))

            // Mode banner detection
            if (accum.contains("SETUP MODE ACTIVE")) mode = SerialMode.SETUP
            else if (accum.contains("[KISS TNC]"))   mode = SerialMode.KISS
            else if (accum.contains("[LOG]"))        mode = SerialMode.LOG

            // --- Mode-entry completion (enterSetupMode) ---
            // Wait for the trailing PROMPT specifically, not just the banner: the banner
            // and its prompt can arrive in separate USB reads, and completing on the banner
            // alone leaves the real "\n> " to land in a freshly-cleared accum with nothing
            // listening for it — it then leaks into and prematurely terminates the very
            // next command (almost always the post-connect `export`).
            val ed = entryDeferred
            if (ed != null && accum.contains(PROMPT)) {
                entryDeferred = null
                ed.complete(CommandResult.Ok(accum.toString()))
                accum.clear()
                return@collect
            }

            // --- Import paste-mode completion ---
            val id = importDeferred
            if (id != null && accum.contains(IMPORT_SUCCESS)) {
                importDeferred = null
                id.complete(CommandResult.Ok(accum.toString()))
                accum.clear()
                return@collect
            }

            // --- Normal command prompt resolution ---
            val p = pending
            if (p != null && accum.contains(PROMPT)) {
                val trimmed = accum.toString().substringBeforeLast(PROMPT).trim()
                pending = null
                accum.clear()
                p.complete(CommandResult.Ok(trimmed))
                return@collect
            }

            // Prevent unbounded growth between commands
            if (accum.length > 65536) accum.delete(0, accum.length - 32768)
        }
    }

    // -------------------------------------------------------------------------
    // cmdDispatcher — serialises the command queue; runs on Dispatchers.IO
    // -------------------------------------------------------------------------

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
            try {
                withTimeout(cmd.timeoutMs) { cmd.await() }
            } catch (e: TimeoutCancellationException) {
                pending = null
                cmd.complete(CommandResult.Timeout)
            }
        }
    }
}

/** A queued command with its own deferred result. */
private class PendingCommand(val text: String = "", val timeoutMs: Long = CMD_TIMEOUT_MS) {
    private val deferred = CompletableDeferred<CommandResult>()
    fun complete(result: CommandResult) { deferred.complete(result) }
    suspend fun await(): CommandResult  = deferred.await()
}
