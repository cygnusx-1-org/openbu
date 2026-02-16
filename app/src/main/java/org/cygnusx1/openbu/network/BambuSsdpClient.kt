package org.cygnusx1.openbu.network

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

data class DiscoveredPrinter(
    val ip: String,
    val serialNumber: String,
    val modelCode: String,
    val deviceName: String,
    val lastSeen: Long = System.currentTimeMillis(),
)

class BambuSsdpClient {

    companion object {
        private const val MULTICAST_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 2021
        private const val STALE_TIMEOUT_MS = 60_000L
        private const val MSEARCH_INTERVAL_MS = 10_000L

        private val MSEARCH_MESSAGE = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_GROUP:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: urn:bambulab-com:device:3dprinter:1\r\n")
            append("\r\n")
        }.toByteArray()
    }

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenJob: Job? = null
    private var msearchJob: Job? = null
    private var cleanupJob: Job? = null
    private var socket: MulticastSocket? = null

    fun startDiscovery(context: Context, scope: CoroutineScope) {
        if (listenJob != null) return

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("openbu_ssdp").apply {
            setReferenceCounted(true)
            acquire()
        }

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val group = InetAddress.getByName(MULTICAST_GROUP)
                val mSocket = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    soTimeout = 5000
                    val iface = findWifiInterface()
                    if (iface != null) {
                        networkInterface = iface
                        joinGroup(java.net.InetSocketAddress(group, SSDP_PORT), iface)
                    } else {
                        joinGroup(group)
                    }
                }
                socket = mSocket

                val buffer = ByteArray(4096)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        mSocket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        val senderIp = packet.address.hostAddress ?: continue
                        parseSsdpMessage(message, senderIp)?.let { printer ->
                            addOrUpdatePrinter(printer)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Expected — allows checking isActive periodically
                    }
                }
            } catch (_: Exception) {
                // Socket closed or network error — discovery stops
            }
        }

        msearchJob = scope.launch(Dispatchers.IO) {
            val group = InetAddress.getByName(MULTICAST_GROUP)
            while (isActive) {
                try {
                    DatagramSocket().use { ds ->
                        val packet = DatagramPacket(MSEARCH_MESSAGE, MSEARCH_MESSAGE.size, group, SSDP_PORT)
                        ds.send(packet)
                    }
                } catch (_: Exception) {
                    // Send failed — will retry
                }
                delay(MSEARCH_INTERVAL_MS)
            }
        }

        cleanupJob = scope.launch {
            while (isActive) {
                delay(10_000)
                val now = System.currentTimeMillis()
                _discoveredPrinters.value = _discoveredPrinters.value.filter {
                    now - it.lastSeen < STALE_TIMEOUT_MS
                }
            }
        }
    }

    fun stopDiscovery() {
        listenJob?.cancel()
        listenJob = null
        msearchJob?.cancel()
        msearchJob = null
        cleanupJob?.cancel()
        cleanupJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
    }

    private fun parseSsdpMessage(message: String, senderIp: String): DiscoveredPrinter? {
        val headers = mutableMapOf<String, String>()
        for (line in message.lines()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().lowercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }

        val usn = headers["usn"] ?: return null
        val serialNumber = usn.removePrefix("USN:").trim()
        if (serialNumber.length !in 15..16) return null

        val location = headers["location"]
        val ip = if (location != null) {
            // Location is typically like "http://192.168.1.100/..."
            try {
                java.net.URI(location).host ?: senderIp
            } catch (_: Exception) {
                senderIp
            }
        } else {
            senderIp
        }

        val modelCode = headers["devmodel.bambu.com"] ?: ""
        val deviceName = headers["devname.bambu.com"] ?: ""

        return DiscoveredPrinter(
            ip = ip,
            serialNumber = serialNumber,
            modelCode = modelCode,
            deviceName = deviceName,
        )
    }

    private fun addOrUpdatePrinter(printer: DiscoveredPrinter) {
        val current = _discoveredPrinters.value.toMutableList()
        val index = current.indexOfFirst { it.serialNumber == printer.serialNumber }
        if (index >= 0) {
            current[index] = printer.copy(lastSeen = System.currentTimeMillis())
        } else {
            current.add(printer)
        }
        _discoveredPrinters.value = current
    }

    private fun findWifiInterface(): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull { iface ->
                !iface.isLoopback && iface.isUp && (iface.name.startsWith("wlan") || iface.name.startsWith("wl"))
            }
        } catch (_: Exception) {
            null
        }
    }
}
