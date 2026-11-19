package io.github.jeepnjonny.lorafieldops.platform

/** Wall-clock time in milliseconds since the Unix epoch. */
expect fun nowMillis(): Long

/** Local wall-clock time of day as "HH:mm:ss". */
expect fun localTimeOfDay(): String

/** [epochMs] as a short local date and medium time, e.g. "11/19/26, 2:04:05 PM". */
expect fun formatDateTime(epochMs: Long): String
