package io.github.jeepnjonny.lorafieldops.firmware

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import io.github.jeepnjonny.lorafieldops.BuildConfig
import io.github.jeepnjonny.lorafieldops.platform.BleTrackerInfo
import io.github.jeepnjonny.lorafieldops.platform.FirmwarePlatform
import io.github.jeepnjonny.lorafieldops.platform.PickedFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Firmware files are 2 MB at most today; refuse anything far larger. */
private const val MAX_FIRMWARE_BYTES = 16 * 1024 * 1024

class AndroidFirmwarePlatform(context: Context) : FirmwarePlatform {
    private val app = context.applicationContext
    private val dfu = NordicDfuRunner(app)

    override suspend fun httpGet(url: String, onProgress: (received: Long, total: Long?) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as? HttpURLConnection ?: throw IOException("Not an HTTP URL")
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                // GitHub's API requires a User-Agent; release downloads redirect to its CDN.
                conn.setRequestProperty("User-Agent", "LoRaFieldOpsConfig-Android/${BuildConfig.VERSION_NAME}")
                if (url.startsWith("https://api.github.com/")) {
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                }
                val code = conn.responseCode
                if (code == 404) throw IOException("No published release found")
                if (code == 403 || code == 429) throw IOException("GitHub is rate-limiting requests; try again later")
                if (code !in 200..299) throw IOException("Server answered HTTP $code")
                val total = conn.contentLengthLong.takeIf { it > 0 }
                if (total != null && total > MAX_FIRMWARE_BYTES) throw IOException("File is unexpectedly large")
                val out = ByteArrayOutputStream(total?.toInt() ?: 64 * 1024)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var received = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        received += n
                        if (received > MAX_FIRMWARE_BYTES) throw IOException("File is unexpectedly large")
                        onProgress(received, total)
                    }
                }
                out.toByteArray()
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun runNordicDfu(
        tracker: BleTrackerInfo,
        zip: ByteArray,
        onProgress: (percent: Int, kbPerSecond: Float) -> Unit,
    ) = dfu.run(tracker.handle as BluetoothDevice, zip, onProgress)

    @Composable
    override fun KeepScreenOn() {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    @Composable
    override fun rememberFilePicker(onPicked: (PickedFile?) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                onPicked(withContext(Dispatchers.IO) { runCatching { readPicked(uri) }.getOrNull() })
            }
        }
        // Firmware files have no reliable MIME type (.bin is often application/octet-stream or nothing).
        return { launcher.launch(arrayOf("*/*")) }
    }

    private fun readPicked(uri: Uri): PickedFile? {
        val resolver = app.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: return null
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FIRMWARE_BYTES) return null
            }
            out.toByteArray()
        } ?: return null
        return PickedFile(name, bytes)
    }
}
