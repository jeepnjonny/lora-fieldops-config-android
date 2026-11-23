package io.github.jeepnjonny.lorafieldops.firmware

import android.app.Activity
import io.github.jeepnjonny.lorafieldops.BuildConfig
import io.github.jeepnjonny.lorafieldops.MainActivity
import no.nordicsemi.android.dfu.DfuBaseService

/** Nordic DFU library service for the T114 upload; [NordicDfuRunner] starts it. */
class TrackerDfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java
    override fun isDebug(): Boolean = BuildConfig.DEBUG
}
