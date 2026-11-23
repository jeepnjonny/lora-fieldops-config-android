package io.github.jeepnjonny.lorafieldops.serial

import android.bluetooth.BluetoothGatt

/** Gives the Bluetooth stack time to drop its service cache before rediscovery. */
internal const val GATT_REFRESH_SETTLE_MS = 500L

/**
 * Drops Android's cached service table for this device. BluetoothGatt.refresh() is
 * hidden API (called by reflection); it is the only way to clear a stale cache on a
 * bonded device. Returns false where it isn't available.
 */
internal fun BluetoothGatt.refreshServiceCache(): Boolean = try {
    javaClass.getMethod("refresh").invoke(this) as? Boolean ?: false
} catch (_: Exception) {
    false
}
