package com.kj7nye.lorafieldops.logger

import com.kj7nye.lorafieldops.model.CoverageEntry
import com.kj7nye.lorafieldops.model.CoverageEntryType
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Uploader against a real local HTTP server, through the production [UrlConnectionPoster]. */
class CoverageUploaderTest {

    private class Request(val auth: String?, val body: String) {
        val json get() = Json.parseToJsonElement(body).jsonObject
        val entryCount get() = json["entries"]!!.jsonArray.size
    }

    /** In-memory queue with the same contract as the Room one. */
    private class FakeQueue : UploadQueue {
        val rows = mutableListOf<QueuedEntry>()
        private var nextId = 1L
        fun add(device: String, entry: CoverageEntry) { rows += QueuedEntry(nextId++, device, entry) }
        override suspend fun nextBatch(limit: Int, exclude: Set<String>): List<QueuedEntry> {
            val device = rows.firstOrNull { it.device !in exclude }?.device ?: return emptyList()
            return rows.filter { it.device == device }.take(limit)
        }
        override suspend fun delete(ids: List<Long>) { rows.removeAll { it.id in ids } }
    }

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Request>()
    /** Decides each reply: (request) -> (status, body). */
    private var responder: (Request) -> Pair<Int, String> = { r -> 200 to """{"accepted":${r.entryCount},"duplicates":0,"ignored":0,"errors":[]}""" }
    private lateinit var baseUrl: String
    private val queue = FakeQueue()
    private lateinit var uploader: CoverageUploader

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/aprs-coverage/api/devicelog") { ex ->
            val req = Request(ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes().toString(Charsets.UTF_8))
            synchronized(requests) { requests += req }
            val (code, body) = responder(req)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/aprs-coverage/"
        uploader = CoverageUploader(queue, UrlConnectionPoster(), appId = "lora-fieldops-config-android/test")
    }

    @After
    fun tearDown() = server.stop(0)

    private fun fill(n: Int, device: String = "K7ABC-9") = repeat(n) {
        queue.add(device, CoverageEntry(CoverageEntryType.RX, 1_763_251_200L + it, packet = "N0CALL>APRS:>$it", rssi = -97, snr = 6.5))
    }

    @Test
    fun sendsWireFormatWithBearerKey() = runBlocking {
        queue.add("K7ABC-9", CoverageEntry(CoverageEntryType.TX, 1_763_251_200, packet = "K7ABC-9>APLRT1,WIDE1-1:!4336.66N/11612.40W>"))
        queue.add("K7ABC-9", CoverageEntry(CoverageEntryType.EVENT, 1_763_251_300, text = "beacon rate 120 → 60 s"))
        val r = uploader.uploadAll(baseUrl, "acv_secret")
        assertTrue(r is UploadResult.Done)
        val req = requests.single()
        assertEquals("Bearer acv_secret", req.auth)
        assertEquals("K7ABC-9", req.json["device"]!!.jsonPrimitive.content)
        assertEquals("lora-fieldops-config-android/test", req.json["app"]!!.jsonPrimitive.content)
        val entries = req.json["entries"]!!.jsonArray
        assertEquals("tx", entries[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("no null fields on the wire", entries[0].jsonObject.containsKey("rssi"))
        assertEquals("event", entries[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("beacon rate 120 → 60 s", entries[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun batchesOf500() = runBlocking {
        fill(1201)
        val r = uploader.uploadAll(baseUrl, "acv_k") as UploadResult.Done
        assertEquals(listOf(500, 500, 201), requests.map { it.entryCount })
        assertEquals(1201, r.sent)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun oneDevicePerRequest() = runBlocking {
        fill(2, "K7ABC-9"); fill(3, "K7ABC-7")
        uploader.uploadAll(baseUrl, "acv_k")
        assertEquals(listOf("K7ABC-9", "K7ABC-7"), requests.map { it.json["device"]!!.jsonPrimitive.content })
    }

    @Test
    fun entryErrorsAreDeletedAndReported() = runBlocking {
        fill(3)
        responder = { 200 to """{"accepted":2,"duplicates":0,"ignored":0,"errors":[{"index":1,"error":"bad packet"}]}""" }
        val r = uploader.uploadAll(baseUrl, "acv_k") as UploadResult.Done
        assertTrue(queue.rows.isEmpty())
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0].startsWith("bad packet"))
        assertTrue(r.errors[0].contains("N0CALL>APRS:>1"))
    }

    @Test
    fun unauthorizedBlocksAndKeepsQueue() = runBlocking {
        fill(3)
        responder = { 401 to """{"error":"invalid key"}""" }
        val r = uploader.uploadAll(baseUrl, "acv_bad")
        assertTrue(r is UploadResult.Blocked && r.code == 401)
        assertEquals(3, queue.rows.size)
    }

    @Test
    fun unknownDeviceBlocks() = runBlocking {
        fill(1)
        responder = { 404 to "" }
        val r = uploader.uploadAll(baseUrl, "acv_k")
        assertTrue(r is UploadResult.Blocked && r.code == 404)
        assertEquals(1, queue.rows.size)
    }

    @Test
    fun unknownDeviceDoesNotBlockOtherDevices() = runBlocking {
        fill(2, "K7OLD-9"); fill(3, "K7ABC-9")
        responder = { r ->
            if (r.json["device"]!!.jsonPrimitive.content == "K7OLD-9") 404 to ""
            else 200 to """{"accepted":${r.entryCount}}"""
        }
        val r = uploader.uploadAll(baseUrl, "acv_k") as UploadResult.Done
        assertEquals(3, r.sent)
        assertEquals(listOf("K7OLD-9", "K7OLD-9"), queue.rows.map { it.device })
        assertTrue(r.errors.single().contains("K7OLD-9"))
    }

    @Test
    fun otherClientErrorParksDeviceAndRetries() = runBlocking {
        fill(2, "K7BAD-9"); fill(1, "K7ABC-9")
        responder = { r ->
            if (r.json["device"]!!.jsonPrimitive.content == "K7BAD-9") 403 to """{"error":"not yours"}"""
            else 200 to """{"accepted":1}"""
        }
        val r = uploader.uploadAll(baseUrl, "acv_k") as UploadResult.Done
        assertEquals(1, r.sent)
        assertEquals(2, queue.rows.size)
        responder = { 403 to """{"error":"not yours"}""" }
        val again = uploader.uploadAll(baseUrl, "acv_k")
        assertTrue(again is UploadResult.Retry && again.message.contains("not yours"))
    }

    @Test
    fun captivePortalReplyKeepsQueue() = runBlocking {
        fill(3)
        responder = { 200 to "<html><body>Please sign in to Hotel Wi-Fi</body></html>" }
        assertTrue(uploader.uploadAll(baseUrl, "acv_k") is UploadResult.Retry)
        assertEquals(3, queue.rows.size)
    }

    @Test
    fun tooLargeHalvesBatch() = runBlocking {
        fill(300)
        responder = { r -> if (r.entryCount > 100) 413 to "" else 200 to """{"accepted":${r.entryCount}}""" }
        val r = uploader.uploadAll(baseUrl, "acv_k") as UploadResult.Done
        assertEquals(listOf(300, 150, 75, 75, 75, 75), requests.map { it.entryCount })
        assertEquals(300, r.sent)
        assertEquals(75, uploader.batchSize)
    }

    @Test
    fun unprocessableSingleEntryIsDroppedSoQueueMovesOn() = runBlocking {
        fill(2)
        responder = { r -> if (r.body.contains(">0")) 422 to """{"error":"unparseable"}""" else 200 to """{"accepted":1}""" }
        val r = uploader.uploadAll(baseUrl, "acv_k") as UploadResult.Done
        assertTrue(queue.rows.isEmpty())
        assertEquals(1, r.sent)
        assertTrue(r.errors.single().contains("unparseable"))
    }

    @Test
    fun serverErrorRetriesAndKeepsQueue() = runBlocking {
        fill(5)
        responder = { 503 to "down" }
        assertTrue(uploader.uploadAll(baseUrl, "acv_k") is UploadResult.Retry)
        assertEquals(5, queue.rows.size)
    }

    @Test
    fun networkErrorRetries() = runBlocking {
        fill(1)
        server.stop(0)
        assertTrue(uploader.uploadAll(baseUrl, "acv_k") is UploadResult.Retry)
        assertEquals(1, queue.rows.size)
    }

    @Test
    fun partialProgressKeptWhenLaterBatchFails() = runBlocking {
        fill(600)
        var n = 0
        responder = { r -> if (n++ == 0) 200 to """{"accepted":${r.entryCount}}""" else 500 to "" }
        assertTrue(uploader.uploadAll(baseUrl, "acv_k") is UploadResult.Retry)
        assertEquals(100, queue.rows.size)
    }

    @Test
    fun missingKeySendsNothing() = runBlocking {
        fill(1)
        assertTrue(uploader.uploadAll(baseUrl, "") is UploadResult.Skipped)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun plainHttpToAnotherHostSendsNothing() = runBlocking {
        fill(1)
        assertTrue(uploader.uploadAll("http://example.org/aprs-coverage", "acv_k") is UploadResult.Skipped)
        assertEquals(1, queue.rows.size)
    }

    @Test
    fun emptyQueueSendsNothing() = runBlocking {
        val r = uploader.uploadAll(baseUrl, "acv_k") as UploadResult.Done
        assertEquals(0, r.sent)
        assertTrue(requests.isEmpty())
    }
}
