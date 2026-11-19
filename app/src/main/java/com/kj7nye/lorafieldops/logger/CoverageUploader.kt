package com.kj7nye.lorafieldops.logger

import com.kj7nye.lorafieldops.model.CoverageEntry
import com.kj7nye.lorafieldops.model.CoverageEntryType
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val MAX_BATCH = 500

/** A queued entry plus the row id it is stored under and the device it was logged for. */
data class QueuedEntry(val id: Long, val device: String, val entry: CoverageEntry)

/** The upload side's view of the offline queue (Room in the app, a list in tests). */
interface UploadQueue {
    /**
     * Oldest entries, all for the same device (the device of the oldest entry not in
     * [exclude]), at most [limit].
     */
    suspend fun nextBatch(limit: Int, exclude: Set<String> = emptySet()): List<QueuedEntry>
    suspend fun delete(ids: List<Long>)
}

/** Minimal HTTP POST so the uploader can be tested against a local server. */
fun interface HttpPoster {
    /** Returns (status code, response body). Throws IOException on network failure. */
    suspend fun post(url: String, bearer: String, jsonBody: String): Pair<Int, String>
}

/** Server-side problem with one entry of an accepted batch. */
@Serializable
data class EntryError(val index: Int = -1, val error: String = "")

@Serializable
data class DeviceLogResponse(
    val accepted: Int = 0,
    val duplicates: Int = 0,
    val ignored: Int = 0,
    val errors: List<EntryError> = emptyList(),
)

sealed class UploadResult {
    /** Queue drained (or was empty). [errors] are per-entry problems the server reported. */
    data class Done(val sent: Int, val accepted: Int, val duplicates: Int, val ignored: Int, val errors: List<String>) : UploadResult()
    /** Bad/revoked key (401) or device not approved (404): stop until settings change. */
    data class Blocked(val code: Int, val message: String) : UploadResult()
    /** Network failure, 5xx, or another temporary problem: try again later. Nothing was lost. */
    data class Retry(val message: String) : UploadResult()
    /** Logger off, or settings incomplete: nothing was sent. */
    data class Skipped(val reason: String) : UploadResult()
}

@Serializable
private data class WireEntry(
    val type: String,
    val ts: Long,
    val packet: String? = null,
    val text: String? = null,
    val rssi: Int? = null,
    val snr: Double? = null,
)

@Serializable
private data class WireRequest(
    val device: String,
    val app: String,
    val entries: List<WireEntry>,
)

private val wireJson = Json { explicitNulls = false; encodeDefaults = true }
private val replyJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Sends queued entries to `{base}/api/devicelog` in batches of up to [MAX_BATCH].
 *
 * - 200: the batch is deleted from the queue, including entries the server listed in
 *   `errors` (resending them would fail the same way); those errors are reported.
 * - 200 with a body that isn't the server's JSON (captive portal): [UploadResult.Retry],
 *   nothing deleted.
 * - 401: [UploadResult.Blocked]; the caller stops uploading until settings change.
 * - 404 / other 4xx for one device: that device is skipped for the rest of the pass and
 *   reported; other devices still upload. If nothing could be sent, 404 is
 *   [UploadResult.Blocked] and other 4xx are [UploadResult.Retry].
 * - 413 / 422: batch size is halved and the same entries are tried again. A single
 *   entry still rejected at size 1 is dropped and reported, so it can't wedge the queue.
 * - network errors, 5xx, 429, anything else: [UploadResult.Retry], queue untouched.
 *
 * Resending a batch is always safe: the server dedupes.
 */
