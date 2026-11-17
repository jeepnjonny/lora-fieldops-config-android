package com.kj7nye.lorafieldops.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kj7nye.lorafieldops.logger.CoverageLogger
import com.kj7nye.lorafieldops.logger.LoggerRuntime
import com.kj7nye.lorafieldops.logger.LoggerSettings
import com.kj7nye.lorafieldops.logger.UploadStatus
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

    fun setEnabled(v: Boolean) = logger.settingsStore.update { it.copy(enabled = v) }
    fun setUploadTx(v: Boolean) = logger.settingsStore.update { it.copy(uploadTx = v) }
    fun setUploadRx(v: Boolean) = logger.settingsStore.update { it.copy(uploadRx = v) }
    fun setRecordNotes(v: Boolean) = logger.settingsStore.update { it.copy(recordSettingNotes = v) }

    /** Text fields are applied together with an explicit Save, not per keystroke. */
    fun saveFields(baseUrl: String, apiKey: String, device: String) = logger.settingsStore.update {
        it.copy(
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            device = normalizeDeviceField(device),
        )
    }

    fun uploadNow() = logger.uploadNow()
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
