package io.github.jeepnjonny.lorafieldops.firmware

import io.github.jeepnjonny.lorafieldops.model.FirmwareVersion
import io.github.jeepnjonny.lorafieldops.platform.BleLink
import io.github.jeepnjonny.lorafieldops.platform.BleTrackerInfo
import io.github.jeepnjonny.lorafieldops.platform.FirmwarePlatform
import io.github.jeepnjonny.lorafieldops.platform.PickedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** What the updater needs from the setup session (implemented by the config view model). */
interface UpdateSession {
    /** The open Bluetooth setup link, or null when not connected over Bluetooth. */
    fun currentBleLink(): BleLink?
    /** Board id from `version.board`; null until read or on firmware too old to report it. */
    fun currentBoard(): String?
    /** Version text from `version.date`. */
    fun currentVersion(): String?
    /**
     * Ends the setup session without closing the Bluetooth link, and hands the link
     * and its tracker to the updater, which then owns (and closes) it.
     */
    fun takeBleLinkForUpdate(): Pair<BleLink, BleTrackerInfo>?
    /** Opens a new setup session to [tracker]; returns what `version` reported, or null if it failed. */
    suspend fun reconnect(tracker: BleTrackerInfo): TrackerVersionInfo?
    /** The update dialog closed; if no session is open any more, return to the device list. */
    fun updateClosed()
}

/** A firmware file chosen for this tracker and checked by [checkFirmwareFile]. */
class FirmwareImage(
    val support: BoardUpdateSupport.Supported,
    val fileName: String,
    /** Release tag or file name, for messages and START's informational version field. */
    val label: String,
    val bytes: ByteArray,
)

/** The release check shown on the Firmware card. */
sealed interface ReleaseCheck {
    data object Idle : ReleaseCheck
    data object Checking : ReleaseCheck
    /** [newer] is null when either version can't be compared (dev builds). */
    data class Found(val release: FirmwareRelease, val asset: ReleaseAsset, val newer: Boolean?) : ReleaseCheck
    data class Failed(val message: String) : ReleaseCheck
}

/** Steps of an update, shown in the update dialog; null means no dialog. */
sealed interface UpdateStep {
    data class Confirm(val image: FirmwareImage, val warnings: List<String>) : UpdateStep
    data class Downloading(val fraction: Float?) : UpdateStep
    /** [cancellable] is false for the T114: stopping mid-transfer leaves its app area erased. */
    data class Transferring(val fraction: Float, val kbPerSecond: Float?, val cancellable: Boolean) : UpdateStep
    data class Working(val message: String) : UpdateStep
    data class Done(val message: String) : UpdateStep
    /** [canRetryNow]: T114 left in its bootloader; retrying right away re-runs the transfer. */
    data class Failed(val message: String, val canRetryNow: Boolean = false) : UpdateStep
}

/**
 * Firmware updates over Bluetooth for a connected tracker:
 * - ESP32 boards: the `ble_ota.h` protocol ([OtaUploader]) on the bonded setup link.
 * - Heltec T114: buttonless jump to the Adafruit bootloader, then Nordic DFU
 *   ([FirmwarePlatform.runNordicDfu]) with the rules found on hardware.
 */
