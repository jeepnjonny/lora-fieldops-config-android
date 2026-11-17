package com.kj7nye.lorafieldops.logger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background upload of the coverage queue. Enqueued by [CoverageLogger.scheduleUpload]
 * with a network constraint and exponential backoff, so entries logged during a drive
 * go up once the phone is back online, even if the app was closed in between.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (CoverageLogger.get(applicationContext).runUpload()) {
            is UploadResult.Retry -> Result.retry()
            else -> Result.success()   // done, blocked until settings change, or logger off
        }
}
