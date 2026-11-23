package io.github.jeepnjonny.lorafieldops.logger

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [HttpPoster] on HttpURLConnection: no extra dependency. Never logs the key. */
class UrlConnectionPoster(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpPoster {
    override suspend fun post(url: String, bearer: String, jsonBody: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try { send(url, bearer, jsonBody) } catch (e: IOException) {
                throw NetworkException(e.message ?: e.javaClass.simpleName, e)
            }
        }

    private fun send(url: String, bearer: String, jsonBody: String): Pair<Int, String> {
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
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
