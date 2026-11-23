package io.github.jeepnjonny.lorafieldops.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

private val timeOfDay = NSDateFormatter().apply { dateFormat = "HH:mm:ss" }
private val dateTime = NSDateFormatter().apply {
    dateStyle = NSDateFormatterShortStyle
    timeStyle = NSDateFormatterMediumStyle
}

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun localTimeOfDay(): String = timeOfDay.stringFromDate(NSDate())

actual fun formatDateTime(epochMs: Long): String =
    dateTime.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
