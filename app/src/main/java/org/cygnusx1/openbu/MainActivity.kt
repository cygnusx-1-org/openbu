package org.cygnusx1.openbu

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.cygnusx1.openbu.ui.ConnectionScreen
import org.cygnusx1.openbu.ui.DashboardScreen
import org.cygnusx1.openbu.ui.SettingsScreen
import org.cygnusx1.openbu.ui.StreamScreen
import org.cygnusx1.openbu.ui.theme.OpenbuTheme
import org.cygnusx1.openbu.viewmodel.BambuStreamViewModel
import org.cygnusx1.openbu.viewmodel.ConnectionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenbuTheme {
                val viewModel: BambuStreamViewModel = viewModel()
                LaunchedEffect(Unit) { viewModel.autoConnectIfSaved() }
                val connectionState by viewModel.connectionState.collectAsState()
                val frame by viewModel.frame.collectAsState()
                val fps by viewModel.fps.collectAsState()
                val errorMessage by viewModel.errorMessage.collectAsState()
                val isLightOn by viewModel.isLightOn.collectAsState()
                val isMqttConnected by viewModel.isMqttConnected.collectAsState()
                val printerStatus by viewModel.printerStatus.collectAsState()
                val keepConnectionInBackground by viewModel.keepConnectionInBackground.collectAsState()
                val discoveredPrinters by viewModel.discoveredPrinters.collectAsState()

                var showFullscreen by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }

                when {
                    connectionState == ConnectionState.Connected && showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            keepConnectionInBackground = keepConnectionInBackground,
                            onKeepConnectionChanged = { viewModel.setKeepConnectionInBackground(it) },
                            onBack = { showSettings = false },
                        )
                    }
                    connectionState == ConnectionState.Connected && showFullscreen -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        BackHandler { showFullscreen = false }
                        StreamScreen(
                            frame = frame,
                            fps = fps,
                        )
                    }
                    connectionState == ConnectionState.Connected -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        BackHandler {
                            showFullscreen = false
                            showSettings = false
                            viewModel.disconnect()
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        DashboardScreen(
                            frame = frame,
                            fps = fps,
                            isLightOn = isLightOn,
                            isMqttConnected = isMqttConnected,
                            printerStatus = printerStatus,
                            onToggleLight = { viewModel.toggleLight(it) },
                            onOpenFullscreen = { showFullscreen = true },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                    else -> {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        showFullscreen = false
                        showSettings = false
                        ConnectionScreen(
                            savedIp = viewModel.getSavedIp(),
                            savedAccessCode = viewModel.getSavedAccessCode(),
                            savedSerialNumber = viewModel.getSavedSerialNumber(),
                            connectionState = connectionState,
                            errorMessage = errorMessage,
                            discoveredPrinters = discoveredPrinters,
                            onStartDiscovery = { viewModel.startDiscovery() },
                            onStopDiscovery = { viewModel.stopDiscovery() },
                            onConnect = { ip, accessCode, serialNumber ->
                                viewModel.connect(ip, accessCode, serialNumber)
                            },
                        )
                    }
                }
            }
        }
    }
}
