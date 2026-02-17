package org.cygnusx1.openbu

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import org.cygnusx1.openbu.ui.ConnectionScreen
import org.cygnusx1.openbu.ui.DashboardScreen
import org.cygnusx1.openbu.ui.RtspStreamScreen
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
            val viewModel: BambuStreamViewModel = viewModel()
            val forceDarkMode by viewModel.forceDarkMode.collectAsState()
            OpenbuTheme(overrideDeviceTheme = forceDarkMode) {
                val connectionState by viewModel.connectionState.collectAsState()
                val frame by viewModel.frame.collectAsState()
                val fps by viewModel.fps.collectAsState()
                val errorMessage by viewModel.errorMessage.collectAsState()
                val isLightOn by viewModel.isLightOn.collectAsState()
                val isMqttConnected by viewModel.isMqttConnected.collectAsState()
                val printerStatus by viewModel.printerStatus.collectAsState()
                val keepConnectionInBackground by viewModel.keepConnectionInBackground.collectAsState()
                val showMainStream by viewModel.showMainStream.collectAsState()
                val rtspEnabled by viewModel.rtspEnabled.collectAsState()
                val rtspUrl by viewModel.rtspUrl.collectAsState()
                val discoveredPrinters by viewModel.discoveredPrinters.collectAsState()

                var showFullscreen by rememberSaveable { mutableStateOf(false) }
                var showRtspFullscreen by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val effectiveRtspUrl = if (rtspEnabled && rtspUrl.isNotBlank()) rtspUrl else ""

                // Shared ExoPlayer for RTSP — survives screen transitions
                @OptIn(UnstableApi::class)
                val rtspPlayer = remember(effectiveRtspUrl) {
                    if (effectiveRtspUrl.isNotBlank()) {
                        ExoPlayer.Builder(this@MainActivity).build().apply {
                            val mediaSource = RtspMediaSource.Factory()
                                .createMediaSource(MediaItem.fromUri(effectiveRtspUrl))
                            setMediaSource(mediaSource)
                            prepare()
                            playWhenReady = true
                        }
                    } else null
                }
                DisposableEffect(effectiveRtspUrl) {
                    onDispose { rtspPlayer?.release() }
                }

                when {
                    connectionState == ConnectionState.Connected && showSettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            keepConnectionInBackground = keepConnectionInBackground,
                            onKeepConnectionChanged = { viewModel.setKeepConnectionInBackground(it) },
                            showMainStream = showMainStream,
                            onShowMainStreamChanged = { viewModel.setShowMainStream(it) },
                            rtspEnabled = rtspEnabled,
                            onRtspEnabledChanged = { viewModel.setRtspEnabled(it) },
                            rtspUrl = rtspUrl,
                            onRtspUrlChanged = { viewModel.setRtspUrl(it) },
                            forceDarkMode = forceDarkMode,
                            onForceDarkModeChanged = { viewModel.setForceDarkMode(it) },
                            onBack = { showSettings = false },
                        )
                    }
                    connectionState == ConnectionState.Connected && showFullscreen -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        BackHandler {
                            showFullscreen = false
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        StreamScreen(
                            frame = frame,
                            fps = fps,
                        )
                    }
                    connectionState == ConnectionState.Connected && showRtspFullscreen && effectiveRtspUrl.isNotBlank() -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        BackHandler {
                            showRtspFullscreen = false
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        RtspStreamScreen(player = rtspPlayer)
                    }
                    connectionState == ConnectionState.Connected -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler {
                            showFullscreen = false
                            showRtspFullscreen = false
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
                            showMainStream = showMainStream,
                            rtspPlayer = rtspPlayer,
                            onToggleLight = { viewModel.toggleLight(it) },
                            onOpenFullscreen = { showFullscreen = true },
                            onOpenRtspFullscreen = { showRtspFullscreen = true },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                    else -> {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        showFullscreen = false
                        showRtspFullscreen = false
                        showSettings = false
                        ConnectionScreen(
                            connectionState = connectionState,
                            errorMessage = errorMessage,
                            discoveredPrinters = discoveredPrinters,
                            onStartDiscovery = { viewModel.startDiscovery() },
                            onStopDiscovery = { viewModel.stopDiscovery() },
                            onGetSavedAccessCode = { serial -> viewModel.getSavedAccessCode(serial) },
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
