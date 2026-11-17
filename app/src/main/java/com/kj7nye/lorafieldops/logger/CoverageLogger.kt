package com.kj7nye.lorafieldops.logger

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kj7nye.lorafieldops.BuildConfig
import com.kj7nye.lorafieldops.model.CoverageEntry
import com.kj7nye.lorafieldops.model.CoverageEntryType
import com.kj7nye.lorafieldops.serial.BleTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CoverageLogger"
private const val UPLOAD_INTERVAL_MS = 60_000L
private const val FLUSH_TICK_MS = 250L
private const val UPLOAD_WORK = "coverage-upload"

/** What the logger is doing right now; drives the logger screen and the notification. */
data class LoggerRuntime(
    /** A tracker link is attached and KISS notifications are on. */
    val logging: Boolean = false,
    /** The link dropped and the app is trying to get it back. */
    val reconnecting: Boolean = false,
    val device: String = "",
    val sent: Int = 0,
    val heard: Int = 0,
    /** Why logging couldn't start on the current link (e.g. no KISS service). */
    val linkError: String? = null,
)

/**
 * Process-wide coverage logger. The ViewModel hands it the BLE link of each session
 * ([attach]/[detach]); it subscribes to the tracker's KISS notifications, turns them
 * into tx/rx entries ([PacketRecorder]), queues them in Room and uploads them.
 *
 * Nothing here runs, and nothing is sent anywhere, unless [LoggerSettings.enabled] is on.
 */
