package org.cygnusx1.openbu

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.util.Log
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.launch
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.cygnusx1.openbu.ui.ConnectionScreen
import org.cygnusx1.openbu.ui.DashboardScreen
import org.cygnusx1.openbu.ui.FileManagerScreen
import org.cygnusx1.openbu.ui.TimelapseScreen
import org.cygnusx1.openbu.ui.PrinterSettingsScreen
import org.cygnusx1.openbu.ui.RtspStreamScreen
import org.cygnusx1.openbu.ui.SettingsScreen
import org.cygnusx1.openbu.ui.StreamScreen
import org.cygnusx1.openbu.ui.VideoPlayerScreen
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
            val customBgColor by viewModel.customBgColor.collectAsState()
            val connectionState by viewModel.connectionState.collectAsState()
            val isConnected = connectionState == ConnectionState.Connected
            OpenbuTheme(
                overrideDeviceTheme = forceDarkMode,
                customBackgroundColor = if (isConnected) customBgColor else null,
            ) {
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
                val debugLogging by viewModel.debugLogging.collectAsState()
                val extendedDebugLogging by viewModel.extendedDebugLogging.collectAsState()
                val discoveredPrinters by viewModel.discoveredPrinters.collectAsState()
                val savedPrinters by viewModel.savedPrinters.collectAsState()
                val connectedSerialNumber by viewModel.connectedSerialNumber.collectAsState()

                var showFullscreen by rememberSaveable { mutableStateOf(false) }
                var showRtspFullscreen by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showPrinterSettings by rememberSaveable { mutableStateOf(false) }
                var showFileManager by rememberSaveable { mutableStateOf(false) }
                var showTimelapse by rememberSaveable { mutableStateOf(false) }
                val effectiveRtspUrl = if (rtspEnabled && rtspUrl.isNotBlank()) rtspUrl else ""

                // Shared ExoPlayer for RTSP — survives screen transitions
                @OptIn(UnstableApi::class)
                val rtspPlayer = remember(effectiveRtspUrl) {
                    if (effectiveRtspUrl.isNotBlank()) {
                        val isRtsps = effectiveRtspUrl.startsWith("rtsps://")
                        val playerUrl = if (isRtsps) effectiveRtspUrl.replaceFirst("rtsps://", "rtsp://") else effectiveRtspUrl
                        Log.d("RTSP", "Creating player: isRtsps=$isRtsps, playerUrl=$playerUrl")
                        ExoPlayer.Builder(this@MainActivity).build().apply {
                            addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    val stateName = when (playbackState) {
                                        Player.STATE_IDLE -> "IDLE"
                                        Player.STATE_BUFFERING -> "BUFFERING"
                                        Player.STATE_READY -> "READY"
                                        Player.STATE_ENDED -> "ENDED"
                                        else -> "UNKNOWN($playbackState)"
                                    }
                                    Log.d("RTSP", "Playback state: $stateName")
                                }
                                override fun onPlayerError(error: PlaybackException) {
                                    Log.e("RTSP", "Player error: ${error.errorCodeName} (${error.errorCode})", error)
                                }
                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    Log.d("RTSP", "isPlaying=$isPlaying")
                                }
                                override fun onRenderedFirstFrame() {
                                    Log.d("RTSP", "First frame rendered")
                                }
                            })
                            val factory = RtspMediaSource.Factory().apply {
                                if (isRtsps) {
                                    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                                    })
                                    val sslContext = SSLContext.getInstance("TLS")
                                    sslContext.init(null, trustAll, java.security.SecureRandom())
                                    Log.d("RTSP", "Using trust-all SSLSocketFactory + forceRtpTcp")
                                    setSocketFactory(sslContext.socketFactory)
                                    setForceUseRtpTcp(true)
                                }
                            }
                            val mediaSource = factory.createMediaSource(MediaItem.fromUri(playerUrl))
                            setMediaSource(mediaSource)
                            prepare()
                            playWhenReady = true
                        }
                    } else null
                }
                DisposableEffect(effectiveRtspUrl) {
                    onDispose { rtspPlayer?.release() }
                }

                val fileList by viewModel.fileList.collectAsState()
                val currentFtpPath by viewModel.currentFtpPath.collectAsState()
                val ftpLoading by viewModel.ftpLoading.collectAsState()
                val ftpError by viewModel.ftpError.collectAsState()
                val ftpTransferProgress by viewModel.ftpTransferProgress.collectAsState()
                val ftpTransferName by viewModel.ftpTransferName.collectAsState()

                val timelapseListState = androidx.compose.foundation.lazy.rememberLazyListState()
                val timelapseScope = androidx.compose.runtime.rememberCoroutineScope()
                var timelapseNewestFirst by rememberSaveable { mutableStateOf(true) }
                val timelapseFileList by viewModel.timelapseFileList.collectAsState()
                val timelapseThumbnails by viewModel.timelapseThumbnails.collectAsState()
                val timelapseLoading by viewModel.timelapseLoading.collectAsState()
                val timelapseError by viewModel.timelapseError.collectAsState()
                val timelapseDownloadProgress by viewModel.timelapseDownloadProgress.collectAsState()
                val timelapseDownloadName by viewModel.timelapseDownloadName.collectAsState()
                val timelapsePlaybackFile by viewModel.timelapsePlaybackFile.collectAsState()

                when {
                    // Video player — shown when timelapse file is downloaded
                    showTimelapse && timelapsePlaybackFile != null -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        BackHandler {
                            viewModel.clearPlaybackFile()
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        VideoPlayerScreen(
                            videoFile = timelapsePlaybackFile!!,
                            onFinished = {
                                viewModel.clearPlaybackFile()
                            },
                        )
                    }
                    // Timelapse recordings browser
                    showTimelapse -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler {
                            viewModel.closeTimelapse()
                            showTimelapse = false
                        }
                        TimelapseScreen(
                            fileList = timelapseFileList,
                            thumbnails = timelapseThumbnails,
                            isLoading = timelapseLoading,
                            error = timelapseError,
                            downloadProgress = timelapseDownloadProgress,
                            downloadName = timelapseDownloadName,
                            listState = timelapseListState,
                            newestFirst = timelapseNewestFirst,
                            onToggleSortOrder = {
                                timelapseNewestFirst = !timelapseNewestFirst
                                timelapseScope.launch { timelapseListState.scrollToItem(0) }
                            },
                            onPlayVideo = { viewModel.playVideo(it) },
                            onCancelDownload = { viewModel.cancelTimelapseDownload() },
                            onClearError = { viewModel.clearTimelapseError() },
                            onBack = {
                                viewModel.closeTimelapse()
                                showTimelapse = false
                            },
                        )
                    }
                    // File manager stays visible even if camera/MQTT drops
                    showFileManager -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler {
                            if (currentFtpPath != "/") {
                                viewModel.navigateUp()
                            } else {
                                viewModel.closeFileManager()
                                showFileManager = false
                            }
                        }
                        FileManagerScreen(
                            fileList = fileList,
                            currentPath = currentFtpPath,
                            isLoading = ftpLoading,
                            error = ftpError,
                            transferProgress = ftpTransferProgress,
                            transferName = ftpTransferName,
                            onCancelTransfer = { viewModel.cancelTransfer() },
                            onNavigateTo = { viewModel.navigateTo(it) },
                            onNavigateUp = { viewModel.navigateUp() },
                            onDownloadFile = { viewModel.downloadFile(it) },
                            onDeleteFile = { viewModel.deleteFtpFile(it) },
                            onUploadFile = { viewModel.uploadFile(it) },
                            onClearError = { viewModel.clearFtpError() },
                            onBack = {
                                if (currentFtpPath != "/") {
                                    viewModel.navigateUp()
                                } else {
                                    viewModel.closeFileManager()
                                    showFileManager = false
                                }
                            },
                        )
                    }
                    connectionState == ConnectionState.Connected && showSettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            keepConnectionInBackground = keepConnectionInBackground,
                            onKeepConnectionChanged = { viewModel.setKeepConnectionInBackground(it) },
                            showMainStream = showMainStream,
                            onShowMainStreamChanged = { viewModel.setShowMainStream(it) },
                            forceDarkMode = forceDarkMode,
                            onForceDarkModeChanged = { viewModel.setForceDarkMode(it) },
                            debugLogging = debugLogging,
                            onDebugLoggingChanged = { viewModel.setDebugLogging(it) },
                            extendedDebugLogging = extendedDebugLogging,
                            onExtendedDebugLoggingChanged = { viewModel.setExtendedDebugLogging(it) },
                            onBack = { showSettings = false },
                        )
                    }
                    connectionState == ConnectionState.Connected && showPrinterSettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showPrinterSettings = false }
                        val customPrinterName by viewModel.customPrinterName.collectAsState()
                        val isAutoRtsp = connectedSerialNumber.length >= 3 &&
                            !connectedSerialNumber.startsWith("01S") &&
                            !connectedSerialNumber.startsWith("01P")
                        PrinterSettingsScreen(
                            customPrinterName = customPrinterName,
                            onCustomPrinterNameChanged = { viewModel.setCustomPrinterName(it) },
                            rtspEnabled = rtspEnabled,
                            onRtspEnabledChanged = { viewModel.setRtspEnabled(it) },
                            rtspUrl = rtspUrl,
                            onRtspUrlChanged = { viewModel.setRtspUrl(it) },
                            isAutoRtsp = isAutoRtsp,
                            customBgColor = customBgColor,
                            onBgColorChanged = { viewModel.setCustomBgColor(it) },
                            isSaved = savedPrinters.any { it.serialNumber == connectedSerialNumber },
                            onSavePrinter = { viewModel.saveCurrentPrinter() },
                            onDeletePrinter = { viewModel.deleteSavedPrinter(connectedSerialNumber) },
                            onBack = { showPrinterSettings = false },
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
                            showPrinterSettings = false
                            showFileManager = false
                            showTimelapse = false
                            viewModel.closeFileManager()
                            viewModel.closeTimelapse()
                            viewModel.disconnect()
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        val dashboardCustomName by viewModel.customPrinterName.collectAsState()
                        val printerName = dashboardCustomName.ifBlank {
                            discoveredPrinters.firstOrNull { it.serialNumber == connectedSerialNumber }?.deviceName
                                ?: savedPrinters.firstOrNull { it.serialNumber == connectedSerialNumber }?.deviceName
                                ?: ""
                        }
                        DashboardScreen(
                            frame = frame,
                            fps = fps,
                            isLightOn = isLightOn,
                            isMqttConnected = isMqttConnected,
                            printerStatus = printerStatus,
                            printerName = printerName,
                            showMainStream = showMainStream,
                            rtspPlayer = rtspPlayer,
                            onToggleLight = { viewModel.toggleLight(it) },
                            onOpenFullscreen = { showFullscreen = true },
                            onOpenRtspFullscreen = { showRtspFullscreen = true },
                            onOpenSettings = { showSettings = true },
                            onOpenPrinterSettings = { showPrinterSettings = true },
                            onOpenFileManager = {
                                viewModel.openFileManager()
                                showFileManager = true
                            },
                            onOpenTimelapse = {
                                viewModel.openTimelapse()
                                showTimelapse = true
                            },
                        )
                    }
                    else -> {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        showFullscreen = false
                        showRtspFullscreen = false
                        showSettings = false
                        showPrinterSettings = false
                        showFileManager = false
                        showTimelapse = false
                        ConnectionScreen(
                            connectionState = connectionState,
                            errorMessage = errorMessage,
                            discoveredPrinters = discoveredPrinters,
                            savedPrinters = savedPrinters,
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
