package io.github.jeepnjonny.lorafieldops

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import io.github.jeepnjonny.lorafieldops.logger.CoverageLogger
import io.github.jeepnjonny.lorafieldops.logger.HttpPoster
import io.github.jeepnjonny.lorafieldops.logger.LogQueueDb
import io.github.jeepnjonny.lorafieldops.logger.LogQueueStore
import io.github.jeepnjonny.lorafieldops.logger.LoggerPlatform
import io.github.jeepnjonny.lorafieldops.logger.LoggerService
import io.github.jeepnjonny.lorafieldops.logger.RoomUploadQueue
import io.github.jeepnjonny.lorafieldops.logger.SecretStore
import io.github.jeepnjonny.lorafieldops.logger.SettingsBackend
import io.github.jeepnjonny.lorafieldops.logger.SettingsEditor
import io.github.jeepnjonny.lorafieldops.logger.UploadWorker
import io.github.jeepnjonny.lorafieldops.logger.UrlConnectionPoster
import io.github.jeepnjonny.lorafieldops.platform.AppPlatform
import io.github.jeepnjonny.lorafieldops.platform.BleLink
import io.github.jeepnjonny.lorafieldops.platform.BleTrackerInfo
import io.github.jeepnjonny.lorafieldops.platform.LocationResult
import io.github.jeepnjonny.lorafieldops.platform.LocationSource
import io.github.jeepnjonny.lorafieldops.platform.UsbDeviceInfo
import io.github.jeepnjonny.lorafieldops.platform.UsbSupport
import io.github.jeepnjonny.lorafieldops.serial.BleScanner
import io.github.jeepnjonny.lorafieldops.serial.BleTransport
import io.github.jeepnjonny.lorafieldops.serial.DeviceTransport
import io.github.jeepnjonny.lorafieldops.serial.SerialManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "CoverageLogger"
private const val UPLOAD_WORK = "coverage-upload"

/** The Android side of the shared app: one per process ([get]). */
class AndroidPlatform private constructor(context: Context) : AppPlatform {
    private val app = context.applicationContext

    override val osName = "Android"
    override val bleScanner = BleScanner(app)
    val usbSupport = AndroidUsbSupport(app)
    override val usb: UsbSupport = usbSupport
    override val location: LocationSource = AndroidLocationSource(app)
    override val coverageLogger = CoverageLogger(AndroidLoggerPlatform(app))

    override fun bleLink(tracker: BleTrackerInfo): BleLink =
        BleTransport(app, tracker.handle as BluetoothDevice, tracker.name)

    override fun shareText(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        app.startActivity(Intent.createChooser(send, "Share config").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        @Volatile private var instance: AndroidPlatform? = null

        fun get(context: Context): AndroidPlatform = instance ?: synchronized(this) {
            instance ?: AndroidPlatform(context).also { instance = it }
        }
    }
}

/** USB serial through usb-serial-for-android ([SerialManager]). */
class AndroidUsbSupport(context: Context) : UsbSupport {
    private val serial = SerialManager(context)
    override val transport: DeviceTransport = serial

    override fun findDevices(): List<UsbDeviceInfo> = serial.findDevices().map { it.toInfo() }

    /** The device list entry for [device], e.g. for a USB-attached launch intent. */
    fun infoFor(device: UsbDevice): UsbDeviceInfo? =
        serial.findDevices().firstOrNull { it.device == device }?.toInfo()

    override fun requestPermission(device: UsbDeviceInfo, onResult: (granted: Boolean) -> Unit) =
        serial.requestPermission((device.handle as UsbSerialDriver).device, onResult)

    override fun open(device: UsbDeviceInfo) = serial.open(device.handle as UsbSerialDriver)

    private fun UsbSerialDriver.toInfo() = UsbDeviceInfo(
        title = device.productName ?: "USB Device",
        subtitle = "VID:${device.vendorId.toString(16).uppercase()} PID:${device.productId.toString(16).uppercase()}",
        handle = this,
    )
}

/** One-shot fix from [LocationManager]: no Play Services dependency. */
class AndroidLocationSource(context: Context) : LocationSource {
    private val app = context.applicationContext

    @SuppressLint("MissingPermission")   // the UI asks for location permission first
    override suspend fun currentFix(): LocationResult {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return LocationResult.Failed("Enable device location (GPS) to use this")
        }
        return suspendCancellableCoroutine { cont ->
            lateinit var listener: LocationListener
            listener = LocationListener { loc: Location ->
                lm.removeUpdates(listener)
                if (cont.isActive) cont.resume(
                    LocationResult.Fix(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = if (loc.hasAltitude()) loc.altitude else null,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                    )
                )
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
    }
}

/** Room queue, WorkManager background upload and the foreground service. */
private class AndroidLoggerPlatform(context: Context) : LoggerPlatform {
    private val app = context.applicationContext

    override val queue: LogQueueStore = RoomUploadQueue(LogQueueDb.get(app).dao())
    override val http: HttpPoster = UrlConnectionPoster()
    override val appId = "lora-fieldops-config-android/${BuildConfig.VERSION_NAME}"
    override val settingsBackend: SettingsBackend = AndroidSettingsBackend(app)

    override fun isOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // VALIDATED: Android has confirmed real internet, not a Wi-Fi sign-in page.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Hands the queue to WorkManager: runs when online, retries with exponential backoff. */
    override fun scheduleBackgroundUpload() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelBackgroundUpload() {
        WorkManager.getInstance(app).cancelUniqueWork(UPLOAD_WORK)
    }

    /** We asked the service to stop and it may not have finished ([LoggerService.running] still true). */
    @Volatile private var stopRequested = false

    /**
     * The foreground service runs while the logger wants to keep the app alive. Called
     * under the logger's lock, so start/stop decisions don't interleave.
     */
    override fun setKeepAlive(wanted: Boolean) {
        if (wanted) {
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

    override fun logWarning(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }
}

/**
 * Plain SharedPreferences, plus EncryptedSharedPreferences for the upload key. The file
 * names are unchanged from earlier versions, so existing settings carry over.
 */
private class AndroidSettingsBackend(context: Context) : SettingsBackend {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun edit(block: SettingsEditor.() -> Unit) = prefs.edit {
        val editor = this
        object : SettingsEditor {
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putString(key: String, value: String?) { editor.putString(key, value) }
        }.block()
    }

    override fun openSecrets(): SecretStore? = openEncrypted(app)?.let { sp ->
        object : SecretStore {
            override fun getString(key: String): String? = sp.getString(key, null)
            override fun putString(key: String, value: String) = sp.edit { putString(key, value) }
        }
    }

    private companion object {
        const val PREFS = "coverage_logger"
        const val SECURE_PREFS = "coverage_logger_secure"

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