class CoverageLogger private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = LoggerSettingsStore(app)
    private val dao = LogQueueDb.get(app).dao()
    private val uploader = CoverageUploader(
        RoomUploadQueue(dao), UrlConnectionPoster(),
        appId = "lora-fieldops-config-android/${BuildConfig.VERSION_NAME}",
    )

    private val _runtime = MutableStateFlow(LoggerRuntime())
    val runtime: StateFlow<LoggerRuntime> = _runtime.asStateFlow()

    val queuedCount = dao.countFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    /** Serializes inserts so queue order matches arrival order. */
    private val inserts = Channel<List<LogQueueRow>>(Channel.UNLIMITED)

    @Volatile private var transport: BleTransport? = null
    private var sessionJob: Job? = null

    val settings: LoggerSettings get() = settingsStore.settings.value
    val isEnabled: Boolean get() = settings.enabled

    init {
        scope.launch { for (rows in inserts) runCatching { dao.insert(rows) }.onFailure { Log.w(TAG, "queue insert failed", it) } }
        // Re-evaluate the session whenever a setting that affects recording changes.
        scope.launch {
            settingsStore.settings
                .map { Triple(it.enabled, it.device.trim(), it.uploadTx to it.uploadRx) }
                .distinctUntilChanged()
                .collect { restartSession() }
        }
        scope.launch {
            var wasOn = false
            settingsStore.settings.map { it.enabled }.distinctUntilChanged().collect { on ->
                if (on) { prefillDevice(); scheduleUpload() }
                else if (wasOn) WorkManager.getInstance(app).cancelUniqueWork(UPLOAD_WORK)
                wasOn = on
            }
        }
    }

    // -------------------------------------------------------------------------
    // Session (BLE link) lifecycle, called by the ViewModel
    // -------------------------------------------------------------------------

    /** A BLE session opened. Starts logging on it if the logger is enabled. */
    fun attach(link: BleTransport) {
        transport = link
        _runtime.value = _runtime.value.copy(reconnecting = false)
        restartSession()
    }

    /**
     * The session ended. [reconnecting]: the app will try to get the link back, so the
     * foreground service (and its notification) stays up meanwhile.
     */
    fun detach(reconnecting: Boolean = false) {
        transport = null
        _runtime.value = _runtime.value.copy(reconnecting = reconnecting && isEnabled)
        restartSession()
    }

    fun setReconnecting(on: Boolean) {
        _runtime.value = _runtime.value.copy(reconnecting = on && isEnabled)
        updateService()
    }

    @Volatile private var trackerCallsign: String? = null

    /** Remembers the connected tracker's configured callsign, to prefill the device field. */
    fun suggestDevice(callsign: String) {
        val c = callsign.trim().uppercase()
        if (c.isEmpty() || c.startsWith("NOCALL")) return
        trackerCallsign = c
        prefillDevice()
    }

    /** Fills an empty device field from the tracker, but only once the logger is on. */
    private fun prefillDevice() {
        val c = trackerCallsign ?: return
        if (isEnabled && settings.device.isBlank()) settingsStore.update { it.copy(device = c) }
    }

    /**
     * Queues a device note, if notes are on and [trackerCallsign] (the connected tracker)
     * is the logger's device. Never pass secrets in [text].
     */
    fun recordEvent(trackerCallsign: String, text: String) {
        val s = settings
        if (!s.enabled || !s.recordSettingNotes) return
        val device = s.device.trim()
        if (device.isEmpty() || normalizeCall(deviceSourceCall(device)) != normalizeCall(trackerCallsign)) return
        val entry = CoverageEntry(CoverageEntryType.EVENT, System.currentTimeMillis() / 1000, text = text)
        inserts.trySend(listOf(entry.toRow(device)))
    }

    @Synchronized
    private fun restartSession() {
        val previous = sessionJob
        sessionJob = null
        val link = transport
        val s = settings
        val device = s.device.trim()
        sessionJob = scope.launch {
            // Non-cancellable wait: if this job is itself replaced while waiting, the next
            // one still waits for it, so an old session's teardown (KISS unsubscribe)
            // can never run after a newer session subscribed.
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            if (link == null || !s.enabled) {
                _runtime.value = _runtime.value.copy(logging = false, linkError = null)
                updateService()
                return@launch
            }
            if (device.isEmpty()) {
                _runtime.value = _runtime.value.copy(logging = false, linkError = "Set the device callsign to start logging")
                updateService()
                return@launch
            }
            runSession(link, s, device)
        }
    }

    private suspend fun runSession(link: BleTransport, s: LoggerSettings, device: String) {
        val recorder = PacketRecorder(device, includeTx = s.uploadTx, includeRx = s.uploadRx)
        val sessionScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        try {
            // Collect before subscribing so the first frame isn't missed.
            sessionScope.launch {
                link.kissFlow.collect { bytes ->
                    queue(synchronized(recorder) { recorder.onBytes(bytes, System.currentTimeMillis()) }, device)
                }
            }
            sessionScope.launch {
                while (isActive) {
                    delay(FLUSH_TICK_MS)
                    queue(synchronized(recorder) { recorder.flush(System.currentTimeMillis()) }, device)
                }
            }
            try {
                link.setKissNotifications(true)
            } catch (e: Exception) {
                _runtime.value = _runtime.value.copy(logging = false, linkError = e.message ?: "Couldn't subscribe to KISS")
                updateService()
                return
            }
            _runtime.value = _runtime.value.copy(logging = true, device = device, sent = 0, heard = 0, linkError = null)
            updateService()
            // Upload every minute while connected and online; WorkManager covers the rest.
            sessionScope.launch {
                while (isActive) {
                    delay(UPLOAD_INTERVAL_MS)
                    if (isOnline()) runUpload()
                }
            }
            awaitCancellation()
        } finally {
            sessionScope.coroutineContext[Job]?.cancel()
            queue(synchronized(recorder) { recorder.flush(System.currentTimeMillis(), force = true) }, device)
            // Turn the subscription off if the link is still up (logger switched off mid-session).
            if (transport === link && link.isOpen) {
                withContext(NonCancellable) { runCatching { link.setKissNotifications(false) } }
            }
            _runtime.value = _runtime.value.copy(logging = false)
            updateService()
            if (settings.enabled) scheduleUpload()
        }
    }

    private fun queue(entries: List<CoverageEntry>, device: String) {
        if (entries.isEmpty()) return
        val tx = entries.count { it.type == CoverageEntryType.TX }
        val rx = entries.count { it.type == CoverageEntryType.RX }
        _runtime.value = _runtime.value.let { it.copy(sent = it.sent + tx, heard = it.heard + rx) }
        inserts.trySend(entries.map { it.toRow(device) })
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    /** Upload now (screen button). */
    fun uploadNow() {
        scope.launch { runUpload() }
    }

    /** One upload pass. Used by the 60 s loop, the button and [UploadWorker]. */
    suspend fun runUpload(): UploadResult {
        val s = settings
        if (!s.enabled) return UploadResult.Skipped("Logger is off")
        settingsStore.status.value.blockedMessage?.let { return UploadResult.Skipped(it) }
        _uploading.value = true
        val result = try {
            uploader.uploadAll(s.baseUrl, s.apiKey)
        } finally {
            _uploading.value = false
        }
        // A settings change during the request means this outcome may be about old settings.
        val stale = settings != s
        when (result) {
            is UploadResult.Done -> settingsStore.updateStatus {
                it.copy(
                    lastUploadMs = if (result.sent > 0) System.currentTimeMillis() else it.lastUploadMs,
                    lastError = null,
                    entryErrors = if (result.sent > 0) result.errors.take(20) else it.entryErrors,
                )
            }
            is UploadResult.Blocked -> if (!stale) settingsStore.updateStatus {
                it.copy(lastError = result.message, blockedMessage = result.message)
            }
            is UploadResult.Retry -> {
                settingsStore.updateStatus { it.copy(lastError = result.message) }
                scheduleUpload()
            }
            is UploadResult.Skipped -> settingsStore.updateStatus { it.copy(lastError = result.reason) }
        }
        return result
    }

    /** Hands the queue to WorkManager: runs when online, retries with exponential backoff. */
    fun scheduleUpload() {
        if (!isEnabled) return
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun isOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // -------------------------------------------------------------------------
    // Foreground service
    // -------------------------------------------------------------------------

    /** The service runs while the logger is on and a tracker is connected (or being reconnected). */
    val serviceWanted: Boolean
        get() = isEnabled && (_runtime.value.logging || _runtime.value.reconnecting)

    private fun updateService() {
        if (serviceWanted) {
            if (!LoggerService.running) {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, LoggerService::class.java))
                } catch (e: Exception) {
                    // Android 12+ refuses to start a foreground service from the background.
                    // Logging still runs while the app is alive; the next foreground start retries.
                    Log.w(TAG, "couldn't start logger service: ${e.javaClass.simpleName}")
                }
            }
        } else if (LoggerService.running) {
            app.stopService(Intent(app, LoggerService::class.java))
        }
    }

    companion object {
        @Volatile private var instance: CoverageLogger? = null

        fun get(context: Context): CoverageLogger = instance ?: synchronized(this) {
            instance ?: CoverageLogger(context).also { instance = it }
        }
    }
}
