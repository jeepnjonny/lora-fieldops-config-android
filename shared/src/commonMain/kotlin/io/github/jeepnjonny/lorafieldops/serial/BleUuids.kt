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

/** ESP32 firmware update service (`ble_ota.h`). Present only with the PIN on and on two-slot boards. */
const val OTA_SERVICE_UUID = "62970001-5b20-49c7-ae44-96d63f6d50e8"
/** Commands in (write with response), status out (notify). */
const val OTA_CONTROL_CHAR_UUID = "62970002-5b20-49c7-ae44-96d63f6d50e8"
/** `[u32 offset][payload]`, write without response. */
const val OTA_DATA_CHAR_UUID = "62970003-5b20-49c7-ae44-96d63f6d50e8"

/** Nordic legacy DFU service: buttonless entry in the T114 app, the transfer in its bootloader. */
const val NORDIC_DFU_SERVICE_UUID = "00001530-1212-efde-1523-785feabcd123"
/** DFU control point: `01 04` asks the application to reboot into the bootloader. */
const val NORDIC_DFU_CONTROL_CHAR_UUID = "00001531-1212-efde-1523-785feabcd123"
/** DFU version: reads `08 00` (0.8) in the bootloader. */
const val NORDIC_DFU_VERSION_CHAR_UUID = "00001534-1212-efde-1523-785feabcd123"
