package com.kj7nye.lorafieldops

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.kj7nye.lorafieldops.logger.CoverageLogger
import com.kj7nye.lorafieldops.logger.HttpPoster
import com.kj7nye.lorafieldops.logger.LogQueueStore
import com.kj7nye.lorafieldops.logger.LoggerPlatform
import com.kj7nye.lorafieldops.logger.SettingsBackend
import com.kj7nye.lorafieldops.logger.UploadResult
import com.kj7nye.lorafieldops.platform.AppPlatform
import com.kj7nye.lorafieldops.platform.BleCentral
import com.kj7nye.lorafieldops.platform.BleLink
import com.kj7nye.lorafieldops.platform.BleScanner
import com.kj7nye.lorafieldops.platform.BleTrackerInfo
import com.kj7nye.lorafieldops.platform.FileLogQueue
import com.kj7nye.lorafieldops.platform.IosBleLink
import com.kj7nye.lorafieldops.platform.IosBleScanner
import com.kj7nye.lorafieldops.platform.IosHttpPoster
import com.kj7nye.lorafieldops.platform.IosLocationSource
import com.kj7nye.lorafieldops.platform.IosSettingsBackend
import com.kj7nye.lorafieldops.platform.LocationSource
import com.kj7nye.lorafieldops.platform.UsbSupport
import com.kj7nye.lorafieldops.platform.presentShareSheet
import com.kj7nye.lorafieldops.ui.ConfigViewModel
import com.kj7nye.lorafieldops.ui.LoRaFieldOpsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/** Entry point for the Swift app shell (iosApp/): the whole app as one view controller. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val vm = remember { ConfigViewModel(IosPlatform) }
    LoRaFieldOpsApp(vm)
}

/** The iOS side of the shared app. iPhones have no USB serial access, so [usb] is null. */
object IosPlatform : AppPlatform {
    private val central = BleCentral()

    override val osName = "iOS"
    override val bleScanner: BleScanner = IosBleScanner(central)
    override val usb: UsbSupport? = null
    override val location: LocationSource = IosLocationSource()
    private val loggerPlatform = IosLoggerPlatform()
    override val coverageLogger = CoverageLogger(loggerPlatform).also { loggerPlatform.logger = it }

    override fun bleLink(tracker: BleTrackerInfo): BleLink =
        IosBleLink(central, tracker.handle as CBPeripheral, tracker.name)

    override fun shareText(text: String, subject: String) = presentShareSheet(text)
}

private const val RETRY_FIRST_MS = 30_000L
private const val RETRY_MAX_MS = 15 * 60_000L

/**
 * iOS has no WorkManager: a pending upload is retried in-process with backoff while the
 * app runs. While a tracker is connected the app keeps running in the background
 * (Bluetooth background mode) and the logger also uploads every minute.
 */
private class IosLoggerPlatform : LoggerPlatform {
    lateinit var logger: CoverageLogger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var retryJob: Job? = null

    override val queue: LogQueueStore = FileLogQueue()
    override val http: HttpPoster = IosHttpPoster()
    override val appId = "lora-fieldops-config-ios/" +
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "dev")
    override val settingsBackend: SettingsBackend = IosSettingsBackend()

    /** Network trouble shows up as a failed request, which is retried; no need to probe first. */
    override fun isOnline(): Boolean = true

    override fun scheduleBackgroundUpload() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var wait = 0L
            while (true) {
                delay(wait)
                if (logger.runUpload() !is UploadResult.Retry) break
                wait = if (wait == 0L) RETRY_FIRST_MS else (wait * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    override fun cancelBackgroundUpload() {
        retryJob?.cancel()
        retryJob = null
    }

    /** Nothing to start: the Bluetooth background mode keeps a connected app running. */
    override fun setKeepAlive(wanted: Boolean) = Unit

    override fun logWarning(message: String, error: Throwable?) {
        println("CoverageLogger: $message${error?.let { " ($it)" } ?: ""}")
    }
}
