package com.kj7nye.lorafieldops.platform

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Android runtime permissions behind each [AppPermission] on this OS version (empty: nothing to ask). */
fun androidPermissions(permission: AppPermission): Array<String> = when (permission) {
    AppPermission.BLUETOOTH ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Before Android 12, BLE scan results require location permission.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    AppPermission.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    AppPermission.NOTIFICATIONS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()
}

fun hasPermission(context: Context, permission: AppPermission): Boolean =
    androidPermissions(permission).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
actual fun rememberPermissionRequest(permission: AppPermission, onResult: (granted: Boolean) -> Unit): PermissionRequest {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        onResult(grants.values.all { it })
    }
    return remember(context, launcher, permission) {
        PermissionRequest(
            check = { hasPermission(context, permission) },
            ask = {
                val perms = androidPermissions(permission)
                if (perms.isEmpty()) onResult(true) else launcher.launch(perms)
            },
        )
    }
}

actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry(ClipData.newPlainText(label, text))
