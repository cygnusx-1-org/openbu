package org.cygnusx1.openbu.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.cygnusx1.openbu.network.BambuCameraClient
import org.cygnusx1.openbu.network.BambuMqttClient
import org.cygnusx1.openbu.network.PrinterStatus
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

    private var client: BambuCameraClient? = null
    private var streamJob: Job? = null
    private var mqttClient: BambuMqttClient? = null
    private var mqttJob: Job? = null

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
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connection failed"
                _connectionState.value = ConnectionState.Error
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

    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        mqttJob?.cancel()
        mqttJob = null
        client?.close()
        client = null
        mqttClient?.close()
        mqttClient = null
        _frame.value = null
        _fps.value = 0f
        _isLightOn.value = null
        _isMqttConnected.value = false
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
