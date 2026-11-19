package io.github.jeepnjonny.lorafieldops.serial

/** APRS.fi KISS TNC service: the UUID the tracker advertises (used to find it in a scan). */
const val KISS_SERVICE_UUID = "00000001-ba2a-46c9-ae49-01b0961f68bb"
/** Tracker → phone KISS notifications. */
const val KISS_TX_CHAR_UUID = "00000003-ba2a-46c9-ae49-01b0961f68bb"

/** Nordic-UART-style configuration service carrying the setup CLI (PIN-protected links only). */
const val CONFIG_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
/** Phone writes CLI bytes here. */
const val CONFIG_RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
/** Tracker notifies CLI output here. */
const val CONFIG_TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
