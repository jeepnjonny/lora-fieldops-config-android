package com.kj7nye.lorafieldops

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kj7nye.lorafieldops.ui.ConfigScreen
import com.kj7nye.lorafieldops.ui.ConfigViewModel
import com.kj7nye.lorafieldops.ui.ConnectionScreen
import com.kj7nye.lorafieldops.ui.ConnectionState
import com.kj7nye.lorafieldops.ui.ImportExportSheet
import com.kj7nye.lorafieldops.ui.LogScreen
import com.kj7nye.lorafieldops.ui.StatusScreen
import com.kj7nye.lorafieldops.ui.theme.AppTheme

private const val ROUTE_CONNECT = "connect"
private const val ROUTE_CONFIG  = "config"
private const val ROUTE_STATUS  = "status"
private const val ROUTE_LOG     = "log"

class MainActivity : ComponentActivity() {

    private val vm: ConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle USB_DEVICE_ATTACHED intent (device plugged in while app was closed)
        handleUsbIntent(intent)

        setContent {
            AppTheme { MainNavigation(vm) }
        }
    }

    // singleTop: re-use existing instance when the OS relaunches for USB attach
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val drivers = com.hoho.android.usbserial.driver.UsbSerialProber
            .getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull { it.device == device } ?: return
        vm.serialManager.requestPermission(device) { granted ->
            if (granted) vm.connect(driver)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainNavigation(vm: ConfigViewModel) {
    val navController = rememberNavController()
    val connState by vm.connectionState.collectAsState()
    val snackMsg by vm.snackMessage.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    var showImportExport by remember { mutableStateOf(false) }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Show snack messages from ViewModel
    LaunchedEffect(snackMsg) {
        snackMsg?.let { msg ->
            snackHost.showSnackbar(msg)
            vm.clearSnack()
        }
    }

    // Navigate to connect screen on disconnect OR connection loss, clearing the entire
    // back stack. Without the Error case here, a USB drop while the user is on Config/
    // Status/Log left them stranded on that screen — the bottom nav disappears (isConnected
    // becomes false) but nothing routes them back to where they can actually reconnect.
    // ConnectionScreen already renders ConnectionState.Error with a message + device list,
    // and reconnecting re-syncs config from the device via loadConfig(), so anything that
    // was already sent to the device before the drop comes back automatically.
    // popUpTo(ROUTE_CONNECT) would fail silently if ROUTE_CONNECT was already popped
    // when navigating to config (it was). popUpTo(0) always clears everything.
    LaunchedEffect(connState) {
        if (connState is ConnectionState.Disconnected || connState is ConnectionState.Error) {
            navController.navigate(ROUTE_CONNECT) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val isConnected = connState is ConnectionState.Connected

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        bottomBar = {
            if (isConnected) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_CONFIG,
                        onClick = { navController.navigate(ROUTE_CONFIG) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text("Config") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_STATUS,
                        onClick = { navController.navigate(ROUTE_STATUS) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Info, null) },
                        label = { Text("Status") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_LOG,
                        onClick = { navController.navigate(ROUTE_LOG) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.List, null) },
                        label = { Text("Log") },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { showImportExport = true },
                        icon = { Icon(Icons.Default.ImportExport, null) },
                        label = { Text("Import/Export") },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { vm.disconnect() },
                        icon = { Icon(Icons.Default.Usb, null) },
                        label = { Text("Disconnect") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_CONNECT,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_CONNECT) {
                ConnectionScreen(vm) {
                    navController.navigate(ROUTE_CONFIG) {
                        popUpTo(ROUTE_CONNECT) { inclusive = true }
                    }
                }
            }
            composable(ROUTE_CONFIG) { ConfigScreen(vm) }
            composable(ROUTE_STATUS) { StatusScreen(vm) }
            composable(ROUTE_LOG)    { LogScreen(vm) }
        }
    }

    if (showImportExport) {
        ImportExportSheet(vm) { showImportExport = false }
    }
}
