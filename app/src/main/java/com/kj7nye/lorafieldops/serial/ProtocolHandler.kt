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
/** Last line of the firmware's paste-mode banner, printed instead of a prompt by `import`. */
private const val IMPORT_READY    = "device reboots on success."
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
 * Protocol state machine on top of a [DeviceTransport] (USB serial or BLE).
 *
 * All parsing state is mutated exclusively inside the single [rxReader] coroutine
 * (Dispatchers.IO), so there are no concurrent accesses to the accumulator.
 * The public suspend functions hand off work via [CompletableDeferred] / [Channel]
 * and then await the result.
 *
 * [timeoutScale] stretches every timeout for slower links: BLE replies arrive as
 * many small notifications (20 bytes each on a tracker that keeps the default MTU).
 */
class ProtocolHandler(
    private val transport: DeviceTransport,
    rxFlow: SharedFlow<ByteArray>,
    private val timeoutScale: Long = 1L,
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
     * Returns Err when the firmware answered with a prompt but no setup banner, e.g.
     * "config session busy on USB" while the other transport holds the session.
     */
    suspend fun enterSetupMode(): CommandResult {
        // ProtocolHandler is freshly created per connection, so accum is always empty here.
        val deferred = CompletableDeferred<CommandResult>()
        entryDeferred = deferred

        val result = try {
            withTimeout(SETUP_ENTRY_TIMEOUT_MS * timeoutScale) {
                // Step 1: clear any in-progress KISS buffer / unsaved SETUP edits
                transport.write("discard\r\n".toByteArray())
                delay(ENTRY_DELAY1_MS)
                // Step 2: exit LOG or SETUP mode if already active
                transport.write("exit\r\n".toByteArray())
                delay(ENTRY_DELAY2_MS)
                // Step 3: enter SETUP mode
                transport.write("setup\r\n".toByteArray())
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            entryDeferred = null
            return CommandResult.Timeout
        } catch (e: Exception) {
            entryDeferred = null
            return CommandResult.Err(e.message ?: "entry failed")
        }

        if (result is CommandResult.Ok && !result.text.contains(BANNER_SETUP)) {
            val reason = result.text.lines().firstOrNull { it.trim().startsWith("ERR") }?.trim()
                ?.removePrefix("ERR:")?.trim()
            return CommandResult.Err(reason ?: "tracker did not enter setup mode")
        }
        return result
    }

    /**
     * Send a text command and wait for the `\n> ` prompt (or [completeOn], when the
     * command ends with something other than a prompt), timing out after [timeoutMs].
     * The returned text has the firmware's echo of the command removed.
     */
    suspend fun sendCommand(
        cmdText: String,
        timeoutMs: Long = CMD_TIMEOUT_MS,
        completeOn: String? = null,
    ): CommandResult {
        val cmd = PendingCommand(cmdText, timeoutMs * timeoutScale, completeOn)
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
     * Fails fast if the firmware refuses paste mode or rejects the pasted JSON.
     */
    suspend fun importConfig(json: String): CommandResult {
        // Paste mode prints a banner and no prompt; a prompt means it refused (unsaved edits).
        when (val prime = sendCommand("import", completeOn = IMPORT_READY)) {
            is CommandResult.Err -> return prime
            CommandResult.Timeout -> return CommandResult.Err("tracker did not enter import mode")
            is CommandResult.Ok -> if (!prime.text.contains(IMPORT_READY)) {
                return CommandResult.Err(prime.text.lines().firstOrNull { it.startsWith("ERR") } ?: prime.text)
            }
        }

        val deferred = CompletableDeferred<CommandResult>()
        importDeferred = deferred

        return try {
            transport.write(json.toByteArray(Charsets.UTF_8))
            withTimeout(CMD_TIMEOUT_MS * 3 * timeoutScale) { deferred.await() }
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
            // A newly dispatched command starts from a clean slate: anything still in
            // the accumulator (a banner, an unsolicited status line, a stray prompt)
            // predates it and must not be mistaken for its reply.
            val starting = pending
            if (starting != null && !starting.started) {
                starting.started = true
                accum.clear()
            }

            accum.append(bytes.toString(Charsets.UTF_8))

            // Mode banner detection
            if (accum.contains("SETUP MODE ACTIVE")) mode = SerialMode.SETUP
            else if (accum.contains("[KISS TNC]"))   mode = SerialMode.KISS
            else if (accum.contains("[LOG]"))        mode = SerialMode.LOG

            // --- Mode-entry completion (enterSetupMode) ---
            // Wait for the trailing PROMPT specifically, not just the banner: the banner
            // and its prompt can arrive in separate reads, and completing on the banner
            // alone leaves the real "\n> " to land in a freshly-cleared accum with nothing
            // listening for it.
            val ed = entryDeferred
            if (ed != null && accum.contains(PROMPT)) {
                entryDeferred = null
                ed.complete(CommandResult.Ok(accum.toString()))
                accum.clear()
                return@collect
            }

            // --- Import paste-mode completion ---
            // Success reboots the tracker; a prompt after the paste means the firmware
            // rejected the JSON (parse error, missing callsign, write failure) or aborted.
            val id = importDeferred
            if (id != null) {
                if (accum.contains(IMPORT_SUCCESS)) {
                    importDeferred = null
                    id.complete(CommandResult.Ok(accum.toString()))
                    accum.clear()
                } else if (accum.contains(PROMPT)) {
                    importDeferred = null
                    val reason = accum.lines().lastOrNull { it.contains("[import]") }?.trim()
                    id.complete(CommandResult.Err(reason ?: "tracker rejected the config"))
                    accum.clear()
                }
                return@collect
            }

            // --- Normal command resolution ---
            val p = pending
            if (p != null) {
                val terminator = when {
                    p.completeOn != null && accum.contains(p.completeOn) -> p.completeOn
                    accum.contains(PROMPT) -> PROMPT
                    else -> null
                }
                if (terminator != null) {
                    val raw = if (terminator == PROMPT) accum.toString().substringBeforeLast(PROMPT)
                              else accum.toString()
                    pending = null
                    accum.clear()
                    p.complete(CommandResult.Ok(stripCommandEcho(raw.trim(), p.commandText)))
                    return@collect
                }
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
                transport.write("${cmd.commandText}\r\n".toByteArray())
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

/**
 * The firmware echoes each typed character, so a reply starts with the command line
 * itself. Remove it so callers see only the firmware's output (e.g. "ERR: ..." or
 * "version.date=...") at the start of the text.
 */
private fun stripCommandEcho(reply: String, command: String): String {
    val firstLineEnd = reply.indexOf('\n')
    val firstLine = (if (firstLineEnd >= 0) reply.substring(0, firstLineEnd) else reply).trim()
    if (firstLine != command.trim()) return reply
    return if (firstLineEnd >= 0) reply.substring(firstLineEnd + 1).trim() else ""
}

/** A queued command with its own deferred result. */
private class PendingCommand(
    val commandText: String,
    val timeoutMs: Long = CMD_TIMEOUT_MS,
    /** Alternative end-of-reply marker for commands that don't finish with a prompt. */
    val completeOn: String? = null,
) {
    /** Set by rxReader on the first bytes received after this command was dispatched. */
    @Volatile var started = false
    private val deferred = CompletableDeferred<CommandResult>()
    fun complete(result: CommandResult) { deferred.complete(result) }
    suspend fun await(): CommandResult  = deferred.await()
}
