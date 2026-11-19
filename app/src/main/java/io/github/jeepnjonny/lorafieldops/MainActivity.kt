package io.github.jeepnjonny.lorafieldops

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jeepnjonny.lorafieldops.ui.ConfigViewModel
import io.github.jeepnjonny.lorafieldops.ui.ConnectionState
import io.github.jeepnjonny.lorafieldops.ui.LoRaFieldOpsApp

class MainActivity : ComponentActivity() {

    private val platform by lazy { AndroidPlatform.get(this) }

    private val vm: ConfigViewModel by viewModels {
        viewModelFactory { initializer { ConfigViewModel(AndroidPlatform.get(application)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle USB_DEVICE_ATTACHED intent (device plugged in while app was closed).
        // Not on recreation (rotation etc.): the launch intent is still the attach intent,
        // and handling it again would reopen the port and drop unsaved edits.
        if (savedInstanceState == null) handleUsbIntent(intent)

        setContent { LoRaFieldOpsApp(vm) }
    }

    // singleTop: re-use existing instance when the OS relaunches for USB attach
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // Consume it so a later recreation can't act on it again.
        intent.action = null
        // Already in a session (or opening one): leave it alone.
        val state = vm.connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) return
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        val info = platform.usbSupport.infoFor(device) ?: return
        vm.connectUsbWithPermission(info)
    }
}
