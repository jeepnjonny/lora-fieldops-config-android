package com.kj7nye.lorafieldops.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

/** Runtime permissions the UI asks for before using a feature. */
enum class AppPermission {
    /** Scanning for and connecting to trackers. */
    BLUETOOTH,
    /** "Use this device's location". */
    LOCATION,
    /** The coverage logger's ongoing notification. */
    NOTIFICATIONS,
}

/**
 * A permission check plus a way to ask for it. Where the OS asks by itself on first use
 * (iOS), [isGranted] is always true and the system prompt appears when the feature runs.
 */
class PermissionRequest(private val check: () -> Boolean, private val ask: () -> Unit) {
    val isGranted: Boolean get() = check()
    fun launch() = ask()
}

/** [onResult] receives the user's answer after [PermissionRequest.launch]. */
@Composable
expect fun rememberPermissionRequest(permission: AppPermission, onResult: (granted: Boolean) -> Unit): PermissionRequest

/** A clipboard entry holding plain [text]; [label] is a description some systems show. */
expect fun plainTextClip(label: String, text: String): ClipEntry
