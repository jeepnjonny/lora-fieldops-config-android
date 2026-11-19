package io.github.jeepnjonny.lorafieldops.serial

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProtocolHandler against a scripted fake tracker that echoes and answers each write. */
class ProtocolHandlerTest {

    /**
     * Fake link. [respond] gets each write (decoded) and returns what the tracker
     * prints back, or null for silence. Replies go out in [chunk]-byte pieces, like
     * BLE notifications.
     */
    private class FakeTracker(
        var chunk: Int = 4096,
        var respond: (String) -> String? = { null },
    ) : DeviceTransport {
        override val kind = TransportKind.USB
        val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096)
        override val rxFlow: SharedFlow<ByteArray> = rx
        override val connectionEvents = MutableSharedFlow<ConnectionEvent>()
        override val isOpen = true
        val writes = mutableListOf<String>()

        override suspend fun write(data: ByteArray) {
            val text = data.toString(Charsets.UTF_8)
            synchronized(writes) { writes += text }
            val reply = respond(text) ?: return
            reply.toByteArray(Charsets.UTF_8).toList().chunked(chunk).forEach { rx.tryEmit(it.toByteArray()) }
        }

        override fun close() {}
    }

    /** A CLI that echoes the command line, prints [body], then the prompt. */
    private fun cli(body: (String) -> String): (String) -> String? = { w ->
        val cmd = w.trimEnd('\r', '\n')
        "$cmd\r\n${body(cmd)}\r\n> "
    }

    private val tracker = FakeTracker()
    private lateinit var handler: ProtocolHandler

    private fun start(): ProtocolHandler = runBlocking {
        val h = ProtocolHandler(tracker, tracker.rxFlow)
        // The reader subscribes asynchronously; don't let a reply race past it.
        withTimeout(2_000) { while (tracker.rx.subscriptionCount.value == 0) delay(5) }
        handler = h
        h
    }

    @After
    fun tearDown() { if (::handler.isInitialized) handler.close() }

    @Test
    fun commandReplyHasEchoRemoved() = runBlocking {
        tracker.respond = cli { "version.date=v1.4.7" }
        val r = start().sendCommand("version")
        assertEquals(CommandResult.Ok("version.date=v1.4.7"), r)
        assertEquals("version\r\n", tracker.writes.single())
    }

    @Test
    fun replySplitAcrossSmallChunksWithMultiByteChars() = runBlocking {
        tracker.chunk = 3
        tracker.respond = cli { "beacon rate 120 → 60 s, 23°C" }
        assertEquals(CommandResult.Ok("beacon rate 120 → 60 s, 23°C"), start().sendCommand("show"))
    }

    @Test
    fun firmwareRejectionIsOkWithErrText() = runBlocking {
        tracker.respond = cli { "ERR: symbol must be 1 char" }
        val r = start().sendCommand("beacon symbol xx") as CommandResult.Ok
        assertTrue(r.text.startsWith("ERR"))
    }

    @Test
    fun commandsRunInOrder() = runBlocking {
        tracker.respond = cli { "done $it" }
        val h = start()
        val a = async { h.sendCommand("one") }
        val b = async { h.sendCommand("two") }
        val c = async { h.sendCommand("three") }
        assertEquals(listOf("done one", "done two", "done three"), listOf(a, b, c).map { (it.await() as CommandResult.Ok).text })
        assertEquals(listOf("one\r\n", "two\r\n", "three\r\n"), tracker.writes)
    }

    @Test
    fun timeoutThenNextCommandStillWorks() = runBlocking {
        tracker.respond = { w -> if (w.startsWith("slow")) null else cli { "ok" }(w) }
        val h = start()
        assertEquals(CommandResult.Timeout, h.sendCommand("slow", timeoutMs = 200))
        assertEquals(CommandResult.Ok("ok"), h.sendCommand("fast"))
    }

    @Test
    fun closeReleasesWaitingCallers() = runBlocking {
        val h = start()   // tracker never answers
        val inFlight = async { h.sendCommand("hang", timeoutMs = 60_000) }
        val queued = async { h.sendCommand("next", timeoutMs = 60_000) }
        delay(100)
        h.close()
        val results = try {
            withTimeout(2_000) { listOf(inFlight.await(), queued.await()) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("callers still waiting after close()")
        }
        assertTrue(results.all { it is CommandResult.Err })
        assertTrue(h.sendCommand("after") is CommandResult.Err)
    }

    @Test
    fun lineBreakInCommandIsRefusedUnsent() = runBlocking {
        val h = start()
        assertTrue(h.sendCommand("beacon comment hi\r\nfactoryreset") is CommandResult.Err)
        assertTrue(tracker.writes.isEmpty())
    }

    @Test
    fun setupEntrySucceedsOnBannerAndPrompt() = runBlocking {
        tracker.respond = { w -> if (w.startsWith("setup")) "setup\r\nSETUP MODE ACTIVE\r\n> " else null }
        val r = start().enterSetupMode()
        assertTrue(r is CommandResult.Ok)
        assertEquals(listOf("discard\r\n", "exit\r\n", "setup\r\n"), tracker.writes)
    }

    @Test
    fun setupEntryRefusedReportsFirmwareReason() = runBlocking {
        tracker.respond = { w -> if (w.startsWith("setup")) "setup\r\nERR: config session busy on USB\r\n> " else null }
        assertEquals(CommandResult.Err("config session busy on USB"), start().enterSetupMode())
    }

    @Test
    fun exportExtractsJsonBetweenMarkers() = runBlocking {
        tracker.respond = cli {
            "---- BEGIN tracker_conf.json ----\r\n{\"deviceRole\":0}\r\n---- END tracker_conf.json ----"
        }
        assertEquals(CommandResult.Ok("{\"deviceRole\":0}"), start().exportConfig())
    }

    @Test
    fun exportWithoutMarkersIsAnError() = runBlocking {
        tracker.respond = cli { "nothing here" }
        assertTrue(start().exportConfig() is CommandResult.Err)
    }

    @Test
    fun importSucceedsOnWrittenMarker() = runBlocking {
        tracker.respond = { w ->
            when {
                w.startsWith("import") -> "import\r\nPaste JSON now; device reboots on success.\r\n"
                w.startsWith("{") -> "[import] config written, rebooting\r\n"
                else -> null
            }
        }
        assertTrue(start().importConfig("{\"deviceRole\":0}") is CommandResult.Ok)
    }

    @Test
    fun importRefusedWhenEditsUnsaved() = runBlocking {
        tracker.respond = cli { "ERR: unsaved edits; save or discard first" }
        val r = start().importConfig("{}")
        assertTrue(r is CommandResult.Err && r.message.contains("unsaved edits"))
    }

    @Test
    fun importRejectedJsonReportsReason() = runBlocking {
        tracker.respond = { w ->
            when {
                w.startsWith("import") -> "import\r\nPaste JSON now; device reboots on success.\r\n"
                w.startsWith("{") -> "[import] parse error at 1\r\n> "
                else -> null
            }
        }
        assertEquals(CommandResult.Err("[import] parse error at 1"), start().importConfig("{bad"))
    }
}
