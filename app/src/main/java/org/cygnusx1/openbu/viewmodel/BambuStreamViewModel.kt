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

    fun getSavedIp(): String = prefs.getString("ip", "") ?: ""
    fun getSavedAccessCode(): String = prefs.getString("access_code", "") ?: ""
    fun getSavedSerialNumber(): String = prefs.getString("serial_number", "") ?: ""

    init {
        _keepConnectionInBackground.value = prefs.getBoolean("keep_connection_bg", true)
    }

    fun autoConnectIfSaved() {
        if (userDisconnected) return
        val ip = getSavedIp()
        val accessCode = getSavedAccessCode()
        val serialNumber = getSavedSerialNumber()
        if (ip.isNotBlank() && accessCode.isNotBlank() && serialNumber.length == 15) {
            connect(ip, accessCode, serialNumber)
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

    private fun saveCredentials(ip: String, accessCode: String, serialNumber: String) {
        prefs.edit()
            .putString("ip", ip)
            .putString("access_code", accessCode)
            .putString("serial_number", serialNumber)
            .apply()
    }

    fun connect(ip: String, accessCode: String, serialNumber: String) {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Connected
        ) return

        userDisconnected = false
        saveCredentials(ip, accessCode, serialNumber)
        _connectionState.value = ConnectionState.Connecting
        _errorMessage.value = null

        val bambuClient = BambuCameraClient(ip, accessCode)
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

    override fun onCleared() {
        super.onCleared()
        ssdpClient.stopDiscovery()
        disconnect()
    }
}
