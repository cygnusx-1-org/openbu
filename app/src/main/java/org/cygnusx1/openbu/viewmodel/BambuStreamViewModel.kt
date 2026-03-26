package org.cygnusx1.openbu.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.cygnusx1.openbu.data.FilamentProfile
import org.cygnusx1.openbu.data.printerSeriesFromSerial
import org.cygnusx1.openbu.network.BambuCameraClient
import org.cygnusx1.openbu.network.BambuFtpsClient
import org.cygnusx1.openbu.network.BambuMqttClient
import org.cygnusx1.openbu.network.BambuSsdpClient
import org.cygnusx1.openbu.network.DiscoveredPrinter
import org.cygnusx1.openbu.network.FtpFileEntry
import org.cygnusx1.openbu.network.PrintableObject
import org.cygnusx1.openbu.network.PrinterStatus
import org.cygnusx1.openbu.network.SavedPrinter
import org.cygnusx1.openbu.network.ThreeMfParser
import org.cygnusx1.openbu.service.ConnectionForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

class BambuStreamViewModel(application: Application) : AndroidViewModel(application) {

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _isLightOn = MutableStateFlow<Boolean?>(null)
    val isLightOn: StateFlow<Boolean?> = _isLightOn.asStateFlow()

    private val _isMqttConnected = MutableStateFlow(false)
    val isMqttConnected: StateFlow<Boolean> = _isMqttConnected.asStateFlow()

    private val _printerStatus = MutableStateFlow(PrinterStatus())
    val printerStatus: StateFlow<PrinterStatus> = _printerStatus.asStateFlow()

    private val _keepConnectionInBackground = MutableStateFlow(true)
    val keepConnectionInBackground: StateFlow<Boolean> = _keepConnectionInBackground.asStateFlow()

    private val _showMainStream = MutableStateFlow(true)
    val showMainStream: StateFlow<Boolean> = _showMainStream.asStateFlow()

    private val _internalRtspUrl = MutableStateFlow("")
    val internalRtspUrl: StateFlow<String> = _internalRtspUrl.asStateFlow()

    private val _rtspEnabled = MutableStateFlow(false)
    val rtspEnabled: StateFlow<Boolean> = _rtspEnabled.asStateFlow()

    private val _rtspUrl = MutableStateFlow("")
    val rtspUrl: StateFlow<String> = _rtspUrl.asStateFlow()

    private val _useVlcForRtsp = MutableStateFlow(false)
    val useVlcForRtsp: StateFlow<Boolean> = _useVlcForRtsp.asStateFlow()

    private val _forceDarkMode = MutableStateFlow(false)
    val forceDarkMode: StateFlow<Boolean> = _forceDarkMode.asStateFlow()

    private val _debugLogging = MutableStateFlow(false)
    val debugLogging: StateFlow<Boolean> = _debugLogging.asStateFlow()

    private val _customPrinterName = MutableStateFlow("")
    val customPrinterName: StateFlow<String> = _customPrinterName.asStateFlow()

    private val _mjpegCameraFailed = MutableStateFlow(false)
    val mjpegCameraFailed: StateFlow<Boolean> = _mjpegCameraFailed.asStateFlow()

    private val _noRouteToHost = MutableStateFlow<String?>(null)
    val noRouteToHost: StateFlow<String?> = _noRouteToHost.asStateFlow()

    private val _autoSavePrinter = MutableStateFlow(true)
    val autoSavePrinter: StateFlow<Boolean> = _autoSavePrinter.asStateFlow()

    private val _redactLogs = MutableStateFlow(true)
    val redactLogs: StateFlow<Boolean> = _redactLogs.asStateFlow()

    private val _customBgColor = MutableStateFlow<Int?>(null)
    val customBgColor: StateFlow<Int?> = _customBgColor.asStateFlow()

    private val _connectedSerialNumber = MutableStateFlow("")
    val connectedSerialNumber: StateFlow<String> = _connectedSerialNumber.asStateFlow()

    private val _connectedIp = MutableStateFlow("")
    private val _connectedAccessCode = MutableStateFlow("")

    private val _savedPrinters = MutableStateFlow<List<SavedPrinter>>(emptyList())
    val savedPrinters: StateFlow<List<SavedPrinter>> = _savedPrinters.asStateFlow()

    private val ssdpClient = BambuSsdpClient()
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = ssdpClient.discoveredPrinters

    private val _fileList = MutableStateFlow<List<FtpFileEntry>>(emptyList())
    val fileList: StateFlow<List<FtpFileEntry>> = _fileList.asStateFlow()

    private val _currentFtpPath = MutableStateFlow("/")
    val currentFtpPath: StateFlow<String> = _currentFtpPath.asStateFlow()

    private val _ftpLoading = MutableStateFlow(false)
    val ftpLoading: StateFlow<Boolean> = _ftpLoading.asStateFlow()

    private val _ftpError = MutableStateFlow<String?>(null)
    val ftpError: StateFlow<String?> = _ftpError.asStateFlow()

    private val _ftpTransferProgress = MutableStateFlow<Float?>(null)
    val ftpTransferProgress: StateFlow<Float?> = _ftpTransferProgress.asStateFlow()

    private val _ftpTransferName = MutableStateFlow<String?>(null)
    val ftpTransferName: StateFlow<String?> = _ftpTransferName.asStateFlow()

    // Timelapse state flows
    private val _timelapseFileList = MutableStateFlow<List<FtpFileEntry>>(emptyList())
    val timelapseFileList: StateFlow<List<FtpFileEntry>> = _timelapseFileList.asStateFlow()

    private val _timelapseThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val timelapseThumbnails: StateFlow<Map<String, Bitmap>> = _timelapseThumbnails.asStateFlow()

    private val _timelapseLoading = MutableStateFlow(false)
    val timelapseLoading: StateFlow<Boolean> = _timelapseLoading.asStateFlow()

    private val _timelapseError = MutableStateFlow<String?>(null)
    val timelapseError: StateFlow<String?> = _timelapseError.asStateFlow()

    private val _timelapseDownloadProgress = MutableStateFlow<Float?>(null)
    val timelapseDownloadProgress: StateFlow<Float?> = _timelapseDownloadProgress.asStateFlow()

    private val _timelapseDownloadName = MutableStateFlow<String?>(null)
    val timelapseDownloadName: StateFlow<String?> = _timelapseDownloadName.asStateFlow()

