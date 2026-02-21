package org.cygnusx1.openbu.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.cygnusx1.openbu.network.BambuCameraClient
import org.cygnusx1.openbu.network.BambuMqttClient
import org.cygnusx1.openbu.network.BambuSsdpClient
import org.cygnusx1.openbu.network.DiscoveredPrinter
import org.cygnusx1.openbu.network.PrinterStatus
import org.cygnusx1.openbu.network.SavedPrinter
import org.cygnusx1.openbu.service.ConnectionForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _rtspEnabled = MutableStateFlow(false)
    val rtspEnabled: StateFlow<Boolean> = _rtspEnabled.asStateFlow()

    private val _rtspUrl = MutableStateFlow("")
    val rtspUrl: StateFlow<String> = _rtspUrl.asStateFlow()

    private val _forceDarkMode = MutableStateFlow(false)
    val forceDarkMode: StateFlow<Boolean> = _forceDarkMode.asStateFlow()

    private val _debugLogging = MutableStateFlow(false)
    val debugLogging: StateFlow<Boolean> = _debugLogging.asStateFlow()

    private val _extendedDebugLogging = MutableStateFlow(false)
    val extendedDebugLogging: StateFlow<Boolean> = _extendedDebugLogging.asStateFlow()

    private val _connectedSerialNumber = MutableStateFlow("")
    val connectedSerialNumber: StateFlow<String> = _connectedSerialNumber.asStateFlow()

    private val _connectedIp = MutableStateFlow("")
    private val _connectedAccessCode = MutableStateFlow("")

    private val _savedPrinters = MutableStateFlow<List<SavedPrinter>>(emptyList())
    val savedPrinters: StateFlow<List<SavedPrinter>> = _savedPrinters.asStateFlow()

    private val ssdpClient = BambuSsdpClient()
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = ssdpClient.discoveredPrinters

    private var client: BambuCameraClient? = null
    private var streamJob: Job? = null
    private var mqttClient: BambuMqttClient? = null
    private var mqttJob: Job? = null
    private var userDisconnected = false

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(application)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            application,
            "bambu_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getSavedAccessCode(serialNumber: String): String =
        prefs.getString("access_code_$serialNumber", "") ?: ""

    init {
        _keepConnectionInBackground.value = prefs.getBoolean("keep_connection_bg", true)
        _showMainStream.value = prefs.getBoolean("show_main_stream", true)
        _forceDarkMode.value = prefs.getBoolean("force_dark_mode", false)
        _debugLogging.value = prefs.getBoolean("debug_logging", false)
        _extendedDebugLogging.value = prefs.getBoolean("extended_debug_logging", false)

        // Migrate stale global RTSP keys
        if (prefs.contains("rtsp_enabled") || prefs.contains("rtsp_url")) {
            prefs.edit().remove("rtsp_enabled").remove("rtsp_url").apply()
        }

        loadSavedPrinters()
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

    private fun loadPerPrinterSettings(serial: String) {
        _rtspEnabled.value = prefs.getBoolean("rtsp_enabled_$serial", false)
        _rtspUrl.value = prefs.getString("rtsp_url_$serial", "") ?: ""
    }

    fun setForceDarkMode(enabled: Boolean) {
        _forceDarkMode.value = enabled
        prefs.edit().putBoolean("force_dark_mode", enabled).apply()
    }

    fun setDebugLogging(enabled: Boolean) {
        _debugLogging.value = enabled
        prefs.edit().putBoolean("debug_logging", enabled).apply()
        mqttClient?.debugLogging = enabled
        if (!enabled) {
            setExtendedDebugLogging(false)
        }
    }

    fun setExtendedDebugLogging(enabled: Boolean) {
        _extendedDebugLogging.value = enabled
        prefs.edit().putBoolean("extended_debug_logging", enabled).apply()
        client?.extendedDebugLogging = enabled
    }

    private fun saveCredentials(ip: String, accessCode: String, serialNumber: String) {
        prefs.edit()
            .putString("access_code_$serialNumber", accessCode)
            .apply()
    }

    fun connect(ip: String, accessCode: String, serialNumber: String) {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Connected
        ) return

        userDisconnected = false
        saveCredentials(ip, accessCode, serialNumber)
        _connectedIp.value = ip
        _connectedAccessCode.value = accessCode
        _connectedSerialNumber.value = serialNumber
        loadPerPrinterSettings(serialNumber)
        _connectionState.value = ConnectionState.Connecting
        _errorMessage.value = null

        val bambuClient = BambuCameraClient(ip, accessCode)
        bambuClient.extendedDebugLogging = _extendedDebugLogging.value
        client = bambuClient

        streamJob = viewModelScope.launch {
            try {
                var frameCount = 0
                var lastFpsTime = System.currentTimeMillis()

                bambuClient.frameFlow().collect { bitmap ->
                    if (_connectionState.value != ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Connected
                        if (_keepConnectionInBackground.value) {
                            val app = getApplication<Application>()
                            app.startForegroundService(Intent(app, ConnectionForegroundService::class.java))
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
                if (!userDisconnected) {
                    _errorMessage.value = "Connection to printer failed"
                    _connectionState.value = ConnectionState.Error
                    cleanupConnections()
                }
            }
        }

        // Start MQTT in separate coroutine (non-fatal)
        val mqtt = BambuMqttClient(ip, accessCode, serialNumber)
        mqtt.debugLogging = _debugLogging.value
        mqttClient = mqtt
        mqtt.connect(viewModelScope)

        mqttJob = viewModelScope.launch {
            launch {
                mqtt.connected.collect { _isMqttConnected.value = it }
            }
            launch {
                mqtt.lightOn.collect { _isLightOn.value = it }
            }
            launch {
                mqtt.printerStatus.collect { _printerStatus.value = it }
            }
        }
    }

    fun toggleLight(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            mqttClient?.toggleLight(on)
        }
    }

    private fun cleanupConnections() {
        streamJob?.cancel()
        streamJob = null
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
        _connectedSerialNumber.value = ""
        _connectedIp.value = ""
        _connectedAccessCode.value = ""
        _rtspEnabled.value = false
        _rtspUrl.value = ""
    }

    private fun stopForegroundService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, ConnectionForegroundService::class.java))
    }

    fun disconnect() {
        userDisconnected = true
        cleanupConnections()
        stopForegroundService()
        _errorMessage.value = null
        _connectionState.value = ConnectionState.Disconnected
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
                deviceName = prefs.getString("saved_name_$serial", "") ?: "",
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
            .apply()

        loadSavedPrinters()
    }

    override fun onCleared() {
        super.onCleared()
        ssdpClient.stopDiscovery()
        disconnect()
    }
}