class CoverageUploader(
    private val queue: UploadQueue,
    private val http: HttpPoster,
    private val appId: String,
) {
    /** Shrinks on 413/422 and stays shrunk for this process; a restart tries full size again. */
    @Volatile var batchSize: Int = MAX_BATCH
        private set

    private val mutex = Mutex()

    suspend fun uploadAll(baseUrl: String, apiKey: String): UploadResult = mutex.withLock {
        if (apiKey.isBlank()) return UploadResult.Skipped("No upload key set")
        if (baseUrl.isBlank()) return UploadResult.Skipped("No website address set")
        if (!isSecureOrLoopback(baseUrl)) return UploadResult.Skipped("Website address must start with https://")
        val url = baseUrl.trim().trimEnd('/') + "/api/devicelog"

        var sent = 0; var accepted = 0; var duplicates = 0; var ignored = 0
        val errors = mutableListOf<String>()
        // Devices the server refused this pass. Their entries stay queued, and the
        // other devices' entries still go out.
        val parked = mutableSetOf<String>()
        var parkedMessage: String? = null
        var only404 = true
        while (true) {
            val batch = queue.nextBatch(batchSize, parked)
            if (batch.isEmpty()) break
            val device = batch.first().device
            val body = wireJson.encodeToString(WireRequest.serializer(), WireRequest(
                device = device,
                app = appId,
                entries = batch.map { it.entry.toWire() },
            ))
            val (code, reply) = try {
                http.post(url, apiKey.trim(), body)
            } catch (e: IOException) {
                return UploadResult.Retry("Network error: ${e.message ?: e.javaClass.simpleName}")
            }
            when {
                code == 200 -> {
                    // Anything that isn't our JSON (e.g. a Wi-Fi login page answering 200)
                    // means the batch never reached the server: keep it.
                    val r = runCatching { replyJson.decodeFromString(DeviceLogResponse.serializer(), reply) }
                        .getOrNull()
                        ?: return UploadResult.Retry(
                            "Unexpected reply from the website (not the coverage server?). " +
                            "If you're on Wi-Fi that needs a sign-in page, sign in first.")
                    queue.delete(batch.map { it.id })
                    sent += batch.size
                    accepted += r.accepted; duplicates += r.duplicates; ignored += r.ignored
                    r.errors.forEach { e ->
                        val what = batch.getOrNull(e.index)?.entry?.let { it.packet ?: it.text } ?: "entry ${e.index}"
                        errors += "${e.error} — ${what.take(80)}"
                    }
                }
                code == 401 -> return UploadResult.Blocked(code,
                    "Upload key rejected (401). Check the key, or make a new one on the website: Account tab, Upload keys.")
                code == 404 -> {
                    parked += device
                    parkedMessage = "Device \"$device\" isn't one of your approved devices on the website (404). " +
                        "Register it there, or fix the device callsign here."
                    errors += parkedMessage
                }
                code == 413 || code == 422 -> {
                    if (batch.size == 1) {
                        queue.delete(batch.map { it.id })
                        errors += "Server rejected an entry ($code: ${serverMessage(reply)}) — " +
                            (batch[0].entry.packet ?: batch[0].entry.text ?: "").take(80)
                    } else {
                        batchSize = (batch.size / 2).coerceAtLeast(1)
                    }
                }
                code in 300..399 -> return UploadResult.Retry(
                    "Website address redirects ($code). Use the exact https:// address of the site.")
                code in 400..499 && code != 408 && code != 429 -> {
                    // A request problem specific to this device's entries: park it so the
                    // rest of the queue isn't stuck behind it; it's retried next pass.
                    parked += device
                    only404 = false
                    parkedMessage = "Server refused entries for \"$device\" ($code${serverMessage(reply).let { if (it.isEmpty()) "" else ": $it" }})"
                    errors += parkedMessage
                }
                else -> return UploadResult.Retry("Server error $code${serverMessage(reply).let { if (it.isEmpty()) "" else ": $it" }}")
            }
        }
        if (parked.isNotEmpty() && sent == 0) {
            val msg = if (parked.size == 1) parkedMessage!! else errors.joinToString("\n")
            return if (only404) UploadResult.Blocked(404, msg) else UploadResult.Retry(msg)
        }
        UploadResult.Done(sent, accepted, duplicates, ignored, errors)
    }
}

private fun CoverageEntry.toWire() = WireEntry(
    type = type.wire,
    ts = ts,
    packet = if (type == CoverageEntryType.EVENT) null else packet,
    text = if (type == CoverageEntryType.EVENT) text else null,
    rssi = rssi,
    snr = snr,
)

/** The key is only ever sent over https (plain http only to this machine, for tests). */
private fun isSecureOrLoopback(baseUrl: String): Boolean {
    val u = runCatching { URL(baseUrl.trim()) }.getOrNull() ?: return false
    return u.protocol.equals("https", ignoreCase = true) ||
        (u.protocol.equals("http", ignoreCase = true) && u.host in setOf("127.0.0.1", "localhost", "::1", "[::1]"))
}

/** A short, human-readable message from an error reply body (`{"error": "..."}` or plain text). */
private fun serverMessage(body: String): String {
    val fromJson = Regex(""""(?:error|message)"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)?.groupValues?.get(1)
    return (fromJson ?: body).trim().take(200)
}

/** [HttpPoster] on HttpURLConnection: no extra dependency. Never logs the key. */
class UrlConnectionPoster(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpPoster {
    override suspend fun post(url: String, bearer: String, jsonBody: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as? HttpURLConnection) ?: throw IOException("Not an HTTP URL")
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.instanceFollowRedirects = false   // a redirect would drop the POST body (and leak the key to another host)
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                code to text
            } finally {
                conn.disconnect()
            }
        }
}
