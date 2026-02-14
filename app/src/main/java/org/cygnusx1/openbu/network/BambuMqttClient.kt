package org.cygnusx1.openbu.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/**
 * Raw MQTT 3.1.1 client over TLS for Bambu Lab printers.
 * Uses the same SSL socket approach as BambuCameraClient (trust-all certs).
 */
class BambuMqttClient(
    private val ip: String,
    private val accessCode: String,
    private val serialNumber: String,
) {
    private val _lightOn = MutableStateFlow<Boolean?>(null)
    val lightOn: StateFlow<Boolean?> = _lightOn.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _printerStatus = MutableStateFlow(PrinterStatus())
    val printerStatus: StateFlow<PrinterStatus> = _printerStatus.asStateFlow()

    private var socket: SSLSocket? = null
    private var socketOutput: OutputStream? = null

    fun connect(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to MQTT broker at $ip:8883, serial: $serialNumber")
                val sslSocket = createSslConnection()
                socket = sslSocket
                val input = DataInputStream(sslSocket.inputStream)
                val out = BufferedOutputStream(sslSocket.outputStream)
                socketOutput = out

                // MQTT CONNECT — send as single write
                val clientId = "openbu_${System.currentTimeMillis()}"
                val connectPacket = buildConnectPacket(clientId, "bblp", accessCode)
                out.write(connectPacket)
                out.flush()
                Log.d(TAG, "Sent CONNECT packet (${connectPacket.size} bytes)")

                // Read CONNACK
                val connackType = input.readByte().toInt() and 0xFF
                val connackLen = readRemainingLength(input)
                val connackData = ByteArray(connackLen)
                input.readFully(connackData)
                if ((connackType shr 4) != 2) {
                    throw IOException("Expected CONNACK, got packet type ${connackType shr 4}")
                }
                val returnCode = connackData[1].toInt() and 0xFF
                if (returnCode != 0) {
                    throw IOException("CONNACK rejected: return code $returnCode")
                }
                Log.d(TAG, "MQTT CONNACK OK")
                _connected.value = true

                // SUBSCRIBE to report topic — send as single write
                val reportTopic = "device/$serialNumber/report"
                val subscribePacket = buildSubscribePacket(1, reportTopic)
                out.write(subscribePacket)
                out.flush()
                Log.d(TAG, "Sent SUBSCRIBE to $reportTopic")

                // Read SUBACK
                val subackType = input.readByte().toInt() and 0xFF
                val subackLen = readRemainingLength(input)
                val subackData = ByteArray(subackLen)
                input.readFully(subackData)
                if ((subackType shr 4) != 9) {
                    Log.w(TAG, "Expected SUBACK, got packet type ${subackType shr 4}")
                } else {
                    Log.d(TAG, "SUBACK received")
                }

                // Request current status
                requestStatus()

                // Read loop — process incoming MQTT packets
                while (coroutineContext.isActive) {
                    val headerByte = input.readByte().toInt() and 0xFF
                    val packetType = headerByte shr 4
                    val remainLen = readRemainingLength(input)
                    val payload = ByteArray(remainLen)
                    input.readFully(payload)

                    when (packetType) {
                        3 -> handlePublish(payload) // PUBLISH
                        13 -> {                      // PINGREQ
                            out.write(byteArrayOf(0xD0.toByte(), 0x00))
                            out.flush()
                        }
                        else -> Log.d(TAG, "Received MQTT packet type $packetType, len=$remainLen")
                    }
                }
            } catch (e: Exception) {
                if (_connected.value) {
                    Log.w(TAG, "MQTT connection lost", e)
                } else {
                    Log.e(TAG, "MQTT connection failed", e)
                }
            } finally {
                _connected.value = false
                closeSocket()
            }
        }
    }

    private fun handlePublish(data: ByteArray) {
        try {
            val topicLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val payloadOffset = 2 + topicLen
            if (payloadOffset > data.size) return
            val payload = String(data, payloadOffset, data.size - payloadOffset)
            Log.d(TAG, "PUBLISH received (${data.size} bytes): ${payload.take(200)}")
            parseLightStatus(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PUBLISH", e)
        }
    }

    fun toggleLight(on: Boolean) {
        _lightOn.value = on
        val out = socketOutput ?: return
        val ts = System.currentTimeMillis().toString()
        val json = JSONObject().apply {
            put("system", JSONObject().apply {
                put("sequence_id", ts)
                put("command", "ledctrl")
                put("led_node", "chamber_light")
                put("led_mode", if (on) "on" else "off")
                put("led_on_time", 500)
                put("led_off_time", 500)
                put("loop_times", 0)
                put("interval_time", 0)
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            Log.d(TAG, "Publishing light ${if (on) "on" else "off"} to $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish light toggle", e)
        }
    }

    private fun requestStatus() {
        val out = socketOutput ?: return
        val json = JSONObject().apply {
            put("pushing", JSONObject().apply {
                put("sequence_id", "0")
                put("command", "pushall")
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            Log.d(TAG, "Requesting pushall on $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish pushall", e)
        }
    }

    private fun parseLightStatus(payload: String) {
        val root = JSONObject(payload)
        val print = root.optJSONObject("print") ?: return

        parsePrinterStatus(print)

        val lights = print.optJSONArray("lights_report") ?: return
        for (i in 0 until lights.length()) {
            val light = lights.getJSONObject(i)
            if (light.optString("node") == "chamber_light") {
                val mode = light.optString("mode")
                Log.d(TAG, "Chamber light status: $mode")
                _lightOn.value = mode == "on"
                return
            }
        }
    }

    private fun parsePrinterStatus(print: JSONObject) {
        val current = _printerStatus.value

        val gcodeState = print.optString("gcode_state", "").ifEmpty { current.gcodeState }
        val nozzleTemper = if (print.has("nozzle_temper")) print.optDouble("nozzle_temper").toFloat() else current.nozzleTemper
        val nozzleTarget = if (print.has("nozzle_target_temper")) print.optDouble("nozzle_target_temper").toFloat() else current.nozzleTargetTemper
        val bedTemper = if (print.has("bed_temper")) print.optDouble("bed_temper").toFloat() else current.bedTemper
        val bedTarget = if (print.has("bed_target_temper")) print.optDouble("bed_target_temper").toFloat() else current.bedTargetTemper
        val heatbreakFan = print.optString("heatbreak_fan_speed", "").ifEmpty { current.heatbreakFanSpeed }
        val coolingFan = print.optString("cooling_fan_speed", "").ifEmpty { current.coolingFanSpeed }
        val bigFan1 = print.optString("big_fan1_speed", "").ifEmpty { current.bigFan1Speed }

        var amsTemp = current.amsTemp
        var amsHumidity = current.amsHumidity
        var amsTrayType = current.amsTrayType
        var amsTrayColor = current.amsTrayColor

        val ams = print.optJSONObject("ams")
        if (ams != null) {
            val amsArray = ams.optJSONArray("ams")
            if (amsArray != null && amsArray.length() > 0) {
                val ams0 = amsArray.getJSONObject(0)
                amsTemp = ams0.optString("temp", amsTemp)
                amsHumidity = ams0.optString("humidity_raw", amsHumidity)
                val trays = ams0.optJSONArray("tray")
                if (trays != null && trays.length() > 0) {
                    val tray0 = trays.getJSONObject(0)
                    amsTrayType = tray0.optString("tray_type", amsTrayType)
                    amsTrayColor = tray0.optString("tray_color", amsTrayColor)
                }
            }
        }

        _printerStatus.value = PrinterStatus(
            gcodeState = gcodeState,
            nozzleTemper = nozzleTemper,
            nozzleTargetTemper = nozzleTarget,
            bedTemper = bedTemper,
            bedTargetTemper = bedTarget,
            heatbreakFanSpeed = heatbreakFan,
            coolingFanSpeed = coolingFan,
            bigFan1Speed = bigFan1,
            amsTemp = amsTemp,
            amsHumidity = amsHumidity,
            amsTrayType = amsTrayType,
            amsTrayColor = amsTrayColor,
        )
    }

    // --- MQTT packet builders (each returns a complete packet as byte array) ---

    private fun buildConnectPacket(clientId: String, username: String, password: String): ByteArray {
        val varHeaderAndPayload = ByteArrayOutputStream()
        val d = DataOutputStream(varHeaderAndPayload)
        // Variable header
        d.writeShort(4)                  // Protocol name length
        d.write("MQTT".toByteArray())    // Protocol name
        d.writeByte(4)                   // Protocol level (MQTT 3.1.1)
        d.writeByte(0xC2)               // Flags: username + password + clean session
        d.writeShort(60)                 // Keep alive (seconds)
        // Payload
        d.writeShort(clientId.length)
        d.write(clientId.toByteArray())
        d.writeShort(username.length)
        d.write(username.toByteArray())
        d.writeShort(password.length)
        d.write(password.toByteArray())
        d.flush()

        return wrapMqttPacket(0x10, varHeaderAndPayload.toByteArray())
    }

    private fun buildSubscribePacket(packetId: Int, topic: String): ByteArray {
        val varHeaderAndPayload = ByteArrayOutputStream()
        val d = DataOutputStream(varHeaderAndPayload)
        d.writeShort(packetId)
        d.writeShort(topic.length)
        d.write(topic.toByteArray())
        d.writeByte(0) // QoS 0
        d.flush()

        return wrapMqttPacket(0x82, varHeaderAndPayload.toByteArray())
    }

    private fun buildPublishPacket(topic: String, message: String): ByteArray {
        val varHeaderAndPayload = ByteArrayOutputStream()
        val d = DataOutputStream(varHeaderAndPayload)
        d.writeShort(topic.length)
        d.write(topic.toByteArray())
        d.write(message.toByteArray(Charsets.UTF_8))
        d.flush()

        return wrapMqttPacket(0x30, varHeaderAndPayload.toByteArray())
    }

    private fun wrapMqttPacket(fixedHeaderByte: Int, body: ByteArray): ByteArray {
        val packet = ByteArrayOutputStream(1 + 4 + body.size)
        packet.write(fixedHeaderByte)
        // Encode remaining length
        var len = body.size
        do {
            var byte = len % 128
            len /= 128
            if (len > 0) byte = byte or 0x80
            packet.write(byte)
        } while (len > 0)
        packet.write(body)
        return packet.toByteArray()
    }

    private fun readRemainingLength(input: DataInputStream): Int {
        var value = 0
        var multiplier = 1
        var byte: Int
        do {
            byte = input.readByte().toInt() and 0xFF
            value += (byte and 0x7F) * multiplier
            multiplier *= 128
        } while ((byte and 0x80) != 0)
        return value
    }

    // --- SSL connection (same pattern as BambuCameraClient) ---

    private fun createSslConnection(): SSLSocket {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(ip, 8883), 10_000)
        rawSocket.soTimeout = 90_000
        rawSocket.tcpNoDelay = true

        val sslSocket = sslContext.socketFactory.createSocket(rawSocket, ip, 8883, true) as SSLSocket
        sslSocket.sslParameters = sslSocket.sslParameters.apply {
            endpointIdentificationAlgorithm = null
        }
        sslSocket.startHandshake()
        Log.d(TAG, "TLS handshake complete, cipher: ${sslSocket.session.cipherSuite}")
        return sslSocket
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        socketOutput = null
    }

    fun close() {
        closeSocket()
        _connected.value = false
        _lightOn.value = null
    }

    companion object {
        private const val TAG = "BambuMqtt"
    }
}