    private val _timelapsePlaybackFile = MutableStateFlow<File?>(null)
    val timelapsePlaybackFile: StateFlow<File?> = _timelapsePlaybackFile.asStateFlow()

    private val _mqttDataMessages = MutableStateFlow<List<String>>(emptyList())
    val mqttDataMessages: StateFlow<List<String>> = _mqttDataMessages.asStateFlow()

    private val _logcatText = MutableStateFlow("")
    val logcatText: StateFlow<String> = _logcatText.asStateFlow()

    val connectedAccessCode: StateFlow<String> = _connectedAccessCode.asStateFlow()

    fun captureLogcat() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pid = android.os.Process.myPid()
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid"))
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor()
                _logcatText.value = text
            } catch (e: Exception) {
                _logcatText.value = "Failed to capture logcat: ${e.message}"
            }
        }
    }

    private var client: BambuCameraClient? = null
    private var streamJob: Job? = null
    private var mqttClient: BambuMqttClient? = null
    private var mqttJob: Job? = null
    private var ftpClient: BambuFtpsClient? = null
    private var ftpTransferJob: Job? = null
    private var timelapseFtpClient: BambuFtpsClient? = null
    private var timelapseDownloadJob: Job? = null
    private var timelapseDownloadClient: BambuFtpsClient? = null
    private var timelapseThumbnailJob: Job? = null
    @Volatile private var timelapseDownloadCancelled = false
    private var pendingSavePrinter = false
    private var userDisconnected = false
    private var reconnectJob: Job? = null
    private var reconnectRetryCount = 0
    private val maxReconnectRetries = 5
    private var mjpegRetryCount = 0
    private var mjpegRetryJob: Job? = null

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _hasLastConnectedPrinter = MutableStateFlow(false)
    val hasLastConnectedPrinter: StateFlow<Boolean> = _hasLastConnectedPrinter.asStateFlow()

    private val _rtspReconnectKey = MutableStateFlow(0)
    val rtspReconnectKey: StateFlow<Int> = _rtspReconnectKey.asStateFlow()

    private val _skipObjectsList = MutableStateFlow<List<PrintableObject>>(emptyList())
    val skipObjectsList: StateFlow<List<PrintableObject>> = _skipObjectsList.asStateFlow()

    private val _skipObjectsLoading = MutableStateFlow(false)
    val skipObjectsLoading: StateFlow<Boolean> = _skipObjectsLoading.asStateFlow()

    private val _skipObjectsLoadingMessage = MutableStateFlow("Loading...")
    val skipObjectsLoadingMessage: StateFlow<String> = _skipObjectsLoadingMessage.asStateFlow()

    private val _skipObjectsError = MutableStateFlow<String?>(null)
    val skipObjectsError: StateFlow<String?> = _skipObjectsError.asStateFlow()

    private val _skipObjectsPlateImage = MutableStateFlow<Bitmap?>(null)
    val skipObjectsPlateImage: StateFlow<Bitmap?> = _skipObjectsPlateImage.asStateFlow()

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(application)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        try {
            EncryptedSharedPreferences.create(
                application,
                "bambu_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Encrypted prefs corrupted after force-close — delete and recreate
            Log.w("BambuVM", "EncryptedSharedPreferences corrupted, resetting", e)
            application.deleteSharedPreferences("bambu_prefs")
            EncryptedSharedPreferences.create(
                application,
                "bambu_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    fun getSavedAccessCode(serialNumber: String): String =
        prefs.getString("access_code_$serialNumber", "") ?: ""

    init {
        _keepConnectionInBackground.value = prefs.getBoolean("keep_connection_bg", true)
        _showMainStream.value = prefs.getBoolean("show_main_stream", true)
        _forceDarkMode.value = prefs.getBoolean("force_dark_mode", false)
        _debugLogging.value = prefs.getBoolean("debug_logging", false)
        _autoSavePrinter.value = prefs.getBoolean("auto_save_printer", true)
        _redactLogs.value = prefs.getBoolean("redact_logs", true)
        _useVlcForRtsp.value = prefs.getBoolean("use_vlc_rtsp", false)

        // Migrate stale global RTSP keys
        if (prefs.contains("rtsp_enabled") || prefs.contains("rtsp_url")) {
            prefs.edit().remove("rtsp_enabled").remove("rtsp_url").apply()
        }

        loadSavedPrinters()

        // Auto-reconnect to last-connected printer
        val lastSerial = prefs.getString("last_connected_serial", "") ?: ""
        val lastIp = prefs.getString("last_connected_ip", "") ?: ""
        val lastAccessCode = if (lastSerial.isNotBlank()) getSavedAccessCode(lastSerial) else ""
        Log.d("AutoReconnect", "init: serial=${lastSerial.isNotBlank()}, ip=${lastIp.isNotBlank()}, code=${lastAccessCode.isNotBlank()}")
        if (lastSerial.isNotBlank() && lastIp.isNotBlank() && lastAccessCode.isNotBlank()) {
            Log.d("AutoReconnect", "init: auto-reconnecting to $lastIp ($lastSerial)")
            _hasLastConnectedPrinter.value = true
            _isReconnecting.value = true
            _connectedSerialNumber.value = lastSerial
            loadPerPrinterSettings(lastSerial)
            connect(lastIp, lastAccessCode, lastSerial)
        }
    }

    fun setKeepConnectionInBackground(enabled: Boolean) {
        _keepConnectionInBackground.value = enabled
        prefs.edit().putBoolean("keep_connection_bg", enabled).apply()
        val app = getApplication<Application>()
        if (enabled && _connectionState.value == ConnectionState.Connected) {
            app.startForegroundService(Intent(app, ConnectionForegroundService::class.java))
        } else if (!enabled) {
            app.stopService(Intent(app, ConnectionForegroundService::class.java))
        }
    }

    fun setShowMainStream(enabled: Boolean) {
        _showMainStream.value = enabled
        prefs.edit().putBoolean("show_main_stream", enabled).apply()
    }

    fun setRtspEnabled(enabled: Boolean) {
        _rtspEnabled.value = enabled
        val serial = _connectedSerialNumber.value
        if (serial.isNotEmpty()) {
            prefs.edit().putBoolean("rtsp_enabled_$serial", enabled).apply()
        }
    }

    fun setRtspUrl(url: String) {
        _rtspUrl.value = url
        val serial = _connectedSerialNumber.value
        if (serial.isNotEmpty()) {
            prefs.edit().putString("rtsp_url_$serial", url).apply()
        }
    }

    fun setUseVlcForRtsp(enabled: Boolean) {
        _useVlcForRtsp.value = enabled
        prefs.edit().putBoolean("use_vlc_rtsp", enabled).apply()
    }

    fun setCustomPrinterName(name: String) {
        _customPrinterName.value = name
        val serial = _connectedSerialNumber.value
        if (serial.isNotEmpty()) {
            prefs.edit().putString("custom_name_$serial", name).apply()
            // Update the saved printer list so the connection screen reflects the name
            loadSavedPrinters()
        }
    }

    fun setCustomBgColor(color: Int?) {
        _customBgColor.value = color
        val serial = _connectedSerialNumber.value
        if (serial.isNotEmpty()) {
            if (color != null) {
                prefs.edit().putInt("bg_color_$serial", color).apply()
            } else {
                prefs.edit().remove("bg_color_$serial").apply()
            }
        }
    }

    private fun loadPerPrinterSettings(serial: String) {
        _rtspEnabled.value = prefs.getBoolean("rtsp_enabled_$serial", false)
        _rtspUrl.value = prefs.getString("rtsp_url_$serial", "") ?: ""
        _customBgColor.value = if (prefs.contains("bg_color_$serial")) prefs.getInt("bg_color_$serial", 0) else null
        _customPrinterName.value = prefs.getString("custom_name_$serial", "") ?: ""
    }

    fun setForceDarkMode(enabled: Boolean) {
        _forceDarkMode.value = enabled
        prefs.edit().putBoolean("force_dark_mode", enabled).apply()
    }

    fun setDebugLogging(enabled: Boolean) {
        _debugLogging.value = enabled
        prefs.edit().putBoolean("debug_logging", enabled).apply()
        mqttClient?.debugLogging = enabled
        client?.debugLogging = enabled
    }

    fun setAutoSavePrinter(enabled: Boolean) {
        _autoSavePrinter.value = enabled
        prefs.edit().putBoolean("auto_save_printer", enabled).apply()
    }

    fun setRedactLogs(enabled: Boolean) {
        _redactLogs.value = enabled
        prefs.edit().putBoolean("redact_logs", enabled).apply()
    }

    private fun saveCredentials(ip: String, accessCode: String, serialNumber: String) {
        prefs.edit()
            .putString("access_code_$serialNumber", accessCode)
            .apply()
    }

    private fun usesMjpegCamera(serial: String): Boolean =
        printerSeriesFromSerial(serial).usesMjpegCamera

    fun connect(ip: String, accessCode: String, serialNumber: String, savePrinter: Boolean = false) {
        Log.d("AutoReconnect", "connect() called: ip=$ip, serial=$serialNumber, state=${_connectionState.value}, userDisconnected=$userDisconnected")
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Connected
        ) {
            Log.d("AutoReconnect", "connect() skipped: already ${_connectionState.value}")
            return
        }

        // Clean up any stale stream/MQTT from a previous connection
        streamJob?.cancel()
        streamJob = null
        mqttJob?.cancel()
        mqttJob = null
        val oldCam = client
        val oldMqtt = mqttClient
        client = null
        mqttClient = null
        if (oldCam != null || oldMqtt != null) {
            viewModelScope.launch(Dispatchers.IO) {
                oldCam?.close()
                oldMqtt?.close()
            }
        }

        pendingSavePrinter = savePrinter
        userDisconnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        if (pendingSavePrinter) {
            saveCredentials(ip, accessCode, serialNumber)
        }
        _connectedIp.value = ip
        _connectedAccessCode.value = accessCode
        _connectedSerialNumber.value = serialNumber
        loadPerPrinterSettings(serialNumber)
        _connectionState.value = ConnectionState.Connecting
        _errorMessage.value = null

        // Persist last-connected printer for auto-reconnect (set flag after successful connection)
        prefs.edit()
            .putString("last_connected_serial", serialNumber)
            .putString("last_connected_ip", ip)
            .apply()

        if (usesMjpegCamera(serialNumber)) {
            _showMainStream.value = prefs.getBoolean("show_main_stream", true)
            val bambuClient = BambuCameraClient(ip, accessCode)
            bambuClient.debugLogging = _debugLogging.value
            client = bambuClient

            streamJob = viewModelScope.launch {
                try {
                    var frameCount = 0
                    var lastFpsTime = System.currentTimeMillis()

                    bambuClient.frameFlow().collect { bitmap ->
                        if (_connectionState.value != ConnectionState.Connected) {
                            _connectionState.value = ConnectionState.Connected
                            _hasLastConnectedPrinter.value = true
                            _isReconnecting.value = false
                            _mjpegCameraFailed.value = false
                            _noRouteToHost.value = null
                            reconnectRetryCount = 0
                            if (_keepConnectionInBackground.value) {
                                val app = getApplication<Application>()
                                app.startForegroundService(Intent(app, ConnectionForegroundService::class.java))
                            }
                            if (pendingSavePrinter) {
                                pendingSavePrinter = false
                                saveCurrentPrinter()
                            }
                        }
                        _frame.value = bitmap

                        frameCount++
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastFpsTime
                        if (elapsed >= 1000) {
                            _fps.value = frameCount * 1000f / elapsed
                            frameCount = 0
                            lastFpsTime = now
                        }
                    }
                    _connectionState.value = ConnectionState.Disconnected
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d("AutoReconnect", "MJPEG stream error: ${e.message}, userDisconnected=$userDisconnected, mqttConnected=${_isMqttConnected.value}")
                    if (!userDisconnected) {
                        if (e.isNoRouteToHost()) {
                            setNoRouteToHost(ip)
                        }
                        if (mjpegRetryCount > 0) {
                            _mjpegCameraFailed.value = true
                        }
                        _frame.value = null
                        _fps.value = 0f
                        if (_isMqttConnected.value || ftpClient != null) {
                            // MQTT or FTP still active — only retry the camera stream
                            scheduleMjpegRetry()
                        } else {
                            _errorMessage.value = "Connection to printer failed"
                            _connectionState.value = ConnectionState.Error
                            cleanupConnections()
                            scheduleReconnect()
                        }
                    }
                }
            }
        } else {
            val internalUrl = BambuCameraClient.buildRtspsUrl(ip, accessCode)
            Log.d("RTSP", "Non-MJPEG printer (serial=$serialNumber), auto-configuring RTSPS: $internalUrl")
            _internalRtspUrl.value = internalUrl
        }

        // Start MQTT in separate coroutine (non-fatal)
        val mqtt = BambuMqttClient(ip, accessCode, serialNumber)
        mqtt.debugLogging = _debugLogging.value
        mqttClient = mqtt
        mqtt.connect(viewModelScope)

        mqttJob = viewModelScope.launch {
            launch {
                mqtt.connected.collect {
                    _isMqttConnected.value = it
                    // Mark connected once MQTT is up (for all printer types)
                    if (it && _connectionState.value == ConnectionState.Connecting) {
                        _connectionState.value = ConnectionState.Connected
                        _hasLastConnectedPrinter.value = true
                        _isReconnecting.value = false
                        _noRouteToHost.value = null
                        reconnectRetryCount = 0
                        if (_keepConnectionInBackground.value) {
                            val app = getApplication<Application>()
                            app.startForegroundService(Intent(app, ConnectionForegroundService::class.java))
                        }
                        if (pendingSavePrinter) {
                            pendingSavePrinter = false
                            saveCurrentPrinter()
                        }
                    }
                }
            }
            launch {
                mqtt.connectionError.collect { error ->
                    if (error != null && !userDisconnected) {
                        Log.d("AutoReconnect", "MQTT error: $error")
                        if (error == "EHOSTUNREACH") {
                            setNoRouteToHost(ip)
                        }
                        _errorMessage.value = error
                        _connectionState.value = ConnectionState.Error
                        _hasLastConnectedPrinter.value = false
                        cleanupConnections()
                        if (error.contains("rejected", ignoreCase = true)) {
                            // Auth failure — clear saved credentials so we don't auto-retry
                            prefs.edit()
                                .remove("last_connected_serial")
                                .remove("last_connected_ip")
                                .apply()
                        } else {
                            scheduleReconnect()
                        }
                    }
                }
            }
            launch {
                mqtt.lightOn.collect { _isLightOn.value = it }
            }
            launch {
                mqtt.printerStatus.collect { _printerStatus.value = it }
            }
            launch {
                mqtt.mqttDataMessages.collect { _mqttDataMessages.value = it }
            }
        }
    }

    fun toggleLight(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.toggleLight(on)
        }
    }

    fun setNozzleTemperature(temp: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.setNozzleTemperature(temp)
        }
    }

    fun setBedTemperature(temp: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.setBedTemperature(temp)
        }
    }

    fun setFanSpeed(fan: Int, speedPwm: Int) {
        val fanName = when (fan) { 1 -> "part"; 2 -> "aux"; 3 -> "chamber"; else -> "unknown($fan)" }
        Log.d("BambuVM", "setFanSpeed: $fanName fan=$fan pwm=$speedPwm (${kotlin.math.round(speedPwm * 100f / 255f).toInt()}%) mqttClient=${if (mqttClient != null) "connected" else "null"}")
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.setFanSpeed(fan, speedPwm)
        }
    }

    fun setSpeedLevel(level: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.setSpeedLevel(level)
        }
    }

    fun setFilament(amsId: Int, trayId: Int, profile: FilamentProfile, colorHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.setFilament(amsId, trayId, profile, colorHex)
        }
    }

    fun sendPrinterActionCommand(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.sendPrinterActionCommand(command)
        }
    }

    fun loadSkipObjects() {
        val status = _printerStatus.value
        if (status.gcodeState != "RUNNING" || status.layerNum < 2) {
            _skipObjectsError.value = "Printer must be actively printing (layer 2+) to skip objects"
            return
        }
        val gcodeFile = status.gcodeFile
        if (!gcodeFile.endsWith(".gcode.3mf")) {
            _skipObjectsError.value = "Current print file is not a 3MF file"
            return
        }
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        if (ip.isBlank() || code.isBlank()) {
            _skipObjectsError.value = "Not connected"
            return
        }

        _skipObjectsLoading.value = true
        _skipObjectsError.value = null

        viewModelScope.launch(Dispatchers.IO) {
            var ftpClient: BambuFtpsClient? = null
            try {
                ftpClient = BambuFtpsClient(ip, code)
                ftpClient.connect()

                // List root to get remote file size and timestamp
                val rootFiles = ftpClient.listDirectory("/")
                val remoteEntry = rootFiles.find { it.name == gcodeFile }
                if (remoteEntry == null) {
                    _skipObjectsError.value = "File not found on printer: $gcodeFile"
                    return@launch
                }

                val cacheDir = File(getApplication<Application>().cacheDir, "skip_objects")
                cacheDir.mkdirs()
                val cacheFile = File(cacheDir, gcodeFile)

                // Check cache: matching size and timestamp
                val cacheHit = cacheFile.exists()
                    && cacheFile.length() == remoteEntry.size
                    && cacheFile.lastModified() == parseFtpTimestamp(remoteEntry.lastModified)

                if (cacheHit) {
                    Log.d("BambuVM", "Cache hit for 3MF: $gcodeFile (${cacheFile.length()} bytes)")
                    _skipObjectsLoadingMessage.value = "Loading from cache..."
                } else {
                    Log.d("BambuVM", "Downloading 3MF: /$gcodeFile (${remoteEntry.size} bytes, ts=${remoteEntry.lastModified})")
                    _skipObjectsLoadingMessage.value = "Downloading 3MF from printer..."
                    FileOutputStream(cacheFile).use { out ->
                        ftpClient.downloadFile("/$gcodeFile", out)
                    }
                    // Set local file timestamp to match remote
                    val remoteTs = parseFtpTimestamp(remoteEntry.lastModified)
                    if (remoteTs > 0) {
                        cacheFile.setLastModified(remoteTs)
                    }
                    Log.d("BambuVM", "Downloaded 3MF: ${cacheFile.length()} bytes")
                }

                val result = ThreeMfParser.extractObjects(cacheFile)
                Log.d("BambuVM", "Parsed ${result.objects.size} objects from 3MF: ${result.objects}")
                if (result.objects.size <= 1) {
                    _skipObjectsError.value = "Only one object on plate — nothing to skip"
                    return@launch
                }
                _skipObjectsList.value = result.objects
                _skipObjectsPlateImage.value = result.plateImage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BambuVM", "Failed to load skip objects", e)
                _skipObjectsError.value = "Failed to load objects: ${e.message}"
            } finally {
                try { ftpClient?.close() } catch (_: Exception) {}
                _skipObjectsLoading.value = false
            }
        }
    }

    /**
     * Parses FTP LIST timestamp strings like "Mar 23 14:48" or "Jan  1  2025" to epoch millis.
     * Returns 0 if unparseable.
     */
    private fun parseFtpTimestamp(dateStr: String): Long {
        val formats = arrayOf(
            java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.US),
            java.text.SimpleDateFormat("MMM dd  yyyy", java.util.Locale.US),
            java.text.SimpleDateFormat("MMM  d HH:mm", java.util.Locale.US),
            java.text.SimpleDateFormat("MMM  d  yyyy", java.util.Locale.US),
        )
        for (fmt in formats) {
            try {
                val date = fmt.parse(dateStr) ?: continue
                val cal = java.util.Calendar.getInstance()
                val parsed = java.util.Calendar.getInstance().apply { time = date }
                // FTP timestamps without year default to current year
                if (!dateStr.contains(Regex("\\d{4}"))) {
                    parsed.set(java.util.Calendar.YEAR, cal.get(java.util.Calendar.YEAR))
                    // If the parsed date is in the future, it's last year
                    if (parsed.after(cal)) {
                        parsed.add(java.util.Calendar.YEAR, -1)
                    }
                }
                return parsed.timeInMillis
            } catch (_: Exception) {}
        }
        Log.w("BambuVM", "Could not parse FTP timestamp: '$dateStr'")
        return 0
    }

    fun skipSelectedObjects(objectIds: List<Int>) {
        val status = _printerStatus.value
        if (status.gcodeState != "RUNNING" || status.layerNum < 2) return
        val validIds = objectIds.filter { it !in status.skippedObjects }
        if (validIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.skipObjects(validIds)
        }
    }

    fun clearSkipObjectsError() {
        _skipObjectsError.value = null
    }

    private fun cleanupConnections() {
        streamJob?.cancel()
        streamJob = null
        mjpegRetryJob?.cancel()
        mjpegRetryJob = null
        mqttJob?.cancel()
        mqttJob = null
        val cam = client
        val mqtt = mqttClient
        client = null
        mqttClient = null
        viewModelScope.launch(Dispatchers.IO) {
            cam?.close()
            mqtt?.close()
        }
        _frame.value = null
        _fps.value = 0f
        _isLightOn.value = null
        _isMqttConnected.value = false
        _mqttDataMessages.value = emptyList()
        _internalRtspUrl.value = ""
    }

    private fun fullCleanup() {
        cleanupConnections()
        mjpegRetryJob?.cancel()
        mjpegRetryJob = null
        mjpegRetryCount = 0
        _connectedSerialNumber.value = ""
        _connectedIp.value = ""
        _connectedAccessCode.value = ""
        _rtspEnabled.value = false
        _rtspUrl.value = ""
        _showMainStream.value = prefs.getBoolean("show_main_stream", true)
        _customBgColor.value = null
        _customPrinterName.value = ""
        _mjpegCameraFailed.value = false
        _noRouteToHost.value = null
    }

    private fun scheduleMjpegRetry() {
        if (userDisconnected) return
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        if (ip.isBlank() || code.isBlank()) return
        if (mjpegRetryCount >= maxReconnectRetries) {
            // Stop retrying camera but keep MQTT/dashboard alive
            _isReconnecting.value = false
            return
        }
        mjpegRetryCount++
        _isReconnecting.value = true
        mjpegRetryJob?.cancel()
        mjpegRetryJob = viewModelScope.launch {
            delay(3000)
            if (userDisconnected) return@launch
            val bambuClient = BambuCameraClient(ip, code)
            bambuClient.debugLogging = _debugLogging.value
            client = bambuClient
            try {
                var frameCount = 0
                var lastFpsTime = System.currentTimeMillis()
                bambuClient.frameFlow().collect { bitmap ->
                    if (_mjpegCameraFailed.value) {
                        _mjpegCameraFailed.value = false
                        _noRouteToHost.value = null
                        _isReconnecting.value = false
                        mjpegRetryCount = 0
                        if (_connectionState.value != ConnectionState.Connected) {
                            _connectionState.value = ConnectionState.Connected
                            _hasLastConnectedPrinter.value = true
                            reconnectRetryCount = 0
                            if (_keepConnectionInBackground.value) {
                                val app = getApplication<Application>()
                                app.startForegroundService(Intent(app, ConnectionForegroundService::class.java))
                            }
                            if (pendingSavePrinter) {
                                pendingSavePrinter = false
                                saveCurrentPrinter()
                            }
                        }
                    }
                    _frame.value = bitmap
                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsTime
                    if (elapsed >= 1000) {
                        _fps.value = frameCount * 1000f / elapsed
                        frameCount = 0
                        lastFpsTime = now
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!userDisconnected) {
                    _frame.value = null
                    _fps.value = 0f
                    scheduleMjpegRetry()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        Log.d("AutoReconnect", "scheduleReconnect: userDisconnected=$userDisconnected, retryCount=$reconnectRetryCount/$maxReconnectRetries")
        if (userDisconnected) return
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        val serial = _connectedSerialNumber.value
        if (ip.isBlank() || code.isBlank() || serial.isBlank()) {
            Log.d("AutoReconnect", "scheduleReconnect: skipped, missing credentials")
            return
        }
        if (reconnectRetryCount >= maxReconnectRetries) {
            Log.d("AutoReconnect", "scheduleReconnect: max retries exhausted")
            _isReconnecting.value = false
            cleanupConnections()
            _errorMessage.value = "Failed to reconnect after $maxReconnectRetries attempts"
            _connectionState.value = ConnectionState.Error
            return
        }
        _isReconnecting.value = true
        reconnectRetryCount++
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(3000)
            _connectionState.value = ConnectionState.Disconnected
            _errorMessage.value = null
            connect(ip, code, serial)
        }
    }

    fun reconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        val serial = _connectedSerialNumber.value
        if (ip.isBlank() || code.isBlank() || serial.isBlank()) return
        cleanupConnections()
        _rtspReconnectKey.value++
        reconnectRetryCount = 0
        mjpegRetryCount = 0
        _mjpegCameraFailed.value = false
        _noRouteToHost.value = null
        _isReconnecting.value = true
        _connectionState.value = ConnectionState.Disconnected
        _errorMessage.value = null
        connect(ip, code, serial)
    }

    fun retryIfNeeded() {
        Log.d("AutoReconnect", "retryIfNeeded: state=${_connectionState.value}, hasLastConnected=${_hasLastConnectedPrinter.value}, userDisconnected=$userDisconnected")
        if (_connectionState.value == ConnectionState.Error ||
            (_connectionState.value == ConnectionState.Disconnected && _hasLastConnectedPrinter.value)
        ) {
            val ip = _connectedIp.value.ifBlank { prefs.getString("last_connected_ip", "") ?: "" }
            val serial = _connectedSerialNumber.value.ifBlank { prefs.getString("last_connected_serial", "") ?: "" }
            val code = _connectedAccessCode.value.ifBlank { if (serial.isNotBlank()) getSavedAccessCode(serial) else "" }
            Log.d("AutoReconnect", "retryIfNeeded: ip=${ip.isNotBlank()}, serial=${serial.isNotBlank()}, code=${code.isNotBlank()}")
            if (ip.isNotBlank() && serial.isNotBlank() && code.isNotBlank()) {
                _hasLastConnectedPrinter.value = true
                reconnectRetryCount = 0
                _errorMessage.value = null
                _connectionState.value = ConnectionState.Disconnected
                _isReconnecting.value = true
                connect(ip, code, serial)
            }
        }
    }

    private fun stopForegroundService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, ConnectionForegroundService::class.java))
    }

    fun disconnect() {
        Log.d("AutoReconnect", "disconnect() called")
        userDisconnected = true
        pendingSavePrinter = false
        _demoMode.value = false
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
        reconnectRetryCount = 0
        fullCleanup()
        stopForegroundService()
        _errorMessage.value = null
        _connectionState.value = ConnectionState.Disconnected
        // Clear last-connected so we don't auto-reconnect next launch
        prefs.edit().remove("last_connected_serial").remove("last_connected_ip").apply()
        _hasLastConnectedPrinter.value = false
    }

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    fun enterDemoMode() {
        _demoMode.value = true
        _connectedSerialNumber.value = "DEMO000000000"
        _connectionState.value = ConnectionState.Connected
    }

    fun startDiscovery() {
        ssdpClient.startDiscovery(getApplication(), viewModelScope)
    }

    fun stopDiscovery() {
        ssdpClient.stopDiscovery()
    }

    private fun loadSavedPrinters() {
        val serialsCsv = prefs.getString("saved_printer_serials", "") ?: ""
        if (serialsCsv.isBlank()) {
            _savedPrinters.value = emptyList()
            return
        }
        val serials = serialsCsv.split(",").filter { it.isNotBlank() }
        _savedPrinters.value = serials.mapNotNull { serial ->
            val ip = prefs.getString("saved_ip_$serial", "") ?: ""
            val accessCode = prefs.getString("access_code_$serial", "") ?: ""
            if (ip.isBlank()) return@mapNotNull null
            SavedPrinter(
                ip = ip,
                serialNumber = serial,
                accessCode = accessCode,
                deviceName = (prefs.getString("custom_name_$serial", "") ?: "").ifBlank {
                    prefs.getString("saved_name_$serial", "") ?: ""
                },
            )
        }
    }

    fun saveCurrentPrinter() {
        val serial = _connectedSerialNumber.value
        val ip = _connectedIp.value
        if (serial.isBlank() || ip.isBlank()) return

        val deviceName = discoveredPrinters.value
            .firstOrNull { it.serialNumber == serial }?.deviceName ?: ""

        val serialsCsv = prefs.getString("saved_printer_serials", "") ?: ""
        val serials = serialsCsv.split(",").filter { it.isNotBlank() }.toMutableSet()
        serials.add(serial)

        prefs.edit()
            .putString("saved_printer_serials", serials.joinToString(","))
            .putString("saved_ip_$serial", ip)
            .putString("saved_name_$serial", deviceName)
            .apply()

        loadSavedPrinters()
    }

    fun deleteSavedPrinter(serial: String) {
        val serialsCsv = prefs.getString("saved_printer_serials", "") ?: ""
        val serials = serialsCsv.split(",").filter { it.isNotBlank() && it != serial }

        prefs.edit()
            .putString("saved_printer_serials", serials.joinToString(","))
            .remove("saved_ip_$serial")
            .remove("saved_name_$serial")
            .remove("rtsp_enabled_$serial")
            .remove("rtsp_url_$serial")
            .remove("bg_color_$serial")
            .remove("custom_name_$serial")
            .apply()

        loadSavedPrinters()
    }

    fun openFileManager() {
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        if (ip.isBlank() || code.isBlank()) return

        _ftpLoading.value = true
        _ftpError.value = null
        _fileList.value = emptyList()
        _currentFtpPath.value = "/"

        viewModelScope.launch {
            try {
                val client = BambuFtpsClient(ip, code)
                client.connect()
                ftpClient = client
                val files = client.listDirectory("/")
                _fileList.value = files.sortedWith(compareByDescending<FtpFileEntry> { it.isDirectory }.thenBy { it.name })
                _currentFtpPath.value = "/"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ftpError.value = "Failed to connect: ${e.message}"
            } finally {
                _ftpLoading.value = false
            }
        }
    }

    fun navigateTo(dirName: String) {
        val client = ftpClient ?: return
        _ftpLoading.value = true
        _ftpError.value = null

        val newPath = if (_currentFtpPath.value.endsWith("/")) {
            _currentFtpPath.value + dirName
        } else {
            _currentFtpPath.value + "/" + dirName
        }

        viewModelScope.launch {
            try {
                val files = client.listDirectory(newPath)
                _fileList.value = files.sortedWith(compareByDescending<FtpFileEntry> { it.isDirectory }.thenBy { it.name })
                _currentFtpPath.value = newPath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ftpError.value = "Failed to list directory: ${e.message}"
            } finally {
                _ftpLoading.value = false
            }
        }
    }

    fun navigateUp() {
        val current = _currentFtpPath.value
        if (current == "/") return
        val parent = current.substringBeforeLast("/").ifEmpty { "/" }
        val client = ftpClient ?: return
        _ftpLoading.value = true
        _ftpError.value = null

        viewModelScope.launch {
            try {
                val files = client.listDirectory(parent)
                _fileList.value = files.sortedWith(compareByDescending<FtpFileEntry> { it.isDirectory }.thenBy { it.name })
                _currentFtpPath.value = parent
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ftpError.value = "Failed to navigate up: ${e.message}"
            } finally {
                _ftpLoading.value = false
            }
        }
    }

    fun downloadFile(entry: FtpFileEntry) {
        val client = ftpClient ?: return
        val currentPath = _currentFtpPath.value
        val remotePath = if (currentPath.endsWith("/")) "$currentPath${entry.name}" else "$currentPath/${entry.name}"

        _ftpTransferName.value = "Downloading ${entry.name}"
        _ftpTransferProgress.value = 0f

        ftpTransferJob = viewModelScope.launch {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, entry.name)
                FileOutputStream(file).use { output ->
                    client.downloadFile(remotePath, output, totalSize = entry.size) { bytesRead, totalBytes ->
                        if (totalBytes > 0) {
                            _ftpTransferProgress.value = (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                        }
                    }
                }
                _ftpTransferName.value = "Saved to Downloads/${entry.name}"
                _ftpTransferProgress.value = null
            } catch (e: CancellationException) {
                _ftpTransferName.value = null
                _ftpTransferProgress.value = null
            } catch (e: Exception) {
                _ftpError.value = "Download failed: ${e.message}"
                _ftpTransferName.value = null
                _ftpTransferProgress.value = null
            } finally {
                ftpTransferJob = null
            }
        }
    }

    fun uploadFile(uri: Uri) {
        val client = ftpClient ?: return
        val app = getApplication<Application>()

        // Resolve display name and file size from content resolver
        var fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "upload"
        var fileSize = -1L
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { fileName = it }
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
            }
        }

        val lowerName = fileName.lowercase()
        if (!lowerName.endsWith(".3mf") && !lowerName.endsWith(".gcode")) {
            _ftpError.value = "Only .3mf and .gcode files can be uploaded"
            return
        }

        val currentPath = _currentFtpPath.value
        val remotePath = if (currentPath.endsWith("/")) "$currentPath$fileName" else "$currentPath/$fileName"

        _ftpTransferName.value = "Uploading $fileName"
        _ftpTransferProgress.value = 0f
        _ftpError.value = null

        ftpTransferJob = viewModelScope.launch {
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    client.uploadFile(remotePath, input, totalSize = fileSize) { bytesWritten, totalBytes ->
                        if (totalBytes > 0) {
                            _ftpTransferProgress.value = (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f)
                        }
                    }
                } ?: throw IOException("Cannot open file")
                _ftpTransferName.value = null
                _ftpTransferProgress.value = null
                // Refresh listing
                _ftpLoading.value = true
                val files = client.listDirectory(currentPath)
                _fileList.value = files.sortedWith(compareByDescending<FtpFileEntry> { it.isDirectory }.thenBy { it.name })
                _currentFtpPath.value = currentPath
            } catch (e: CancellationException) {
                // Cancelled by user
            } catch (e: Exception) {
                _ftpError.value = "Upload failed: ${e.message}"
            } finally {
                ftpTransferJob = null
                _ftpTransferName.value = null
                _ftpTransferProgress.value = null
                _ftpLoading.value = false
            }
        }
    }

    fun cancelTransfer() {
        ftpClient?.cancelTransfer()
        ftpTransferJob?.cancel()
        ftpTransferJob = null
        _ftpTransferName.value = null
        _ftpTransferProgress.value = null
    }

    fun deleteFtpFile(entry: FtpFileEntry) {
        val client = ftpClient ?: return
        val currentPath = _currentFtpPath.value
        val remotePath = if (currentPath.endsWith("/")) "$currentPath${entry.name}" else "$currentPath/${entry.name}"

        viewModelScope.launch {
            try {
                client.deleteFile(remotePath)
                // Refresh listing
                val files = client.listDirectory(currentPath)
                _fileList.value = files.sortedWith(compareByDescending<FtpFileEntry> { it.isDirectory }.thenBy { it.name })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ftpError.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun clearFtpTransferStatus() {
        _ftpTransferName.value = null
        _ftpTransferProgress.value = null
    }

    fun clearFtpError() {
        _ftpError.value = null
    }

    fun closeFileManager() {
        val ftp = ftpClient
        ftpClient = null
        _fileList.value = emptyList()
        _currentFtpPath.value = "/"
        _ftpError.value = null
        _ftpLoading.value = false
        _ftpTransferName.value = null
        _ftpTransferProgress.value = null
        viewModelScope.launch(Dispatchers.IO) {
            ftp?.close()
        }

        // Reconnect camera + MQTT if the connection dropped while FTP was active
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        val serial = _connectedSerialNumber.value
        if (ip.isNotBlank() && code.isNotBlank() && serial.isNotBlank() &&
            _connectionState.value != ConnectionState.Connected
        ) {
            // Reset state so connect() doesn't bail early
            _connectionState.value = ConnectionState.Disconnected
            _errorMessage.value = null
            connect(ip, code, serial)
        }
    }

    // --- Timelapse functions ---

    fun openTimelapse() {
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        if (ip.isBlank() || code.isBlank()) return

        _timelapseLoading.value = true
        _timelapseError.value = null
        _timelapseFileList.value = emptyList()
        _timelapseThumbnails.value = emptyMap()

        viewModelScope.launch {
            try {
                val client = BambuFtpsClient(ip, code)
                client.connect()
                timelapseFtpClient = client
                val files = client.listDirectory("/timelapse")
                    .filter { !it.isDirectory && it.name.lowercase().endsWith(".avi") }
                    .sortedByDescending { it.lastModified }
                _timelapseFileList.value = files
                loadThumbnails(files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _timelapseError.value = "Failed to list recordings: ${e.message}"
            } finally {
                _timelapseLoading.value = false
            }
        }
    }

    private fun loadThumbnails(files: List<FtpFileEntry>) {
        val app = getApplication<Application>()

        // Load cached thumbnails first, collect which ones need downloading
        val uncached = mutableListOf<FtpFileEntry>()
        for (entry in files) {
            val baseName = entry.name.substringBeforeLast(".")
            val cachedFile = File(app.cacheDir, "thumb_${baseName}.jpg")
            if (cachedFile.exists() && cachedFile.length() > 0) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    _timelapseThumbnails.value = _timelapseThumbnails.value + (entry.name to bitmap)
                    continue
                }
            }
            uncached.add(entry)
        }

        if (uncached.isEmpty()) return

        timelapseThumbnailJob = viewModelScope.launch(Dispatchers.IO) {
            val thumbClient = try {
                BambuFtpsClient(_connectedIp.value, _connectedAccessCode.value).also { it.connect() }
            } catch (_: Exception) {
                return@launch
            }
            try {
                // List available thumbnails on the printer
                val remoteThumbs = try {
                    thumbClient.listDirectory("/timelapse/thumbnail")
                        .filter { !it.isDirectory && it.name.lowercase().endsWith(".jpg") }
                        .map { it.name }
                        .toSet()
                } catch (_: Exception) {
                    emptySet()
                }

                for (entry in uncached) {
                    val baseName = entry.name.substringBeforeLast(".")
                    val thumbName = "${baseName}.jpg"
                    if (thumbName !in remoteThumbs) continue
                    downloadThumbnail(entry, baseName, app, thumbClient)
                }
            } finally {
                thumbClient.close()
            }
        }
    }

    private suspend fun downloadThumbnail(entry: FtpFileEntry, baseName: String, app: Application, thumbClient: BambuFtpsClient) {
        val cachedFile = File(app.cacheDir, "thumb_${baseName}.jpg")
        try {
            val remotePath = "/timelapse/thumbnail/${baseName}.jpg"
            FileOutputStream(cachedFile).use { output ->
                thumbClient.downloadFile(remotePath, output, totalSize = -1) { _, _ -> }
            }
            if (cachedFile.exists() && cachedFile.length() > 0) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    _timelapseThumbnails.value = _timelapseThumbnails.value + (entry.name to bitmap)
                }
            }
        } catch (_: Exception) {
            // Thumbnail download failed, skip
        }
    }

    fun playVideo(entry: FtpFileEntry) {
        // Cancel thumbnail loading first — we need the printer's FTPS capacity
        timelapseThumbnailJob?.cancel()
        timelapseThumbnailJob = null

        val app = getApplication<Application>()
        timelapseDownloadCancelled = false
        _timelapseDownloadName.value = entry.name
        _timelapseDownloadProgress.value = 0f

        val cachedFile = File(app.cacheDir, "timelapse_${entry.name}")

        // Use cached file if it matches the expected size
        if (cachedFile.exists() && cachedFile.length() == entry.size) {
            Log.d("PlayVideo", "Using cached file: ${cachedFile.name} (${cachedFile.length()} bytes)")
            _timelapseDownloadProgress.value = null
            _timelapseDownloadName.value = null
            _timelapsePlaybackFile.value = cachedFile
            return
        }

        timelapseDownloadJob = viewModelScope.launch {
            try {
                val dlClient = BambuFtpsClient(_connectedIp.value, _connectedAccessCode.value)
                timelapseDownloadClient = dlClient
                dlClient.connect()

                Log.d("PlayVideo", "Downloading ${entry.name} (${entry.size} bytes)")
                FileOutputStream(cachedFile).use { output ->
                    dlClient.downloadFile(
                        "/timelapse/${entry.name}",
                        output,
                        totalSize = entry.size,
                    ) { bytesRead, totalBytes ->
                        if (timelapseDownloadCancelled) return@downloadFile
                        if (totalBytes > 0) {
                            _timelapseDownloadProgress.value = (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                        }
                    }
                }
                if (!timelapseDownloadCancelled) {
                    Log.d("PlayVideo", "Download complete (${cachedFile.length()} bytes)")
                    _timelapseDownloadProgress.value = null
                    _timelapseDownloadName.value = null
                    _timelapsePlaybackFile.value = cachedFile
                }
            } catch (e: CancellationException) {
                // Cancelled by user
            } catch (e: Exception) {
                if (!timelapseDownloadCancelled) {
                    Log.e("PlayVideo", "Download failed", e)
                    _timelapseError.value = "Download failed: ${e.message}"
                    _timelapseDownloadProgress.value = null
                    _timelapseDownloadName.value = null
                }
            } finally {
                timelapseDownloadJob = null
                timelapseDownloadClient?.close()
                timelapseDownloadClient = null
            }
        }
    }

    fun cancelTimelapseDownload() {
        // Set flag first to prevent progress callbacks from re-setting state
        timelapseDownloadCancelled = true
        _timelapseDownloadProgress.value = null
        _timelapseDownloadName.value = null
        // Close the data socket to unblock the blocking read, then cancel the job
        timelapseDownloadClient?.cancelTransfer()
        timelapseDownloadJob?.cancel()
        timelapseDownloadJob = null
    }

    fun clearPlaybackFile() {
        _timelapsePlaybackFile.value = null
        cancelTimelapseDownload()
    }

    fun clearTimelapseError() {
        _timelapseError.value = null
    }

    fun closeTimelapse() {
        timelapseDownloadJob?.cancel()
        timelapseDownloadJob = null
        timelapseThumbnailJob?.cancel()
        timelapseThumbnailJob = null
        val ftp = timelapseFtpClient
        timelapseFtpClient = null
        _timelapseFileList.value = emptyList()
        _timelapseThumbnails.value = emptyMap()
        _timelapseLoading.value = false
        _timelapseError.value = null
        _timelapseDownloadProgress.value = null
        _timelapseDownloadName.value = null
        _timelapsePlaybackFile.value = null
        viewModelScope.launch(Dispatchers.IO) {
            ftp?.close()
        }

        // Reconnect camera + MQTT if dropped during FTPS operations
        val ip = _connectedIp.value
        val code = _connectedAccessCode.value
        val serial = _connectedSerialNumber.value
        if (ip.isNotBlank() && code.isNotBlank() && serial.isNotBlank() &&
            _connectionState.value != ConnectionState.Connected
        ) {
            _connectionState.value = ConnectionState.Disconnected
            _errorMessage.value = null
            connect(ip, code, serial)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ssdpClient.stopDiscovery()
        closeFileManager()
        closeTimelapse()
        // Clean up connections but preserve last_connected prefs for auto-reconnect on next launch
        cleanupConnections()
        stopForegroundService()
    }

    private fun setNoRouteToHost(ip: String) {
        _noRouteToHost.value = "No route to host, $ip.\nPrinter off or disconnected from WiFi?"
    }

    private fun Exception.isNoRouteToHost(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { it.message?.contains("EHOSTUNREACH") == true }
}
