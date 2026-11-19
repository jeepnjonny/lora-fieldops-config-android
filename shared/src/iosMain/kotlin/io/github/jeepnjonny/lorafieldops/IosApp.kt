package io.github.jeepnjonny.lorafieldops

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import io.github.jeepnjonny.lorafieldops.logger.CoverageLogger
import io.github.jeepnjonny.lorafieldops.logger.HttpPoster
import io.github.jeepnjonny.lorafieldops.logger.LogQueueStore
import io.github.jeepnjonny.lorafieldops.logger.LoggerPlatform
import io.github.jeepnjonny.lorafieldops.logger.SettingsBackend
import io.github.jeepnjonny.lorafieldops.logger.UploadResult
import io.github.jeepnjonny.lorafieldops.platform.AppPlatform
import io.github.jeepnjonny.lorafieldops.platform.BleCentral
import io.github.jeepnjonny.lorafieldops.platform.BleLink
import io.github.jeepnjonny.lorafieldops.platform.BleScanner
import io.github.jeepnjonny.lorafieldops.platform.BleTrackerInfo
import io.github.jeepnjonny.lorafieldops.platform.FileLogQueue
import io.github.jeepnjonny.lorafieldops.platform.IosBleLink
import io.github.jeepnjonny.lorafieldops.platform.IosBleScanner
import io.github.jeepnjonny.lorafieldops.platform.IosHttpPoster
import io.github.jeepnjonny.lorafieldops.platform.IosLocationSource
import io.github.jeepnjonny.lorafieldops.platform.IosSettingsBackend
import io.github.jeepnjonny.lorafieldops.platform.LocationSource
import io.github.jeepnjonny.lorafieldops.platform.UsbSupport
import io.github.jeepnjonny.lorafieldops.platform.presentShareSheet
import io.github.jeepnjonny.lorafieldops.ui.ConfigViewModel
import io.github.jeepnjonny.lorafieldops.ui.LoRaFieldOpsApp
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
