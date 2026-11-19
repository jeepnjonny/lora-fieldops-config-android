package io.github.jeepnjonny.lorafieldops.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jeepnjonny.lorafieldops.logger.CoverageLogger
import io.github.jeepnjonny.lorafieldops.logger.LoggerRuntime
import io.github.jeepnjonny.lorafieldops.logger.LoggerSettings
import io.github.jeepnjonny.lorafieldops.logger.TrackerIdentity
import io.github.jeepnjonny.lorafieldops.logger.UploadStatus
import io.github.jeepnjonny.lorafieldops.logger.DEFAULT_COVERAGE_BASE_URL
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** State and actions for [CoverageLoggerScreen]; a thin view over [CoverageLogger]. */
class LoggerViewModel(private val logger: CoverageLogger) : ViewModel() {

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
    val uri = parseWebAddress(t) ?: return "Not a valid web address"
    if (!uri.scheme.equals("https", ignoreCase = true)) return "Must start with https://"
    if (uri.host.isEmpty()) return "Not a valid web address"
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
        return "Use just the site address, e.g. $DEFAULT_COVERAGE_BASE_URL"
    }
    return null
}

/** True when [new] points at a different host than [old] (so a saved key shouldn't follow it). */
internal fun hostChanged(old: String, new: String): Boolean {
    fun host(u: String) = parseWebAddress(u.trim())?.host?.takeIf { it.isNotEmpty() }?.lowercase()
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

/** The parts of a web address the logger checks. [scheme] and [host] are "" when absent. */
internal class WebAddress(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val query: String?,
    val fragment: String?,
)

private val URL_RE = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(?:([^@/?#]*)@)?(\[[^\]]*\]|[^:/?#]*)(?::\d*)?[^?#]*(?:\?([^#]*))?(?:#(.*))?$""")
private val SCHEME_RE = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")
private const val URL_CHARS = "-._~:/?#[]@!$&'()*+,;=%"

/**
 * Splits [s] like java.net.URI would for an absolute web address; null when it holds a
 * character a URI can't (spaces, quotes, braces, ...).
 */
internal fun parseWebAddress(s: String): WebAddress? {
    if (s.any { !(it.isLetterOrDigit() || it in URL_CHARS || it.code > 0x7F) || it.isWhitespace() }) return null
    URL_RE.find(s)?.let { m ->
        fun group(i: Int) = m.groups[i]?.value
        return WebAddress(m.groupValues[1], group(2), m.groupValues[3], group(4), group(5))
    }
    // "https:foo" (no //) has a scheme but no host; "example.org/x" has neither.
    val scheme = SCHEME_RE.find(s)?.groupValues?.get(1).orEmpty()
    return WebAddress(scheme, null, "", null, null)
}
