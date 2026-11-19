package com.kj7nye.lorafieldops.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

/** iOS shows its own permission prompt when Bluetooth or location is first used. */
@Composable
actual fun rememberPermissionRequest(permission: AppPermission, onResult: (granted: Boolean) -> Unit): PermissionRequest =
    remember(permission) { PermissionRequest(check = { true }, ask = { onResult(true) }) }

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry.withPlainText(text)
