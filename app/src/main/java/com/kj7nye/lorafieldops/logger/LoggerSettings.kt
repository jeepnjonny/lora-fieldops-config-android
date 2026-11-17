package com.kj7nye.lorafieldops.logger

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_COVERAGE_BASE_URL = "https://apps.k7swi.org/aprs-coverage"

/** User settings for the coverage logger. Everything is off by default. */
data class LoggerSettings(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_COVERAGE_BASE_URL,
    val apiKey: String = "",
    /** "K7ABC-9", or "SENDER:OBJECT" for an object tracker. Free text by design. */
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

/**
 * Stores [LoggerSettings] in plain SharedPreferences, except the upload key, which goes
 * in EncryptedSharedPreferences. Exposes both settings and [UploadStatus] as flows.
 */
class LoggerSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    /**
     * Opened on first use only, so a phone that never turns the logger on never touches
     * the keystore (keeps app start exactly as before).
     */
    private val secure: SharedPreferences? by lazy { openEncrypted(appContext) }
    /** Fallback when the keystore is unusable: the key lives only until the app stops. */
    @Volatile private var memoryKey: String = ""

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<LoggerSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    /** False when Android's keystore couldn't be used and the key isn't saved across restarts. */
    val keyPersisted: Boolean get() = secure != null

    /**
     * Applies [change]. Any change to settings clears a 401/404 block, since the user
     * may just have fixed the key or the device.
     */
    @Synchronized
    fun update(change: (LoggerSettings) -> LoggerSettings) {
        val old = _settings.value
        val new = change(old)
        if (new == old) return
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
            if (sp != null) sp.edit { putString(K_API_KEY, new.apiKey) } else memoryKey = new.apiKey
            prefs.edit { putBoolean(K_HAS_KEY, new.apiKey.isNotEmpty()) }
        }
        _settings.value = new
        if (_status.value.blockedMessage != null) updateStatus { it.copy(blockedMessage = null) }
    }

    @Synchronized
    fun updateStatus(change: (UploadStatus) -> UploadStatus) {
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
        baseUrl = prefs.getString(K_BASE_URL, null) ?: DEFAULT_COVERAGE_BASE_URL,
        apiKey = if (prefs.getBoolean(K_HAS_KEY, false)) {
            runCatching { secure?.getString(K_API_KEY, null) }.getOrNull() ?: memoryKey
        } else memoryKey,
        device = prefs.getString(K_DEVICE, null) ?: "",
        uploadTx = prefs.getBoolean(K_UPLOAD_TX, true),
        uploadRx = prefs.getBoolean(K_UPLOAD_RX, true),
        recordSettingNotes = prefs.getBoolean(K_NOTES, false),
    )

    private fun readStatus() = UploadStatus(
        lastUploadMs = prefs.getLong(K_LAST_UPLOAD, 0L),
        lastError = prefs.getString(K_LAST_ERROR, null),
        blockedMessage = prefs.getString(K_BLOCKED, null),
        entryErrors = prefs.getString(K_ENTRY_ERRORS, null)?.split(ERROR_SEP)?.filter { it.isNotEmpty() } ?: emptyList(),
    )

    private companion object {
        const val PREFS = "coverage_logger"
        const val SECURE_PREFS = "coverage_logger_secure"
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

        /**
         * The encrypted file can't be read after a restore to another phone (its keystore
         * key doesn't travel), so on failure it is deleted and recreated once. If even that
         * fails, the key is held in memory only.
         */
        @Suppress("DEPRECATION")   // security-crypto is deprecated but still the documented way to do this
        fun openEncrypted(context: Context): SharedPreferences? {
            fun open(): SharedPreferences {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context, SECURE_PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { it.all }   // force a read so a corrupt file fails here, not later
            }
            return runCatching { open() }.recoverCatching {
                context.deleteSharedPreferences(SECURE_PREFS)
                open()
            }.getOrNull()
        }
    }
}
