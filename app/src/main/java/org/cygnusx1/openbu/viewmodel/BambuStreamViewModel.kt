package org.cygnusx1.openbu.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.cygnusx1.openbu.data.FilamentProfile
import org.cygnusx1.openbu.data.ProxyConfig
import org.cygnusx1.openbu.data.printerSeriesFromSerial
import org.cygnusx1.openbu.network.BambuCameraClient
import org.cygnusx1.openbu.network.BambuFtpsClient
import org.cygnusx1.openbu.network.Socks5TlsSocket
import org.cygnusx1.openbu.network.BambuMqttClient
import org.cygnusx1.openbu.network.BambuSsdpClient
import org.cygnusx1.openbu.network.DiscoveredPrinter
import org.cygnusx1.openbu.network.FtpFileEntry
import org.cygnusx1.openbu.network.PrintableObject
import org.cygnusx1.openbu.network.PrinterStatus
import org.cygnusx1.openbu.network.SavedPrinter
import org.cygnusx1.openbu.network.ThreeMfParser
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

    // Global relay settings
    private val _relayEnabled = MutableStateFlow(false)
    val relayEnabled: StateFlow<Boolean> = _relayEnabled.asStateFlow()

    private val _relayHost = MutableStateFlow("")
    val relayHost: StateFlow<String> = _relayHost.asStateFlow()

    private val _relayPort = MutableStateFlow("1080")
    val relayPort: StateFlow<String> = _relayPort.asStateFlow()

    private val _relayUsername = MutableStateFlow("")
    val relayUsername: StateFlow<String> = _relayUsername.asStateFlow()

    private val _relayPassword = MutableStateFlow("")
    val relayPassword: StateFlow<String> = _relayPassword.asStateFlow()

    // Per-printer relay override: true = skip relay for this printer
    private val _relayOverride = MutableStateFlow(false)
    val relayOverride: StateFlow<Boolean> = _relayOverride.asStateFlow()

    /** Returns the effective ProxyConfig for the current connection, or null if relay is off/overridden. */
    private fun effectiveProxyConfig(serial: String): ProxyConfig? {
        if (!_relayEnabled.value) return null
        // Per-printer override disables relay for this printer
        if (prefs.getBoolean("relay_override_$serial", false)) return null
        val host = _relayHost.value
        val port = _relayPort.value.toIntOrNull() ?: return null
        val user = _relayUsername.value
        val pass = _relayPassword.value
        if (host.isBlank() || user.isBlank() || pass.isBlank()) return null
        return ProxyConfig(host, port, user, pass)
    }

    /** Creates a socket factory lambda that tunnels through SOCKS5, or null for direct. */
    private fun proxySocketFactory(config: ProxyConfig?): ((String, Int) -> java.net.Socket)? =
        if (config != null) { host, port -> Socks5TlsSocket.connect(config, host, port) } else null

    /** Exposed for RTSP player in MainActivity. Updated when connect() is called. */
    private val _currentProxyConfig = MutableStateFlow<ProxyConfig?>(null)
    val currentProxyConfig: StateFlow<ProxyConfig?> = _currentProxyConfig.asStateFlow()

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

    fun captureLogcat(filter: String? = null) {
        _logcatText.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pid = android.os.Process.myPid()
                val command = if (filter != null) {
                    arrayOf("logcat", "-d", "--pid=$pid", "-s", filter)
                } else {
                    arrayOf("logcat", "-d", "--pid=$pid")
                }
                val process = Runtime.getRuntime().exec(command)
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
    // No connection path auto-retries any more: every attempt — manual entry, auto-discovered/
    // saved printer, or a dashboard drop/stale-socket reconnect — makes a single attempt and then
    // surfaces a Retry button (ConnectionScreen) or Reconnect button (Dashboard) for the user to
    // tap, rather than looping on its own. This is the same one-shot behaviour manual entry always
    // had; it now applies everywhere. (maxReconnectRetries is still used for the camera-only MJPEG
    // substream retry, which is independent of the MQTT connection.)
    private var isManualConnection = false
    private val effectiveMaxRetries: Int
        get() = 0
    private var mjpegRetryCount = 0
    private var mjpegRetryJob: Job? = null

    // Timestamp of the last MQTT status report. Used on resume to tell a live connection from one
    // that's nominally "Connected" but whose socket silently died while the app was backgrounded.
    @Volatile private var lastReportAtMs = 0L
    private val mqttStaleMs = 30_000L

    // Whether MQTT has actually connected at least once in the current session. Guards the
    // resume-time "MQTT is down" reconnect (retryIfNeeded): for MJPEG printers the camera marks
    // the connection Connected before MQTT is up, so without this flag a resume during that window
    // — or any printer that streams video but can't reach MQTT — would needlessly tear down a
    // working camera on every resume. Reset on each fresh connect.
    private var mqttEverConnected = false

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

    /**
     * Stable MQTT client id for this install, persisted on first use and cached in memory.
     * Reusing it across reconnects lets the broker drop our previous lingering session instead of
     * counting the reconnect as a second client, which would otherwise hit the printer's
     * 2-connection limit (one slot is often held by OrcaSlicer) and leave the app spinning until
     * the old session times out broker-side.
     */
    private val mqttClientId: String by lazy {
        prefs.getString("mqtt_client_id", null) ?: run {
            val id = "openbu_${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}"
            prefs.edit().putString("mqtt_client_id", id).apply()
            id
        }
    }

    init {
        _showMainStream.value = prefs.getBoolean("show_main_stream", true)
        _forceDarkMode.value = prefs.getBoolean("force_dark_mode", false)
        _debugLogging.value = prefs.getBoolean("debug_logging", false)
        _autoSavePrinter.value = prefs.getBoolean("auto_save_printer", true)
        _redactLogs.value = prefs.getBoolean("redact_logs", true)
        _relayEnabled.value = prefs.getBoolean("relay_enabled", false)
        _relayHost.value = prefs.getString("relay_host", "") ?: ""
        _relayPort.value = prefs.getString("relay_port", "1080") ?: "1080"
        _relayUsername.value = prefs.getString("relay_username", "") ?: ""
        _relayPassword.value = prefs.getString("relay_password", "") ?: ""
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
        _relayOverride.value = prefs.getBoolean("relay_override_$serial", false)
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

    fun setRelayEnabled(enabled: Boolean) {
        _relayEnabled.value = enabled
        prefs.edit().putBoolean("relay_enabled", enabled).apply()
        if (_connectionState.value == ConnectionState.Connected ||
            _connectionState.value == ConnectionState.Connecting
        ) {
            reconnect()
        }
    }

    fun setRelayHost(host: String) {
        _relayHost.value = host
        prefs.edit().putString("relay_host", host).apply()
    }

    fun setRelayPort(port: String) {
        _relayPort.value = port
        prefs.edit().putString("relay_port", port).apply()
    }

    fun setRelayUsername(username: String) {
        _relayUsername.value = username
        prefs.edit().putString("relay_username", username).apply()
    }

    fun setRelayPassword(password: String) {
        _relayPassword.value = password
        prefs.edit().putString("relay_password", password).apply()
    }

    fun setRelayOverride(disabled: Boolean) {
        _relayOverride.value = disabled
        val serial = _connectedSerialNumber.value
        if (serial.isNotEmpty()) {
            prefs.edit().putBoolean("relay_override_$serial", disabled).apply()
        }
    }

    private fun usesMjpegCamera(serial: String): Boolean =
        printerSeriesFromSerial(serial).usesMjpegCamera

    fun connect(ip: String, accessCode: String, serialNumber: String, savePrinter: Boolean = false, manualMode: Boolean = false) {
        Log.d("AutoReconnect", "connect() called: ip=$ip, serial=$serialNumber, state=${_connectionState.value}, userDisconnected=$userDisconnected, manualMode=$manualMode")
        isManualConnection = manualMode
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
        // Note: the printer (credentials + saved list) is only persisted once the
        // connection actually succeeds — see saveCurrentPrinter().
        _connectedIp.value = ip
        _connectedAccessCode.value = accessCode
        _connectedSerialNumber.value = serialNumber
        loadPerPrinterSettings(serialNumber)
        val proxyConfig = effectiveProxyConfig(serialNumber)
        _currentProxyConfig.value = proxyConfig
        _connectionState.value = ConnectionState.Connecting
        _errorMessage.value = null

        // Persist last-connected printer for auto-reconnect (set flag after successful connection)
        prefs.edit()
            .putString("last_connected_serial", serialNumber)
            .putString("last_connected_ip", ip)
            .apply()

        if (usesMjpegCamera(serialNumber)) {
            _showMainStream.value = prefs.getBoolean("show_main_stream", true)
            val cameraSocketFactory = proxySocketFactory(proxyConfig)
            val bambuClient = BambuCameraClient(
                ip, accessCode,
                rawSocketFactory = if (cameraSocketFactory != null) {
                    { cameraSocketFactory(ip, 6000) }
                } else null,
            )

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
                            // Once connected, a later drop should auto-retry like any other
                            // printer — the no-retry rule only applies to the first attempt.
                            isManualConnection = false
                            _isReconnecting.value = false
                            _mjpegCameraFailed.value = false
                            _noRouteToHost.value = null
                            reconnectRetryCount = 0
                            // Note: saving the printer is deferred until a verified MQTT
                            // report arrives (see the reportReceived collector), not on the
                            // first camera frame — which only proves IP + access code.
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
                        if (_isMqttConnected.value || ftpClient != null || mqttClient != null) {
                            // MQTT connected, connecting, or FTP active — only retry the camera stream
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
        mqttEverConnected = false
        val mqtt = BambuMqttClient(ip, accessCode, serialNumber, clientId = mqttClientId, rawSocketFactory = proxySocketFactory(proxyConfig))
        mqtt.debugLogging = _debugLogging.value
        mqttClient = mqtt
        mqtt.connect(viewModelScope)

        mqttJob = viewModelScope.launch {
            launch {
                mqtt.connected.collect {
                    _isMqttConnected.value = it
                    if (it) mqttEverConnected = true
                    // Mark connected once MQTT is up (for all printer types)
                    if (it && _connectionState.value == ConnectionState.Connecting) {
                        _connectionState.value = ConnectionState.Connected
                        _hasLastConnectedPrinter.value = true
                        // Once connected, a later drop should auto-retry like any other
                        // printer — the no-retry rule only applies to the first attempt.
                        isManualConnection = false
                        _isReconnecting.value = false
                        _noRouteToHost.value = null
                        reconnectRetryCount = 0
                        // Saving is deferred to the reportReceived collector: a CONNACK only
                        // proves the access code; the serial isn't validated until a report
                        // arrives on device/<serial>/report.
                    }
                }
            }
            launch {
                mqtt.connectionError.collect { error ->
                    if (error != null && !userDisconnected) {
                        Log.d("AutoReconnect", "MQTT error: $error")
                        if (error == "EHOSTUNREACH") {
                            // "EHOSTUNREACH" is an internal signal, not user-facing text.
                            // Show the same friendly message setNoRouteToHost uses on the
                            // dashboard, since this path routes to the ConnectionScreen.
                            setNoRouteToHost(ip)
                            _errorMessage.value = noRouteToHostMessage(ip)
                        } else {
                            _errorMessage.value = error
                        }
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
                mqtt.printerStatus.collect {
                    _printerStatus.value = it
                    lastReportAtMs = System.currentTimeMillis()
                }
            }
            launch {
                mqtt.mqttDataMessages.collect { _mqttDataMessages.value = it }
            }
            launch {
                // Persist the printer only once we've received a real report for this serial.
                // A camera frame / CONNACK only proves IP + access code; a report proves the
                // serial's subscription is valid (a wrong serial gets dropped, no report).
                mqtt.reportReceived.collect { received ->
                    if (received && pendingSavePrinter) {
                        pendingSavePrinter = false
                        saveCurrentPrinter()
                    }
                }
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
        // When gcode_file is a plate reference (e.g. "plate_1.gcode"), use subtask_name instead
        val gcodeFile = if (Regex("""plate_\d{1,2}\.gcode$""").containsMatchIn(status.gcodeFile)
            && status.subtaskName.isNotEmpty()
        ) {
            "${status.subtaskName}.gcode.3mf"
        } else {
            status.gcodeFile
        }
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
                ftpClient = BambuFtpsClient(ip, code, rawSocketFactory = proxySocketFactory(effectiveProxyConfig(_connectedSerialNumber.value)))
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
        mqttEverConnected = false
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
            val retrySocketFactory = proxySocketFactory(effectiveProxyConfig(_connectedSerialNumber.value))
            val bambuClient = BambuCameraClient(
                ip, code,
                rawSocketFactory = if (retrySocketFactory != null) {
                    { retrySocketFactory(ip, 6000) }
                } else null,
            )
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
                            // Once connected, a later drop should auto-retry like any other
                            // printer — the no-retry rule only applies to the first attempt.
                            isManualConnection = false
                            reconnectRetryCount = 0
                            // Saving is deferred to the reportReceived collector (verified
                            // MQTT data), not the camera stream which only proves IP + code.
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
        if (reconnectRetryCount >= effectiveMaxRetries) {
            Log.d("AutoReconnect", "scheduleReconnect: max retries exhausted")
            _isReconnecting.value = false
            cleanupConnections()
            _errorMessage.value = "Couldn't connect to the printer.\n\n" +
                "Check that the IP address and serial number are correct. Make sure LAN Mode " +
                "and Developer Mode are both enabled in the printer's network settings."
            _connectionState.value = ConnectionState.Error
            if (isManualConnection) {
                // A manual attempt that never connected shouldn't be auto-reconnected on
                // resume or relaunch — keep the user on the manual entry screen.
                _hasLastConnectedPrinter.value = false
                prefs.edit()
                    .remove("last_connected_serial")
                    .remove("last_connected_ip")
                    .apply()
            }
            return
        }
        _isReconnecting.value = true
        reconnectRetryCount++
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(3000)
            _connectionState.value = ConnectionState.Disconnected
            _errorMessage.value = null
            connect(ip, code, serial, savePrinter = pendingSavePrinter, manualMode = isManualConnection)
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
        connect(ip, code, serial, savePrinter = pendingSavePrinter, manualMode = isManualConnection)
    }

    fun retryIfNeeded() {
        Log.d("AutoReconnect", "retryIfNeeded: state=${_connectionState.value}, hasLastConnected=${_hasLastConnectedPrinter.value}, userDisconnected=$userDisconnected")
        val reportAgeMs = System.currentTimeMillis() - lastReportAtMs
        // Only auto-reconnect on resume if we're returning to an active session (the
        // dashboard). If the user is sitting on the connect screen — which is exactly when
        // hasLastConnectedPrinter is false — leave them there instead of jumping to the
        // dashboard.
        if (!_hasLastConnectedPrinter.value) return
        // A connect attempt is already in flight — don't interrupt it.
        if (_connectionState.value == ConnectionState.Connecting) return

        // Refresh when we're not connected, OR when we're nominally Connected but the MQTT
        // socket went stale while backgrounded (no report within mqttStaleMs). The latter is the
        // common "reopened the app and it isn't refreshing" case: the read loop is blocked on a
        // half-dead socket, so the state still reads Connected and nothing reconnects on its own.
        val stale = _connectionState.value == ConnectionState.Connected &&
            reportAgeMs > mqttStaleMs
        // Ground truth: the MQTT socket is down while we still hold a session (state Connected).
        // The socket frequently dies within a few seconds of backgrounding ("Software caused
        // connection abort"), well under mqttStaleMs — so the report-age heuristic alone misses it
        // and the dashboard sits on a dead connection until a manual reconnect. _isMqttConnected
        // flips false the moment the read loop exits, so trust it directly — but only once MQTT has
        // actually been up this session (mqttEverConnected), so we don't tear down a working camera
        // during the initial window where the camera marks us Connected before MQTT finishes.
        val mqttDown = _connectionState.value == ConnectionState.Connected &&
            mqttEverConnected &&
            !_isMqttConnected.value
        val needsRefresh = _connectionState.value == ConnectionState.Error ||
            _connectionState.value == ConnectionState.Disconnected ||
            stale ||
            mqttDown
        if (!needsRefresh) return

        val ip = _connectedIp.value.ifBlank { prefs.getString("last_connected_ip", "") ?: "" }
        val serial = _connectedSerialNumber.value.ifBlank { prefs.getString("last_connected_serial", "") ?: "" }
        val code = _connectedAccessCode.value.ifBlank { if (serial.isNotBlank()) getSavedAccessCode(serial) else "" }
        Log.d("AutoReconnect", "retryIfNeeded: refreshing (stale=$stale), ip=${ip.isNotBlank()}, serial=${serial.isNotBlank()}, code=${code.isNotBlank()}")
        if (ip.isNotBlank() && serial.isNotBlank() && code.isNotBlank()) {
            _hasLastConnectedPrinter.value = true
            reconnectRetryCount = 0
            mjpegRetryCount = 0
            // This is a dashboard reconnect, never a connection-screen attempt — clear the manual
            // flag so the full retry count applies even if the session began as a manual entry.
            isManualConnection = false
            _errorMessage.value = null
            // Tear down the stale connection so connect() doesn't bail on a lingering Connected
            // state and so the dead sockets are closed before we rebuild.
            cleanupConnections()
            _connectionState.value = ConnectionState.Disconnected
            _isReconnecting.value = true
            connect(ip, code, serial)
        }
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

        // Prefer the live SSDP name, but never clobber a previously-saved name with blank when
        // discovery hasn't found this printer yet (e.g. saved via manual entry or reconnect with
        // no active discovery) — otherwise the connection screen falls back to "Bambu Lab Printer".
        val deviceName = (discoveredPrinters.value
            .firstOrNull { it.serialNumber == serial }?.deviceName ?: "")
            .ifBlank { prefs.getString("saved_name_$serial", "") ?: "" }

        val serialsCsv = prefs.getString("saved_printer_serials", "") ?: ""
        val serials = serialsCsv.split(",").filter { it.isNotBlank() }.toMutableSet()
        serials.add(serial)

        prefs.edit()
            .putString("saved_printer_serials", serials.joinToString(","))
            .putString("saved_ip_$serial", ip)
            .putString("saved_name_$serial", deviceName)
            .putString("access_code_$serial", _connectedAccessCode.value)
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
                val client = BambuFtpsClient(ip, code, rawSocketFactory = proxySocketFactory(effectiveProxyConfig(_connectedSerialNumber.value)))
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
                val client = BambuFtpsClient(ip, code, rawSocketFactory = proxySocketFactory(effectiveProxyConfig(_connectedSerialNumber.value)))
                client.connect()
                timelapseFtpClient = client
                val files = client.listDirectory("/timelapse")
                    .filter { !it.isDirectory && (it.name.lowercase().endsWith(".avi") || it.name.lowercase().endsWith(".mp4")) }
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
                BambuFtpsClient(_connectedIp.value, _connectedAccessCode.value, rawSocketFactory = proxySocketFactory(effectiveProxyConfig(_connectedSerialNumber.value))).also { it.connect() }
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
                val dlClient = BambuFtpsClient(_connectedIp.value, _connectedAccessCode.value, rawSocketFactory = proxySocketFactory(effectiveProxyConfig(_connectedSerialNumber.value)))
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
    }

    private fun noRouteToHostMessage(ip: String): String =
        "No route to host, $ip.\nPrinter off or disconnected from WiFi?"

    private fun setNoRouteToHost(ip: String) {
        _noRouteToHost.value = noRouteToHostMessage(ip)
    }

    private fun Exception.isNoRouteToHost(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { it.message?.contains("EHOSTUNREACH") == true }
}
