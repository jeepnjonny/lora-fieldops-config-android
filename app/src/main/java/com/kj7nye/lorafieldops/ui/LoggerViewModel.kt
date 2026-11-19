package com.kj7nye.lorafieldops.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kj7nye.lorafieldops.logger.CoverageLogger
import com.kj7nye.lorafieldops.logger.LoggerRuntime
import com.kj7nye.lorafieldops.logger.LoggerSettings
import com.kj7nye.lorafieldops.logger.TrackerIdentity
import com.kj7nye.lorafieldops.logger.UploadStatus
import com.kj7nye.lorafieldops.logger.DEFAULT_COVERAGE_BASE_URL
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** State and actions for [CoverageLoggerScreen]; a thin view over [CoverageLogger]. */
class LoggerViewModel(app: Application) : AndroidViewModel(app) {
    private val logger = CoverageLogger.get(app)

    val settings: StateFlow<LoggerSettings> = logger.settingsStore.settings
    val status: StateFlow<UploadStatus> = logger.settingsStore.status
    val runtime: StateFlow<LoggerRuntime> = logger.runtime
    val uploading: StateFlow<Boolean> = logger.uploading
    val queued: StateFlow<Int> = logger.queuedCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val keyPersisted: Boolean get() = logger.settingsStore.keyPersisted
    /** The connected tracker's identity, used when the device field is blank; null until read. */
    val tracker: StateFlow<TrackerIdentity?> = logger.tracker

    fun setEnabled(v: Boolean) = logger.settingsStore.update { it.copy(enabled = v) }
    fun setUploadTx(v: Boolean) = logger.settingsStore.update { it.copy(uploadTx = v) }
    fun setUploadRx(v: Boolean) = logger.settingsStore.update { it.copy(uploadRx = v) }
    fun setRecordNotes(v: Boolean) = logger.settingsStore.update { it.copy(recordSettingNotes = v) }

    /**
     * Text fields are applied together with an explicit Save, not per keystroke. The
     * caller has checked [baseUrlError]. A key is only ever sent to the site it was
     * entered for: changing the website's host without also changing the key clears it.
     */
    fun saveFields(baseUrl: String, apiKey: String, device: String) = logger.settingsStore.update {
        val url = baseUrl.trim().trimEnd('/')
        val key = apiKey.trim()
        it.copy(
            baseUrl = url,
            apiKey = if (hostChanged(it.baseUrl, url) && key == it.apiKey) "" else key,
            device = normalizeDeviceField(device),
        )
    }

    fun uploadNow() = logger.uploadNow()
}

/** Why [url] can't be used as the website address, or null if it's fine. */
internal fun baseUrlError(url: String): String? {
    val t = url.trim()
    if (t.isEmpty()) return "Enter the website address"
    val uri = try { URI(t) } catch (e: URISyntaxException) { return "Not a valid web address" }
    if (!uri.scheme.equals("https", ignoreCase = true)) return "Must start with https://"
    if (uri.host.isNullOrEmpty()) return "Not a valid web address"
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
        return "Use just the site address, e.g. $DEFAULT_COVERAGE_BASE_URL"
    }
    return null
}

/** True when [new] points at a different host than [old] (so a saved key shouldn't follow it). */
internal fun hostChanged(old: String, new: String): Boolean {
    fun host(u: String) = runCatching { URI(u.trim()).host?.lowercase() }.getOrNull()
    return host(old) != host(new)
}

/**
 * Callsigns are upper case; an object name (after "SENDER:") keeps its case, since
 * APRS object names are case-sensitive.
 */
internal fun normalizeDeviceField(device: String): String {
    val t = device.trim()
    val colon = t.indexOf(':')
    return if (colon < 0) t.uppercase() else t.substring(0, colon).trim().uppercase() + ":" + t.substring(colon + 1).trim()
}
