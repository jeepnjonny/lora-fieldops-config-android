package io.github.jeepnjonny.lorafieldops.platform

import java.text.DateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date

private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun localTimeOfDay(): String = timeOfDay.format(LocalTime.now())

actual fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(epochMs))
