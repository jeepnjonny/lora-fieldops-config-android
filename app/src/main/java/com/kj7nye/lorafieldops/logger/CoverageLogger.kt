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
import com.kj7nye.lorafieldops.model.BeaconConfig
import com.kj7nye.lorafieldops.model.CoverageEntry
import com.kj7nye.lorafieldops.model.CoverageEntryType
import com.kj7nye.lorafieldops.serial.BleTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CoverageLogger"
private const val UPLOAD_INTERVAL_MS = 60_000L
private const val FLUSH_TICK_MS = 250L
private const val UPLOAD_WORK = "coverage-upload"
private const val INSERT_ATTEMPTS = 4

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            // A logger bug must never take the app (and the user's config session) down.
            Log.e(TAG, "logger error", e)
            runCatching { settingsStore.updateStatus { it.copy(lastError = "Logger error: ${e.message ?: e.javaClass.simpleName}") } }
        })

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

    /**
     * The connected tracker's beacon identity, as read from its config on this link. Null
     * until the config has been read (and after the link ends). The callsign may be blank
     * or NOCALL when the tracker has none set; see [trackerDevice].
     */
    private val _tracker = MutableStateFlow<TrackerIdentity?>(null)
    val tracker: StateFlow<TrackerIdentity?> = _tracker.asStateFlow()

    /** The device the logger records as: the saved override, else the connected tracker's own. */
    fun effectiveDevice(s: LoggerSettings = settings, tracker: TrackerIdentity? = _tracker.value): String =
        s.device.trim().ifEmpty { trackerDevice(tracker) }

    init {
        scope.launch { for (rows in inserts) insertWithRetry(rows) }
        // Re-evaluate the session whenever a setting that affects recording changes.
        scope.launch {
            settingsStore.settings
                .combine(_tracker) { s, t -> Triple(s.enabled, effectiveDevice(s, t), s.uploadTx to s.uploadRx) }
                .distinctUntilChanged()
                .collect { restartSession() }
        }
        // A "device not approved" block is about one device: a different tracker gets its own try.
        scope.launch {
            settingsStore.settings
                .combine(_tracker) { s, t -> effectiveDevice(s, t) }
                .distinctUntilChanged()
                .collect { device ->
                    val blocked = settingsStore.status.value.blockedMessage
                    if (device.isNotEmpty() && blocked != null && "(404)" in blocked) {
                        settingsStore.updateStatus { it.copy(blockedMessage = null) }
                        scheduleUpload()
                    }
                }
        }
        scope.launch {
            var wasOn = false
            settingsStore.settings.map { it.enabled }.distinctUntilChanged().collect { on ->
                if (on) scheduleUpload()
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
        // A new link may be a different tracker (or a reflashed one): its identity is
        // unknown until the ViewModel reads its config and calls [setTracker].
        _tracker.value = null
        _runtime.update { it.copy(reconnecting = false) }
        restartSession()
    }

    /**
     * The session ended. [reconnecting]: the app will try to get the link back, so the
     * foreground service (and its notification) stays up meanwhile.
     */
    fun detach(reconnecting: Boolean = false) {
        transport = null
        _tracker.value = null
        _runtime.update { it.copy(reconnecting = reconnecting && isEnabled) }
        restartSession()
    }

    fun setReconnecting(on: Boolean) {
        _runtime.update { it.copy(reconnecting = on && isEnabled) }
        updateService()
    }

    /**
     * The connected tracker's beacon callsign and tactical (object) name, read from its
     * config on connect and after each save. Used as the device when the device field
     * is left blank.
     */
    fun setTracker(beacon: BeaconConfig?) {
        _tracker.value = TrackerIdentity(beacon?.callsign.orEmpty(), beacon?.tacticalCallsign.orEmpty())
    }

    /**
     * Queues a device note, if notes are on and [trackerCallsign] (the connected tracker)
     * is the logger's device. Never pass secrets in [text].
     */
    fun recordEvent(trackerCallsign: String, text: String) {
        val s = settings
        if (!s.enabled || !s.recordSettingNotes) return
        val device = effectiveDevice(s)
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
        val tracker = _tracker.value
        val device = effectiveDevice(s, tracker)
        sessionJob = scope.launch {
            // Non-cancellable wait: if this job is itself replaced while waiting, the next
            // one still waits for it, so an old session's teardown (KISS unsubscribe)
            // can never run after a newer session subscribed.
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            if (link == null || !s.enabled) {
                _runtime.update { it.copy(logging = false, linkError = null) }
                updateService()
                return@launch
            }
            if (device.isEmpty()) {
                val why = if (tracker == null) "Reading the tracker's callsign…"
                    else "The tracker has no callsign set. Set one in its config, or enter a device callsign here"
                _runtime.update { it.copy(logging = false, linkError = why) }
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
                _runtime.update { it.copy(logging = false, linkError = e.message ?: "Couldn't subscribe to KISS") }
                updateService()
                return
            }
            _runtime.update { it.copy(logging = true, device = device, sent = 0, heard = 0, linkError = null) }
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
            _runtime.update { it.copy(logging = false) }
            updateService()
            if (settings.enabled) scheduleUpload()
        }
    }

    private suspend fun insertWithRetry(rows: List<LogQueueRow>) {
        var wait = 200L
        repeat(INSERT_ATTEMPTS) { attempt ->
            try {
                dao.insert(rows)
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (attempt == INSERT_ATTEMPTS - 1) {
                    Log.w(TAG, "queue insert failed; ${rows.size} entries lost", e)
                    settingsStore.updateStatus { it.copy(lastError = "Couldn't save ${rows.size} log entries: ${e.message ?: e.javaClass.simpleName}") }
                    return
                }
                delay(wait)
                wait *= 4
            }
        }
    }

    private fun queue(entries: List<CoverageEntry>, device: String) {
        if (entries.isEmpty()) return
        val tx = entries.count { it.type == CoverageEntryType.TX }
        val rx = entries.count { it.type == CoverageEntryType.RX }
        _runtime.update { it.copy(sent = it.sent + tx, heard = it.heard + rx) }
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Database or encoding trouble: nothing was deleted, so try again later.
            Log.w(TAG, "upload failed", e)
            UploadResult.Retry("Upload failed: ${e.message ?: e.javaClass.simpleName}")
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
        // VALIDATED: Android has confirmed real internet, not a Wi-Fi sign-in page.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // -------------------------------------------------------------------------
    // Foreground service
    // -------------------------------------------------------------------------

    /**
     * The service runs while the logger is on and a tracker is connected (or being
     * reconnected). It stays up across a session restart on the same link (reading the
     * callsign, a settings change): Android 12+ won't let it start again from the background.
     */
    val serviceWanted: Boolean
        get() = isEnabled && (transport != null || _runtime.value.logging || _runtime.value.reconnecting)

    /** We asked the service to stop and it may not have finished ([LoggerService.running] still true). */
    @Volatile private var stopRequested = false

    @Synchronized
    private fun updateService() {
        if (serviceWanted) {
            if (!LoggerService.running || stopRequested) {
                stopRequested = false
                try {
                    ContextCompat.startForegroundService(app, Intent(app, LoggerService::class.java))
                } catch (e: Exception) {
                    // Android 12+ refuses to start a foreground service from the background.
                    // Logging still runs while the app is alive; the next foreground start retries.
                    Log.w(TAG, "couldn't start logger service: ${e.javaClass.simpleName}")
                }
            }
        } else if (LoggerService.running && !stopRequested) {
            stopRequested = true
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

/** What the connected tracker beacons as: its callsign and, in object mode, the object name. */
data class TrackerIdentity(val callsign: String, val objectName: String = "")

/**
 * A tracker's identity as a logger device: "K7ABC-9" (or "K7ABC" with no SSID), or
 * "K7ABC-9:OBJECT" when it beacons as an object (tactical callsign set). "" when it has
 * no callsign (blank or NOCALL).
 * The object name keeps its case, as APRS object names are case-sensitive.
 */
internal fun trackerDevice(tracker: TrackerIdentity?): String {
    // SSID 0 is never sent over the air ("K7ABC-0" goes out as "K7ABC"), so drop it.
    val call = tracker?.callsign?.trim()?.uppercase().orEmpty().removeSuffix("-0")
    if (call.isEmpty() || call.startsWith("NOCALL")) return ""
    val obj = tracker?.objectName?.trim().orEmpty().take(9)
    return if (obj.isEmpty()) call else "$call:$obj"
}
