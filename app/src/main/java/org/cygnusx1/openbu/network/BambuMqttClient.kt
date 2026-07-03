package org.cygnusx1.openbu.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cygnusx1.openbu.data.FilamentProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.roundToInt
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
    // Stable per-install MQTT client id. Reusing the same id across reconnects lets the broker
    // evict our previous (possibly still-lingering) session per MQTT-3.1.4-2 instead of treating
    // the reconnect as an additional client — which would trip the printer's 2-connection limit
    // (one slot is often held by OrcaSlicer) and get the new connection dropped.
    private val clientId: String = "openbu_${System.currentTimeMillis()}",
    private val rawSocketFactory: ((String, Int) -> Socket)? = null,
) {
    private val _lightOn = MutableStateFlow<Boolean?>(null)
    val lightOn: StateFlow<Boolean?> = _lightOn.asStateFlow()

    // After a local light toggle we optimistically set _lightOn, but the printer keeps reporting the
    // old mode in any reports already in flight (and the next periodic report) until it applies the
    // command. Honouring those would flip the switch back ("Off" -> "On" -> "Off"), so we suppress
    // contradicting lights_report values until the printer confirms our desired state or this window
    // expires. (Issue #41)
    private var pendingLightOn: Boolean? = null
    private var pendingLightDeadlineMs: Long = 0L
    private val pendingLightTimeoutMs = 4000L

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _printerStatus = MutableStateFlow(PrinterStatus())
    val printerStatus: StateFlow<PrinterStatus> = _printerStatus.asStateFlow()

    // Flips true once a PUBLISH is received on our subscribed device/<serial>/report topic.
    // The broker only delivers messages for the exact serial we subscribed to (and drops the
    // connection for a wrong serial), so this is proof the serial is valid.
    private val _reportReceived = MutableStateFlow(false)
    val reportReceived: StateFlow<Boolean> = _reportReceived.asStateFlow()

    @Volatile
    var debugLogging: Boolean = false

    private val seenKeySignatures = mutableSetOf<String>()
    private val _mqttDataMessages = MutableStateFlow<List<String>>(emptyList())
    val mqttDataMessages: StateFlow<List<String>> = _mqttDataMessages.asStateFlow()

    private var socket: SSLSocket? = null
    private var socketOutput: OutputStream? = null
    private val amsModelMap = mutableMapOf<String, String>()
    // H2-series printers report the loaded slot via device.extruder.info[].snow rather
    // than ams.tray_now (which carries a stale value). Latched once we see extruder
    // info so partial updates that still echo ams.tray_now don't clobber the real value.
    private var usesExtruderSnow = false
    private val nextPacketId = AtomicInteger(2)

    fun connect(scope: CoroutineScope) {
        // Reset per-connection detection state so a reused instance reconnecting to a
        // different printer family doesn't inherit the previous printer's latch.
        usesExtruderSnow = false
        scope.launch(Dispatchers.IO) {
            try {
                if (debugLogging) Log.d(TAG, "Connecting to MQTT broker at $ip:8883, serial: $serialNumber")
                val sslSocket = createSslConnection()
                socket = sslSocket
                val input = DataInputStream(sslSocket.inputStream)
                val out = BufferedOutputStream(sslSocket.outputStream)
                socketOutput = out

                // MQTT CONNECT — send as single write
                val connectPacket = buildConnectPacket(clientId, "bblp", accessCode)
                out.write(connectPacket)
                out.flush()
                if (debugLogging) Log.d(TAG, "Sent CONNECT packet (${connectPacket.size} bytes)")

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
                    val reason = when (returnCode) {
                        4, 5 -> "Access code rejected by printer"
                        else -> "Connection refused (code $returnCode)"
                    }
                    _connectionError.value = reason
                    throw IOException("CONNACK rejected: return code $returnCode")
                }
                if (debugLogging) Log.d(TAG, "MQTT CONNACK OK")
                _connected.value = true

                // SUBSCRIBE to report topic — send as single write
                val reportTopic = "device/$serialNumber/report"
                val subscribePacket = buildSubscribePacket(1, reportTopic)
                out.write(subscribePacket)
                out.flush()
                if (debugLogging) Log.d(TAG, "Sent SUBSCRIBE to $reportTopic")

                // Read SUBACK
                val subackType = input.readByte().toInt() and 0xFF
                val subackLen = readRemainingLength(input)
                val subackData = ByteArray(subackLen)
                input.readFully(subackData)
                if ((subackType shr 4) != 9) {
                    Log.w(TAG, "Expected SUBACK, got packet type ${subackType shr 4}")
                } else {
                    if (debugLogging) Log.d(TAG, "SUBACK received")
                }

                // Request current status and version info
                requestStatus()
                requestVersion()

                // Read loop — process incoming MQTT packets
                while (coroutineContext.isActive) {
                    val headerByte = input.readByte().toInt() and 0xFF
                    val packetType = headerByte shr 4
                    val remainLen = readRemainingLength(input)
                    val payload = ByteArray(remainLen)
                    input.readFully(payload)

                    when (packetType) {
                        3 -> {                       // PUBLISH
                            // Parse first so printerStatus (incl. the mock flag) reflects this
                            // report before reportReceived flips — consumers gate on both.
                            handlePublish(payload)
                            _reportReceived.value = true
                        }
                        4 -> if (debugLogging) Log.d(TAG, "PUBACK received") // PUBACK
                        13 -> {                      // PINGREQ
                            out.write(byteArrayOf(0xD0.toByte(), 0x00))
                            out.flush()
                        }
                        else -> if (debugLogging) Log.d(TAG, "Received MQTT packet type $packetType, len=$remainLen")
                    }
                }
            } catch (e: Exception) {
                if (_connected.value) {
                    Log.w(TAG, "MQTT connection lost", e)
                } else {
                    Log.e(TAG, "MQTT connection failed", e)
                    if (e.isNoRouteToHost()) {
                        _connectionError.value = "EHOSTUNREACH"
                    } else if (_connectionError.value == null) {
                        // Broker refused or closed the connection before CONNACK — commonly
                        // Developer Mode disabled or firmware too old. Surface it so the
                        // ViewModel can retry and ultimately show an actionable hint.
                        _connectionError.value = "Could not reach the printer"
                    }
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
            if (debugLogging) Log.d(TAG, "PUBLISH received (${data.size} bytes): ${payload.take(200)}")
            if (debugLogging) {
                Log.d(TAG, "PUBLISH full payload:\n$payload")
            }
            val json = JSONObject(payload)
            val print = json.optJSONObject("print")
            val printCmd = print?.optString("command", "") ?: ""
            val printMsg = if (print != null && print.has("msg")) print.optInt("msg").toString() else ""
            val infoCmd = json.optJSONObject("info")?.optString("command", "") ?: ""
            val sig = when {
                printCmd.isNotEmpty() -> "print.command=$printCmd|msg=$printMsg"
                infoCmd.isNotEmpty() -> "info.command=$infoCmd"
                else -> keySignature(json)
            }
            if (seenKeySignatures.add(sig)) {
                _mqttDataMessages.value = _mqttDataMessages.value + json.toString(2)
            }
            parseLightStatus(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PUBLISH", e)
        }
    }

    private fun keySignature(obj: JSONObject): String {
        val keys = obj.keys().asSequence().toList().sorted()
        return keys.joinToString(",") { key ->
            val value = obj.opt(key)
            when (value) {
                is JSONObject -> "$key:{${keySignature(value)}}"
                is JSONArray -> "$key:[${arraySignature(value)}]"
                else -> key
            }
        }
    }

    private fun arraySignature(arr: JSONArray): String {
        if (arr.length() == 0) return ""
        val first = arr.opt(0)
        return when (first) {
            is JSONObject -> "{${keySignature(first)}}"
            is JSONArray -> "[${arraySignature(first)}]"
            else -> "v"
        }
    }

    fun toggleLight(on: Boolean) {
        _lightOn.value = on
        pendingLightOn = on
        pendingLightDeadlineMs = System.currentTimeMillis() + pendingLightTimeoutMs
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
            if (debugLogging) Log.d(TAG, "Publishing light ${if (on) "on" else "off"} to $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish light toggle", e)
        }
    }

    fun sendPrinterActionCommand(command: String) {
        val out = socketOutput ?: return
        val json = JSONObject().apply {
            put("print", JSONObject().apply {
                put("sequence_id", "0")
                put("command", command)
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "Publishing printer action command=$command to $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish printer action command", e)
        }
    }

    fun setFilament(amsId: Int, trayId: Int, profile: FilamentProfile, colorHex: String) {
        val out = socketOutput ?: return
        // ams_id is the hardware ID as reported by the printer:
        //   0-3 for standard AMS, 128+ for AMS-HT/Lite, 255 for external spool
        // tray_id: slot index within the AMS unit (0-3), or 254 for external spool
        // BambuStudio sends both slot_id and tray_id; for physical AMS, tray_id = slot_id;
        // for virtual trays (external spool), tray_id = 254.
        val isVirtual = amsId == 255
        val tagTrayId = if (isVirtual) 254 else trayId
        val json = JSONObject().apply {
            put("print", JSONObject().apply {
                put("sequence_id", "0")
                put("command", "ams_filament_setting")
                put("ams_id", amsId)
                put("slot_id", trayId)
                put("tray_id", tagTrayId)
                put("tray_info_idx", profile.filamentId)
                put("tray_type", profile.type)
                put("tray_color", colorHex)
                put("nozzle_temp_min", profile.nozzleTempMin)
                put("nozzle_temp_max", profile.nozzleTempMax)
                put("setting_id", profile.settingId)
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "Publishing ams_filament_setting: ${json.toString()}")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish ams_filament_setting", e)
        }
    }

    fun setNozzleTemperature(temp: Int) {
        val out = socketOutput ?: return
        val json = JSONObject().apply {
            put("print", JSONObject().apply {
                put("sequence_id", "0")
                put("command", "gcode_line")
                put("param", "M104 S$temp\n")
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "Publishing nozzle_temper=$temp to $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish nozzle temperature", e)
        }
    }

    fun setBedTemperature(temp: Int) {
        val out = socketOutput ?: return
        val json = JSONObject().apply {
            put("print", JSONObject().apply {
                put("sequence_id", "0")
                put("command", "gcode_line")
                put("param", "M140 S$temp\n")
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "Publishing bed_temper=$temp to $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish bed temperature", e)
        }
    }

    fun setFanSpeed(fan: Int, speedPwm: Int) {
        // G-code: M106 P{fan} S{speedPwm}
        // fan: 1=part cooling, 2=aux/big, 3=chamber
        // speedPwm: 0-255
        val fanName = when (fan) { 1 -> "part"; 2 -> "aux"; 3 -> "chamber"; else -> "unknown($fan)" }
        val out = socketOutput ?: run {
            Log.w(TAG, "setFanSpeed: socketOutput is null, cannot send M106 P$fan S$speedPwm ($fanName)")
            return
        }
        val gcode = "M106 P$fan S$speedPwm\n"
        val json = JSONObject().apply {
            put("print", JSONObject().apply {
                put("sequence_id", "0")
                put("command", "gcode_line")
                put("param", gcode)
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "setFanSpeed: $fanName fan=$fan pwm=$speedPwm gcode=\"${gcode.trim()}\" topic=$topic payload=${json}")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
            if (debugLogging) Log.d(TAG, "setFanSpeed: published ${packet.size} bytes for $fanName fan")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish fan speed for $fanName fan (P$fan S$speedPwm)", e)
        }
    }

    fun setSpeedLevel(level: Int) {
        val out = socketOutput ?: return
        val json = JSONObject().apply {
            put("print", JSONObject().apply {
                put("sequence_id", "0")
                put("command", "print_speed")
                put("param", level.toString())
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "Publishing print_speed param=$level to $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish print_speed", e)
        }
    }

    fun skipObjects(objectIds: List<Int>) {
        val out = socketOutput ?: return
        val packetId = nextPacketId.getAndIncrement()
        val json = JSONObject().apply {
            put("print", JSONObject().apply {
                put("sequence_id", packetId.toString())
                put("command", "skip_objects")
                put("obj_list", JSONArray(objectIds))
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "Publishing skip_objects ids=$objectIds to $topic (QoS 1, packetId=$packetId)")
            val packet = buildPublishPacketQoS1(topic, json.toString(), packetId)
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish skip_objects", e)
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
            if (debugLogging) Log.d(TAG, "Requesting pushall on $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish pushall", e)
        }
    }

    private fun requestVersion() {
        val out = socketOutput ?: return
        val json = JSONObject().apply {
            put("info", JSONObject().apply {
                put("sequence_id", "0")
                put("command", "get_version")
            })
        }
        try {
            val topic = "device/$serialNumber/request"
            if (debugLogging) Log.d(TAG, "Requesting get_version on $topic")
            val packet = buildPublishPacket(topic, json.toString())
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish get_version", e)
        }
    }

    private fun parseVersionResponse(info: JSONObject) {
        val modules = info.optJSONArray("module") ?: return
        var changed = false
        for (i in 0 until modules.length()) {
            val module = modules.getJSONObject(i)
            val name = module.optString("name", "")
            // name is like "ams/2", "n3f/0", "n3s/128" — extract prefix and id around "/"
            val slashIndex = name.indexOf('/')
            if (slashIndex < 0) continue
            val prefix = name.substring(0, slashIndex)
            val amsId = name.substring(slashIndex + 1)
            val productName = module.optString("product_name", "")
            val displayName = if (productName.isNotEmpty()) {
                productName
            } else {
                // Fallback for printers (e.g. X1C) that omit product_name in get_version.
                // Map prefix -> friendly base, then append 1-based slot number.
                val base = when (prefix) {
                    "ams" -> "AMS"
                    "n3f" -> "AMS 2 Pro"
                    "n3s" -> "AMS HT"
                    else -> continue
                }
                val idNum = amsId.toIntOrNull()
                if (idNum != null) "$base ${idNum + 1}" else base
            }
            amsModelMap[amsId] = displayName
            changed = true
            if (debugLogging) Log.d(TAG, "AMS model: id=$amsId -> $displayName")
        }
        // Re-apply models to existing AMS units (version response may arrive after pushall)
        if (changed) {
            val current = _printerStatus.value
            val updated = current.amsUnits.map { unit ->
                val model = amsModelMap[unit.id] ?: unit.model
                if (model != unit.model) unit.copy(model = model) else unit
            }
            if (updated != current.amsUnits) {
                _printerStatus.value = current.copy(amsUnits = updated)
            }
        }
    }

    private fun parseLightStatus(payload: String) {
        val root = JSONObject(payload)
        if (debugLogging) {
            Log.d(TAG, "MQTT JSON keys: ${root.keys().asSequence().toList()}")
        }

        val info = root.optJSONObject("info")
        if (info != null && info.optString("command") == "get_version") {
            parseVersionResponse(info)
            // Patch any already-loaded AMS units with the now-known model names
            val cur = _printerStatus.value
            if (cur.amsUnits.isNotEmpty() && amsModelMap.isNotEmpty()) {
                _printerStatus.value = cur.copy(
                    amsUnits = cur.amsUnits.map { unit ->
                        val model = amsModelMap[unit.id]
                        if (model != null) unit.copy(model = model) else unit
                    }
                )
            }
        }

        val print = root.optJSONObject("print") ?: return

        // When ams_filament_setting succeeds, request a full status refresh
        if (print.optString("command") == "ams_filament_setting" &&
            print.optString("result") == "success"
        ) {
            if (debugLogging) Log.d(TAG, "ams_filament_setting succeeded, requesting pushall")
            requestStatus()
        }

        parsePrinterStatus(print)

        val lights = print.optJSONArray("lights_report") ?: return
        for (i in 0 until lights.length()) {
            val light = lights.getJSONObject(i)
            if (light.optString("node") == "chamber_light") {
                val mode = light.optString("mode")
                if (debugLogging) Log.d(TAG, "Chamber light status: $mode")
                val reportedOn = mode == "on"
                val pending = pendingLightOn
                if (pending != null) {
                    if (reportedOn == pending) {
                        // Printer confirmed our toggle — stop suppressing reports.
                        pendingLightOn = null
                    } else if (System.currentTimeMillis() < pendingLightDeadlineMs) {
                        // Stale report from before the command was applied; ignore it so the
                        // switch doesn't bounce back to its old position.
                        return
                    } else {
                        // Confirmation never came; trust the printer again.
                        pendingLightOn = null
                    }
                }
                _lightOn.value = reportedOn
                return
            }
        }
    }

    // Chamber temperature reporting differs by model:
    //   - X1 family / P2S: real sensor, direct °C in top-level "chamber_temper".
    //   - H2 family: no top-level field; the reading lives in device.ctc.info.temp,
    //     encoded as target*65536 + current when the heater is on, or a direct °C
    //     value when idle (heater off).
    // Returns null when the report carries no chamber reading (keep the prior value).
    private fun parseChamberTemper(print: JSONObject): Float? {
        if (print.has("chamber_temper")) {
            return print.optDouble("chamber_temper").toFloat()
        }
        val info = print.optJSONObject("device")?.optJSONObject("ctc")?.optJSONObject("info")
        if (info != null && info.has("temp")) {
            val raw = info.optDouble("temp")
            // Values above 500 pack target*65536 + current; the low half is the
            // current temperature. Smaller values are a direct °C reading.
            return if (raw > 500) (raw.toLong() % 65536).toFloat() else raw.toFloat()
        }
        return null
    }

    private fun parsePrinterStatus(print: JSONObject) {
        val current = _printerStatus.value

        val gcodeState = print.optString("gcode_state", "").ifEmpty { current.gcodeState }
        val subtaskName = print.optString("subtask_name", "").ifEmpty { current.subtaskName }
        val gcodeFile = print.optString("gcode_file", "")
            .ifEmpty { current.gcodeFile }
            .ifEmpty { subtaskName }
        val mcPercent = if (print.has("mc_percent")) print.optInt("mc_percent") else current.mcPercent
        val layerNum = if (print.has("layer_num")) print.optInt("layer_num") else current.layerNum
        val totalLayerNum = if (print.has("total_layer_num")) print.optInt("total_layer_num") else current.totalLayerNum
        val mcRemainingTime = if (print.has("mc_remaining_time")) print.optInt("mc_remaining_time") else current.mcRemainingTime
        val nozzleTemper = if (print.has("nozzle_temper")) print.optDouble("nozzle_temper").toFloat() else current.nozzleTemper
        val nozzleTarget = if (print.has("nozzle_target_temper")) print.optDouble("nozzle_target_temper").toFloat() else current.nozzleTargetTemper
        val bedTemper = if (print.has("bed_temper")) print.optDouble("bed_temper").toFloat() else current.bedTemper
        val bedTarget = if (print.has("bed_target_temper")) print.optDouble("bed_target_temper").toFloat() else current.bedTargetTemper
        val chamberTemper = parseChamberTemper(print) ?: current.chamberTemper
        val isMock = if (print.has("mock")) print.optBoolean("mock") else current.isMock
        // fan_gear packs 0-255 PWM values: bits 0-7 cooling, 8-15 big_fan1, 16-23 big_fan2
        val heatbreakFan: Int
        val coolingFan: Int
        val bigFan1: Int
        val bigFan2: Int
        if (print.has("fan_gear")) {
            val fanGear = print.optLong("fan_gear")
            coolingFan = (fanGear and 0xFF).toInt()
            bigFan1 = ((fanGear shr 8) and 0xFF).toInt()
            bigFan2 = ((fanGear shr 16) and 0xFF).toInt()
            heatbreakFan = if (print.has("heatbreak_fan_speed")) {
                fanSpeedToInt(print.optString("heatbreak_fan_speed", ""))
            } else current.heatbreakFanSpeed
        } else {
            coolingFan = if (print.has("cooling_fan_speed")) fanSpeedToInt(print.optString("cooling_fan_speed", "")) else current.coolingFanSpeed
            bigFan1 = if (print.has("big_fan1_speed")) fanSpeedToInt(print.optString("big_fan1_speed", "")) else current.bigFan1Speed
            bigFan2 = if (print.has("big_fan2_speed")) fanSpeedToInt(print.optString("big_fan2_speed", "")) else current.bigFan2Speed
            heatbreakFan = if (print.has("heatbreak_fan_speed")) fanSpeedToInt(print.optString("heatbreak_fan_speed", "")) else current.heatbreakFanSpeed
        }

        // Log all keys containing "spd" or "speed" to find the correct field name
        val speedKeys = print.keys().asSequence().filter {
            it.contains("spd", ignoreCase = true) || it.contains("speed", ignoreCase = true)
        }.toList()
        if (speedKeys.isNotEmpty()) {
            Log.d(TAG, "Speed-related keys in print: $speedKeys -> ${speedKeys.map { print.opt(it) }}")
        }
        val spdLvl = when {
            print.has("spd_lvl") -> print.optInt("spd_lvl")
            print.has("speed_level") -> print.optInt("speed_level")
            else -> current.spdLvl
        }

        var amsUnits = current.amsUnits
        var vtTray = current.vtTray
        var trayNow = current.trayNow

        val vtTrayObj = print.optJSONObject("vt_tray")
        if (vtTrayObj != null) {
            val trayType = vtTrayObj.optString("tray_type", "")
            val trayColor = vtTrayObj.optString("tray_color", "")
            val trayInfoIdx = vtTrayObj.optString("tray_info_idx", "")
            vtTray = if (trayType.isNotEmpty()) {
                AmsTray(
                    id = vtTrayObj.optString("id", "254"),
                    trayType = trayType,
                    trayColor = trayColor,
                    trayInfoIdx = trayInfoIdx,
                    remainPercent = vtTrayObj.optInt("remain", -1),
                    trayWeight = vtTrayObj.optString("tray_weight", "0").toIntOrNull() ?: 0,
                )
            } else {
                // Empty tray_type: keep the latched value rather than clobbering a
                // previously-good spool with null on a partial report.
                vtTray
            }
        }

        // H2-series printers report the external spool as a "vir_slot" array (id 255)
        // instead of the X1/P "vt_tray" object (id 254). Map the first entry into the
        // same vtTray slot the UI already renders.
        val virSlotArr = print.optJSONArray("vir_slot")
        if (virSlotArr != null && virSlotArr.length() > 0) {
            val virObj = virSlotArr.getJSONObject(0)
            val trayType = virObj.optString("tray_type", "")
            vtTray = if (trayType.isNotEmpty()) {
                AmsTray(
                    id = virObj.optString("id", "255"),
                    trayType = trayType,
                    trayColor = virObj.optString("tray_color", ""),
                    trayInfoIdx = virObj.optString("tray_info_idx", ""),
                    remainPercent = virObj.optInt("remain", -1),
                    trayWeight = virObj.optString("tray_weight", "0").toIntOrNull() ?: 0,
                )
            } else {
                // Empty tray_type: keep the latched value rather than clobbering a
                // previously-good spool with null on a partial report.
                vtTray
            }
        }

        val ams = print.optJSONObject("ams")
        if (ams != null) {
            if (!usesExtruderSnow && ams.has("tray_now")) {
                trayNow = ams.optString("tray_now", trayNow)
            }
            val amsArray = ams.optJSONArray("ams")
            if (amsArray != null && amsArray.length() > 0) {
                val units = mutableListOf<AmsUnit>()
                for (i in 0 until amsArray.length()) {
                    val amsObj = amsArray.getJSONObject(i)
                    val trays = mutableListOf<AmsTray>()
                    val trayArray = amsObj.optJSONArray("tray")
                    if (trayArray != null) {
                        for (j in 0 until trayArray.length()) {
                            val trayObj = trayArray.getJSONObject(j)
                            trays.add(AmsTray(
                                id = trayObj.optString("id", ""),
                                trayType = trayObj.optString("tray_type", ""),
                                trayColor = trayObj.optString("tray_color", ""),
                                trayInfoIdx = trayObj.optString("tray_info_idx", ""),
                                remainPercent = trayObj.optInt("remain", -1),
                                trayWeight = trayObj.optString("tray_weight", "0").toIntOrNull() ?: 0,
                            ))
                        }
                    }
                    val amsId = amsObj.optString("id", "0")
                    val amsModel = amsModelMap[amsId] ?: "---"
                    units.add(AmsUnit(
                        id = amsId,
                        model = amsModel,
                        temp = amsObj.optString("temp", ""),
                        humidity = run {
                            val raw = amsObj.optString("humidity_raw", "")
                            if (raw.isNotEmpty()) {
                                raw
                            } else {
                                // X1C reports a 1-5 level: 5=10%, 4=20%, 3=30%, 2=40%, 1=50%
                                val level = amsObj.optString("humidity", "").toIntOrNull()
                                if (level != null && level in 1..5) ((6 - level) * 10).toString() else ""
                            }
                        },
                        trays = trays,
                    ))
                }
                amsUnits = units
            }
        }

        // H2-series printers don't report the loaded slot via ams.tray_now (it carries
        // a stale "0" even with no AMS). The reliable signal is per-extruder under
        // device.extruder.info[].snow, a 16-bit (amsId << 8) | slot value. Decode it to
        // the X1/P trayNow convention the UI uses: amsId*4 + slot, 254 = external spool
        // (amsId 255), 255 = nothing loaded (0xFFFF). When present, it wins over tray_now.
        val extruderInfo = print.optJSONObject("device")
            ?.optJSONObject("extruder")
            ?.optJSONArray("info")
        if (extruderInfo != null) {
            // Recompute from scratch: default to "255" (nothing loaded) so unloading
            // filament clears the active slot instead of latching the last value.
            var sawSnow = false
            var decoded = "255"
            for (i in 0 until extruderInfo.length()) {
                val info = extruderInfo.getJSONObject(i)
                if (!info.has("snow")) continue // no signal in this entry; don't clobber
                sawSnow = true
                val snow = info.optInt("snow", -1)
                if (snow < 0 || (snow and 0xFFFF) == 0xFFFF) continue // unset / nothing loaded
                val amsId = (snow ushr 8) and 0xFF
                val slot = snow and 0xFF
                decoded = if (amsId == 0xFF) "254" else (amsId * 4 + slot).toString()
                break
            }
            // Only latch onto the snow signal (and overwrite trayNow) once we've actually
            // seen a snow value. A snow-less extruder.info must not disable ams.tray_now,
            // or an X1/P that emits one would freeze trayNow at its first reading.
            if (sawSnow) {
                usesExtruderSnow = true
                trayNow = decoded
            }
        }

        // Parse s_obj: JSON array of skipped object IDs
        val skippedObjects = if (print.has("s_obj")) {
            val arr = print.optJSONArray("s_obj")
            if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    val v = arr.opt(i)
                    when (v) {
                        is Int -> v
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull()
                        else -> null
                    }
                }
            } else current.skippedObjects
        } else current.skippedObjects

        val hmsErrors = if (print.has("hms")) {
            val arr = print.optJSONArray("hms")
            if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    HmsError(
                        attr = obj.optLong("attr"),
                        code = obj.optLong("code"),
                    )
                }
            } else current.hmsErrors
        } else current.hmsErrors

        val newStatus = PrinterStatus(
            gcodeState = gcodeState,
            gcodeFile = gcodeFile,
            subtaskName = subtaskName,
            mcPercent = mcPercent,
            layerNum = layerNum,
            totalLayerNum = totalLayerNum,
            mcRemainingTime = mcRemainingTime,
            nozzleTemper = nozzleTemper,
            nozzleTargetTemper = nozzleTarget,
            bedTemper = bedTemper,
            bedTargetTemper = bedTarget,
            chamberTemper = chamberTemper,
            isMock = isMock,
            heatbreakFanSpeed = heatbreakFan,
            coolingFanSpeed = coolingFan,
            bigFan1Speed = bigFan1,
            bigFan2Speed = bigFan2,
            amsUnits = amsUnits,
            vtTray = vtTray,
            trayNow = trayNow,
            spdLvl = spdLvl,
            skippedObjects = skippedObjects,
            hmsErrors = hmsErrors,
        )
        _printerStatus.value = newStatus

        if (debugLogging) {
            Log.d(TAG, "PrinterStatus: $newStatus")
        }
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

    private fun buildPublishPacketQoS1(topic: String, message: String, packetId: Int): ByteArray {
        val varHeaderAndPayload = ByteArrayOutputStream()
        val d = DataOutputStream(varHeaderAndPayload)
        d.writeShort(topic.length)
        d.write(topic.toByteArray())
        d.writeShort(packetId)
        d.write(message.toByteArray(Charsets.UTF_8))
        d.flush()

        return wrapMqttPacket(0x32, varHeaderAndPayload.toByteArray())
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

        val rawSocket = if (rawSocketFactory != null) {
            rawSocketFactory.invoke(ip, 8883)
        } else {
            Socket().apply { connect(InetSocketAddress(ip, 8883), 10_000) }
        }
        rawSocket.soTimeout = 90_000
        rawSocket.tcpNoDelay = true

        val sslSocket = sslContext.socketFactory.createSocket(rawSocket, ip, 8883, true) as SSLSocket
        sslSocket.sslParameters = sslSocket.sslParameters.apply {
            endpointIdentificationAlgorithm = null
        }
        sslSocket.startHandshake()
        if (debugLogging) Log.d(TAG, "TLS handshake complete, cipher: ${sslSocket.session.cipherSuite}")
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
        _connectionError.value = null
        _lightOn.value = null
        pendingLightOn = null
        seenKeySignatures.clear()
        _mqttDataMessages.value = emptyList()
    }

    /** Convert 0-15 string fan speed to 0-255 using BambuStudio's formula */
    private fun fanSpeedToInt(raw: String): Int {
        val value = raw.toIntOrNull() ?: return 0
        return (floor(value / 1.5f) * 25.5f).roundToInt()
    }

    private fun Exception.isNoRouteToHost(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { it.message?.contains("EHOSTUNREACH") == true }

    companion object {
        private const val TAG = "BambuMqtt"
    }
}
