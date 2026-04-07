package org.cygnusx1.openbu.network

import android.util.Log
import org.cygnusx1.openbu.data.ProxyConfig
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Creates a TCP socket tunneled through a SOCKS5-over-TLS proxy.
 *
 * Java's built-in Proxy(Proxy.Type.SOCKS, ...) does NOT support TLS to the proxy,
 * so we manually implement the SOCKS5 handshake (RFC 1928 + RFC 1929) over an SSLSocket.
 */
object Socks5TlsSocket {

    private const val TAG = "Socks5TlsSocket"

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Connects to [targetHost]:[targetPort] through the SOCKS5+TLS proxy described by [proxy].
     * Returns a socket whose input/output streams are tunneled to the target.
     * The caller can layer additional TLS on top for the target's own encryption.
     */
    fun connect(
        proxy: ProxyConfig,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 10_000,
    ): Socket {
        Log.d(TAG, "connect() called: proxy=${proxy.host}:${proxy.port}, target=$targetHost:$targetPort")
        // 1. TLS connection to the GOST proxy
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAllManager), null)
        Log.d(TAG, "Connecting to proxy ${proxy.host}:${proxy.port}...")
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(proxy.host, proxy.port), connectTimeoutMs)
        Log.d(TAG, "Proxy TCP connected")
        val tlsSocket = sslContext.socketFactory.createSocket(
            rawSocket, proxy.host, proxy.port, true
        ) as SSLSocket
        tlsSocket.sslParameters = tlsSocket.sslParameters.apply {
            endpointIdentificationAlgorithm = null
        }
        Log.d(TAG, "Starting proxy TLS handshake...")
        tlsSocket.startHandshake()
        Log.d(TAG, "Proxy TLS handshake complete")

        val out = tlsSocket.outputStream
        val inp = tlsSocket.inputStream

        // 2. SOCKS5 greeting (RFC 1928): offer username/password auth (method 0x02)
        Log.d(TAG, "Sending SOCKS5 greeting")
        out.write(byteArrayOf(0x05, 0x01, 0x02))
        out.flush()

        val greeting = readExactly(inp, 2)
        Log.d(TAG, "Received SOCKS5 greeting response: version=${greeting[0]}, method=${greeting[1]}")
        if (greeting[0] != 0x05.toByte()) {
            throw IOException("Not a SOCKS5 proxy (version=${greeting[0]})")
        }
        if (greeting[1] != 0x02.toByte()) {
            throw IOException("Proxy did not accept username/password auth (method=${greeting[1]})")
        }

        // 3. Username/password sub-negotiation (RFC 1929)
        Log.d(TAG, "Sending SOCKS5 authentication")
        val userBytes = proxy.username.toByteArray(Charsets.UTF_8)
        val passBytes = proxy.password.toByteArray(Charsets.UTF_8)
        val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
        authPacket[0] = 0x01 // sub-negotiation version
        authPacket[1] = userBytes.size.toByte()
        System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
        authPacket[2 + userBytes.size] = passBytes.size.toByte()
        System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
        out.write(authPacket)
        out.flush()

        val authResp = readExactly(inp, 2)
        Log.d(TAG, "Received SOCKS5 auth response: status=${authResp[1]}")
        if (authResp[1] != 0x00.toByte()) {
            throw IOException("SOCKS5 authentication failed (status=${authResp[1]})")
        }

        // 4. CONNECT request — send domain names as-is (type 0x03) so the proxy
        //    resolves them, which handles internal DNS that the phone can't see.
        //    Only use IPv4/IPv6 address types for IP literals.
        Log.d(TAG, "Sending SOCKS5 CONNECT request for $targetHost:$targetPort")
        val ipAddr = try { InetAddress.getByName(targetHost).takeIf {
            // Only treat as IP if the input was already an IP literal (no DNS lookup)
            targetHost == it.hostAddress
        } } catch (_: Exception) { null }

        val connectPacket: ByteArray
        if (ipAddr != null) {
            val ipBytes = ipAddr.address
            val addrType: Byte = if (ipBytes.size == 4) 0x01 else 0x04
            connectPacket = ByteArray(6 + ipBytes.size)
            connectPacket[0] = 0x05
            connectPacket[1] = 0x01
            connectPacket[2] = 0x00
            connectPacket[3] = addrType
            System.arraycopy(ipBytes, 0, connectPacket, 4, ipBytes.size)
            connectPacket[4 + ipBytes.size] = (targetPort shr 8).toByte()
            connectPacket[5 + ipBytes.size] = targetPort.toByte()
        } else {
            // Domain name — let the proxy resolve it
            val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
            connectPacket = ByteArray(7 + domainBytes.size)
            connectPacket[0] = 0x05
            connectPacket[1] = 0x01
            connectPacket[2] = 0x00
            connectPacket[3] = 0x03 // DOMAINNAME
            connectPacket[4] = domainBytes.size.toByte()
            System.arraycopy(domainBytes, 0, connectPacket, 5, domainBytes.size)
            connectPacket[5 + domainBytes.size] = (targetPort shr 8).toByte()
            connectPacket[6 + domainBytes.size] = targetPort.toByte()
        }
        out.write(connectPacket)
        out.flush()

        // Read CONNECT response: 4 bytes header, then address (variable), then 2 bytes port
        Log.d(TAG, "Reading SOCKS5 CONNECT response")
        val connHeader = readExactly(inp, 4)
        if (connHeader[1] != 0x00.toByte()) {
            val errorMsg = when (connHeader[1].toInt() and 0xFF) {
                1 -> "general SOCKS server failure"
                2 -> "connection not allowed by ruleset"
                3 -> "network unreachable"
                4 -> "host unreachable"
                5 -> "connection refused"
                6 -> "TTL expired"
                7 -> "command not supported"
                8 -> "address type not supported"
                else -> "unknown error ${connHeader[1]}"
            }
            Log.e(TAG, "SOCKS5 CONNECT failed: $errorMsg")
            throw IOException("SOCKS5 CONNECT failed: $errorMsg")
        }
        // Consume the bound address + port from the response
        when (connHeader[3].toInt() and 0xFF) {
            0x01 -> readExactly(inp, 4 + 2) // IPv4 + port
            0x04 -> readExactly(inp, 16 + 2) // IPv6 + port
            0x03 -> { // domain name
                val domainLen = readExactly(inp, 1)[0].toInt() and 0xFF
                readExactly(inp, domainLen + 2)
            }
        }

        Log.d(TAG, "SOCKS5 tunnel established successfully")
        return tlsSocket // now tunneled to targetHost:targetPort
    }

    private fun readExactly(input: java.io.InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) throw IOException("SOCKS5 proxy closed connection unexpectedly")
            offset += n
        }
        return buf
    }
}