class FirmwareUpdateController(
    private val scope: CoroutineScope,
    private val platform: FirmwarePlatform,
    private val session: UpdateSession,
    private val releases: ReleaseSource = GitHubReleaseSource({ platform.httpGet(it) }),
) {
    private val _check = MutableStateFlow<ReleaseCheck>(ReleaseCheck.Idle)
    val check: StateFlow<ReleaseCheck> = _check.asStateFlow()

    private val _step = MutableStateFlow<UpdateStep?>(null)
    val step: StateFlow<UpdateStep?> = _step.asStateFlow()

    private var job: Job? = null
    /** T114 retry state: the image and tracker of the transfer that failed. */
    private var dfuRetry: Pair<FirmwareImage, BleTrackerInfo>? = null
    private var dfuFailures = 0

    val isRunning: Boolean get() = job?.isActive == true

    /** Why an update can't start on the current session, or null when it can. */
    fun blocker(board: String?): String? {
        val link = session.currentBleLink()
            ?: return "Connect over Bluetooth to update the firmware. Over USB, use the web flasher."
        return when (val support = BoardUpdateSupport.forBoard(board)) {
            is BoardUpdateSupport.NotSupported -> support.reason
            is BoardUpdateSupport.Supported -> when {
                support.method == UpdateMethod.ESP32_OTA && !link.updateServices.esp32Ota ->
                    "This tracker's firmware has no Bluetooth update service. Update it once over USB " +
                        "with the web flasher; later updates can then go over Bluetooth."
                else -> null
            }
        }
    }

    /** Clears the card's release result, e.g. when another tracker connects. */
    fun resetCheck() { _check.value = ReleaseCheck.Idle }

    fun checkForUpdate() {
        val support = BoardUpdateSupport.forBoard(session.currentBoard()) as? BoardUpdateSupport.Supported ?: return
        if (_check.value == ReleaseCheck.Checking) return
        _check.value = ReleaseCheck.Checking
        scope.launch {
            _check.value = try {
                val release = releases.latest()
                val asset = release.asset(support.assetName)
                if (asset == null) {
                    ReleaseCheck.Failed("Release ${release.tag} has no ${support.assetName}.")
                } else {
                    val current = session.currentVersion()?.let { FirmwareVersion.parse(it) }
                    val latest = release.version
                    ReleaseCheck.Found(release, asset, if (current != null && latest != null) latest > current else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReleaseCheck.Failed("Couldn't check for updates: ${e.message ?: "network error"}")
            }
        }
    }

    /** Downloads the release found by [checkForUpdate], then asks for confirmation. */
    fun downloadRelease() {
        val found = _check.value as? ReleaseCheck.Found ?: return
        if (isRunning) return
        job = scope.launch {
            _step.value = UpdateStep.Downloading(null)
            val bytes = try {
                platform.httpGet(found.asset.url) { got, total ->
                    val size = total ?: found.asset.size.takeIf { it > 0 }
                    _step.value = UpdateStep.Downloading(size?.let { (got.toFloat() / it).coerceIn(0f, 1f) })
                }
            } catch (e: CancellationException) {
                _step.value = null
                throw e
            } catch (e: Exception) {
                _step.value = UpdateStep.Failed("Download failed: ${e.message ?: "network error"}")
                return@launch
            }
            prepare(found.asset.name, found.release.tag, bytes)
        }
    }

    /** A file from the system picker (offline / field use). */
    fun useFile(file: PickedFile) {
        if (isRunning) return
        prepare(file.name, file.name.substringBeforeLast('.'), file.bytes)
    }

    private fun prepare(fileName: String, label: String, bytes: ByteArray) {
        val board = session.currentBoard()
        blocker(board)?.let { _step.value = UpdateStep.Failed(it); return }
        val support = BoardUpdateSupport.forBoard(board) as BoardUpdateSupport.Supported
        checkFirmwareFile(support, fileName, bytes)?.let { _step.value = UpdateStep.Failed(it); return }
        val image = FirmwareImage(support, fileName, label, bytes)
        _step.value = UpdateStep.Confirm(image, warnings(support.method))
    }

    private fun warnings(method: UpdateMethod): List<String> = when (method) {
        UpdateMethod.ESP32_OTA -> listOf(
            "Takes about a minute and a half. Keep the phone near the tracker and this screen open.",
            "The tracker stops beaconing, digipeating and iGating until it restarts.",
            "If anything goes wrong the tracker keeps its current firmware, and you can try again.",
        )
        UpdateMethod.NORDIC_DFU -> listOf(
            "Takes about 5 minutes. Keep the phone near the tracker and this screen open.",
            "The tracker restarts into its update mode and is off the air until it finishes.",
            "If the transfer fails, retry right away. If that fails too, the tracker must be " +
                "recovered over USB.",
        )
    }

    fun confirm() {
        val image = (_step.value as? UpdateStep.Confirm)?.image ?: return
        if (isRunning) return
        job = scope.launch {
            when (image.support.method) {
                UpdateMethod.ESP32_OTA -> runEsp32(image)
                UpdateMethod.NORDIC_DFU -> runT114(image)
            }
        }
    }

    /** T114 only: re-runs the bootloader transfer while the bootloader still advertises. */
    fun retryNow() {
        val (image, tracker) = dfuRetry ?: return
        if (isRunning) return
        job = scope.launch { runDfuTransfer(image, tracker) }
    }

    /** Cancels a download or an ESP32 transfer (the tracker keeps its firmware). Ignored during a T114 transfer. */
    fun cancel() {
        val step = _step.value
        if (step is UpdateStep.Downloading || (step is UpdateStep.Transferring && step.cancellable)) job?.cancel()
    }

    /** Closes the dialog after it reached an end state. */
    fun dismiss() {
        if (isRunning) return
        _step.value = null
        dfuRetry = null
        dfuFailures = 0
        session.updateClosed()
    }

    // -------------------------------------------------------------------------
    // ESP32
    // -------------------------------------------------------------------------

    private suspend fun runEsp32(image: FirmwareImage) {
        val (link, tracker) = session.takeBleLinkForUpdate()
            ?: return fail("The Bluetooth link closed. Reconnect and try again.")
        val ota = link.otaLink
        if (ota == null) {
            link.close()
            return fail("This tracker has no Bluetooth update service.")
        }
        _step.value = UpdateStep.Transferring(0f, null, cancellable = true)
        val started = TimeSource.Monotonic.markNow()
        var outcome: Throwable? = null
        try {
            OtaUploader(ota, image.bytes, Sha256.digest(image.bytes), image.support.board, image.label) { acked, total ->
                val seconds = started.elapsedNow().inWholeMilliseconds / 1000f
                _step.value = UpdateStep.Transferring(
                    fraction = acked.toFloat() / total,
                    kbPerSecond = if (seconds > 1f) acked / 1024f / seconds else null,
                    cancellable = true,
                )
            }.run()
            _step.value = UpdateStep.Working("Installed. The tracker is restarting…")
        } catch (e: Throwable) {
            outcome = e
        } finally {
            link.close()
        }
        when (val e = outcome) {
            null -> confirmAfterReboot(tracker, image)
            is CancellationException -> withContext(NonCancellable) {
                reconnectAfterFailure(tracker, "Update cancelled. The tracker keeps its current firmware.")
            }
            else -> reconnectAfterFailure(
                tracker,
                (e.message ?: "Bluetooth error") + "\nThe tracker keeps its current firmware; you can try again.",
            )
        }
    }

    private suspend fun reconnectAfterFailure(tracker: BleTrackerInfo, message: String) {
        _step.value = UpdateStep.Working("Reconnecting…")
        delay(FAILURE_RECONNECT_DELAY_MS)
        session.reconnect(tracker)
        _step.value = UpdateStep.Failed(message)
    }

    // -------------------------------------------------------------------------
    // T114 (Nordic DFU through the Adafruit bootloader)
    // -------------------------------------------------------------------------

    private suspend fun runT114(image: FirmwareImage) {
        val (link, tracker) = session.takeBleLinkForUpdate()
            ?: return fail("The Bluetooth link closed. Reconnect and try again.")
        _step.value = UpdateStep.Working("Restarting the tracker into its update mode…")
        try {
            if (link.updateServices.nordicDfu) {
                link.requestNordicDfuJump()
            } else {
                // Firmware from before the DFU service was registered: the setup CLI can do the jump.
                link.write("otadfu\r\n".encodeToByteArray())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The tracker usually drops the link mid-request as it reboots; the bootloader
            // check in runNordicDfu tells whether the jump really happened.
        } finally {
            link.close()
        }
        runDfuTransfer(image, tracker)
    }

    private suspend fun runDfuTransfer(image: FirmwareImage, tracker: BleTrackerInfo) {
        dfuRetry = image to tracker
        _step.value = UpdateStep.Working("Connecting to the tracker's bootloader…")
        try {
            platform.runNordicDfu(tracker, image.bytes) { percent, kbps ->
                _step.value = UpdateStep.Transferring(percent / 100f, kbps.takeIf { it > 0f }, cancellable = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dfuFailures++
            val base = e.message ?: "The update failed."
            _step.value = UpdateStep.Failed(
                if (dfuFailures == 1) {
                    "$base\nThe tracker is waiting in its update mode. Tap Retry now; it only " +
                        "listens over Bluetooth for a few minutes."
                } else {
                    "$base\nIf retrying doesn't work, recover the tracker over USB: connect it to a " +
                        "computer, then use the web flasher or copy the firmware's .uf2 file to the " +
                        "HT-n5262 drive. Pressing RST won't leave update mode."
                },
                canRetryNow = true,
            )
            return
        }
        dfuRetry = null
        dfuFailures = 0
        _step.value = UpdateStep.Working("Installed. The tracker is restarting…")
        confirmAfterReboot(tracker, image)
    }

    // -------------------------------------------------------------------------

    private suspend fun confirmAfterReboot(tracker: BleTrackerInfo, image: FirmwareImage) {
        delay(REBOOT_DELAY_MS)
        _step.value = UpdateStep.Working("Reconnecting…")
        val info = session.reconnect(tracker)
        _step.value = UpdateStep.Done(
            if (info != null) "Update finished. The tracker now runs ${info.version}."
            else "Update finished. The app couldn't reconnect yet; connect again from the device list.",
        )
        _check.value = ReleaseCheck.Idle
    }

    private fun fail(message: String) {
        _step.value = UpdateStep.Failed(message)
    }

    private companion object {
        /** The tracker reboots ~1.5 s after DONE; the handoff says reconnect after ~10 s. */
        const val REBOOT_DELAY_MS = 10_000L
        const val FAILURE_RECONNECT_DELAY_MS = 2_000L
    }
}
