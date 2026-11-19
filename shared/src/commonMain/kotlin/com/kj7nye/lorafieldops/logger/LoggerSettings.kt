package com.kj7nye.lorafieldops.logger

import com.kj7nye.lorafieldops.platform.SyncLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

const val DEFAULT_COVERAGE_BASE_URL = "https://apps.k7swi.org/aprs-coverage"

/** User settings for the coverage logger. Everything is off by default. */
data class LoggerSettings(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_COVERAGE_BASE_URL,
    val apiKey: String = "",
    /**
     * Device override: "K7ABC-9", or "SENDER:OBJECT" for an object tracker. Free text by
     * design. Blank (the default) means the connected tracker's own: its callsign,
     * or "CALLSIGN:TACTICAL" in object mode (see [trackerDevice]).
     */
    val device: String = "",
    val uploadTx: Boolean = true,
    val uploadRx: Boolean = true,
    val recordSettingNotes: Boolean = false,
) {
    /** Never print the key, even in a debug string. */
    override fun toString(): String =
        "LoggerSettings(enabled=$enabled, baseUrl=$baseUrl, apiKey=${if (apiKey.isEmpty()) "<none>" else "<set>"}, " +
        "device=$device, uploadTx=$uploadTx, uploadRx=$uploadRx, recordSettingNotes=$recordSettingNotes)"
}

/** Upload outcome shown on the logger screen. Persisted so it survives restarts. */
data class UploadStatus(
    val lastUploadMs: Long = 0L,
    val lastError: String? = null,
    /** Set on 401/404; uploads stay stopped until a setting changes. */
    val blockedMessage: String? = null,
    /** Per-entry errors from the last successful batch(es). */
    val entryErrors: List<String> = emptyList(),
)

/** Plain (unencrypted) app preferences: SharedPreferences on Android, NSUserDefaults on iOS. */
interface SettingsBackend {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getLong(key: String, default: Long): Long
    fun getString(key: String): String?
    /** Applies every write in [block] together. */
    fun edit(block: SettingsEditor.() -> Unit)
    /**
     * Opens the encrypted store for secrets (Android keystore, iOS Keychain); null when it
     * can't be used on this phone. Called once, on first use.
     */
    fun openSecrets(): SecretStore?
}

interface SettingsEditor {
    fun putBoolean(key: String, value: Boolean)
    fun putLong(key: String, value: Long)
    fun putString(key: String, value: String?)
}

interface SecretStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/**
 * Stores [LoggerSettings] in plain preferences, except the upload key, which goes in
 * the platform's encrypted store. Exposes both settings and [UploadStatus] as flows.
 */
class LoggerSettingsStore(private val prefs: SettingsBackend) {
    private val lock = SyncLock()
    /**
     * Opened on first use only, so a phone that never turns the logger on never touches
     * the keystore (keeps app start exactly as before).
     */
    private val secure: SecretStore? by lazy { prefs.openSecrets() }
    /** Fallback when the keystore is unusable: the key lives only until the app stops. */
    @Volatile private var memoryKey: String = ""

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<LoggerSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    /** False when the phone's secure storage couldn't be used and the key isn't saved across restarts. */
    val keyPersisted: Boolean get() = secure != null

    /**
     * Applies [change]. Any change to settings clears a 401/404 block, since the user
     * may just have fixed the key or the device.
     */
    fun update(change: (LoggerSettings) -> LoggerSettings): Unit = lock.withLock {
        val old = _settings.value
        val new = change(old)
        if (new == old) return@withLock
        prefs.edit {
            putBoolean(K_ENABLED, new.enabled)
            putString(K_BASE_URL, new.baseUrl)
            putString(K_DEVICE, new.device)
            putBoolean(K_UPLOAD_TX, new.uploadTx)
            putBoolean(K_UPLOAD_RX, new.uploadRx)
            putBoolean(K_NOTES, new.recordSettingNotes)
        }
        if (new.apiKey != old.apiKey) {
            val sp = secure
            if (sp != null) sp.putString(K_API_KEY, new.apiKey) else memoryKey = new.apiKey
            prefs.edit { putBoolean(K_HAS_KEY, new.apiKey.isNotEmpty()) }
        }
        _settings.value = new
        if (_status.value.blockedMessage != null) updateStatus { it.copy(blockedMessage = null) }
    }

    fun updateStatus(change: (UploadStatus) -> UploadStatus): Unit = lock.withLock {
        val s = change(_status.value)
        prefs.edit {
            putLong(K_LAST_UPLOAD, s.lastUploadMs)
            putString(K_LAST_ERROR, s.lastError)
            putString(K_BLOCKED, s.blockedMessage)
            putString(K_ENTRY_ERRORS, s.entryErrors.joinToString(ERROR_SEP))
        }
        _status.value = s
    }

    private fun readSettings() = LoggerSettings(
        enabled = prefs.getBoolean(K_ENABLED, false),
        baseUrl = prefs.getString(K_BASE_URL) ?: DEFAULT_COVERAGE_BASE_URL,
        apiKey = if (prefs.getBoolean(K_HAS_KEY, false)) {
            runCatching { secure?.getString(K_API_KEY) }.getOrNull() ?: memoryKey
        } else memoryKey,
        device = prefs.getString(K_DEVICE) ?: "",
        uploadTx = prefs.getBoolean(K_UPLOAD_TX, true),
        uploadRx = prefs.getBoolean(K_UPLOAD_RX, true),
        recordSettingNotes = prefs.getBoolean(K_NOTES, false),
    )

    private fun readStatus() = UploadStatus(
        lastUploadMs = prefs.getLong(K_LAST_UPLOAD, 0L),
        lastError = prefs.getString(K_LAST_ERROR),
        blockedMessage = prefs.getString(K_BLOCKED),
        entryErrors = prefs.getString(K_ENTRY_ERRORS)?.split(ERROR_SEP)?.filter { it.isNotEmpty() } ?: emptyList(),
    )

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_BASE_URL = "base_url"
        const val K_API_KEY = "api_key"
        const val K_HAS_KEY = "has_api_key"
        const val K_DEVICE = "device"
        const val K_UPLOAD_TX = "upload_tx"
        const val K_UPLOAD_RX = "upload_rx"
        const val K_NOTES = "record_setting_notes"
        const val K_LAST_UPLOAD = "last_upload_ms"
        const val K_LAST_ERROR = "last_error"
        const val K_BLOCKED = "blocked_message"
        const val K_ENTRY_ERRORS = "entry_errors"
        const val ERROR_SEP = "\u001F"
    }
}
