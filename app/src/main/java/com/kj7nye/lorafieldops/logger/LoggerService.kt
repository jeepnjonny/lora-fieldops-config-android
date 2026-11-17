package com.kj7nye.lorafieldops.logger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kj7nye.lorafieldops.MainActivity
import com.kj7nye.lorafieldops.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "coverage_logger"
private const val NOTIFICATION_ID = 0x4C47   // "LG"

/**
 * Foreground service (type connectedDevice) that keeps the app process alive while the
 * coverage logger is on and a tracker is connected, so logging continues with the
 * screen off during a drive. The logging itself lives in [CoverageLogger]; this only
 * holds the process and shows "Logging K7ABC-9: N sent, M heard, Q queued".
 */
class LoggerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        val logger = CoverageLogger.get(this)
        if (!goForeground(text(logger.runtime.value, 0))) return
        scope.launch {
            combine(logger.runtime, logger.queuedCount) { rt, q -> text(rt, q) }
                .distinctUntilChanged()
                .collect { t ->
                    if (!logger.serviceWanted) { stopSelf(); return@collect }
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(t))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!CoverageLogger.get(this).serviceWanted) stopSelf()
        // Not sticky: after the process dies there is no BLE link to log from.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(text: String): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        true
    } catch (e: Exception) {
        // E.g. Android 14 refusing connectedDevice without a Bluetooth permission.
        stopSelf()
        false
    }

    private fun text(rt: LoggerRuntime, queued: Int): String = when {
        rt.logging -> "Logging ${rt.device}: ${rt.sent} sent, ${rt.heard} heard, $queued queued"
        rt.reconnecting -> "Reconnecting to tracker… $queued queued"
        else -> "Coverage logger: $queued queued"
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logger)
            .setContentTitle("Coverage logger")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Coverage logger", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the coverage logger records a connected tracker"
            }
        )
    }

    companion object {
        @Volatile var running: Boolean = false
            private set
    }
}
