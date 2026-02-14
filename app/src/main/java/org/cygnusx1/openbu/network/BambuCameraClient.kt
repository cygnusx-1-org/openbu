package org.cygnusx1.openbu.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

class BambuCameraClient(
    private val ip: String,
    private val accessCode: String,
    private val port: Int = 6000,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private var socket: SSLSocket? = null

    fun frameFlow(): Flow<Bitmap> = flow {
        val sslSocket = connect()
        socket = sslSocket
        try {
            sendAuthPayload(sslSocket)
            val input = sslSocket.inputStream
            val buf = ByteArray(4096)
            val frameBuffer = ByteArrayOutputStream(256 * 1024)
            var inFrame = false

            while (coroutineContext.isActive) {
                val bytesRead = input.read(buf)
                if (bytesRead == -1) throw IOException("Stream closed unexpectedly")

                var i = 0
                while (i < bytesRead) {
                    if (!inFrame) {
                        // Look for JPEG SOI marker: FF D8 FF E0
                        if (i + 3 < bytesRead &&
                            buf[i] == 0xFF.toByte() &&
                            buf[i + 1] == 0xD8.toByte() &&
                            buf[i + 2] == 0xFF.toByte() &&
                            buf[i + 3] == 0xE0.toByte()
                        ) {
                            frameBuffer.reset()
                            frameBuffer.write(buf, i, bytesRead - i)
                            inFrame = true
                            break // rest of this chunk is part of the frame
                        }
                        i++
                    } else {
                        // We're inside a frame — scan for EOI marker: FF D9
                        frameBuffer.write(buf, i, bytesRead - i)
                        // Check if EOI is in what we just wrote
                        val data = frameBuffer.toByteArray()
                        val eoiPos = findEoi(data)
                        if (eoiPos >= 0) {
                            val jpegData = data.copyOfRange(0, eoiPos + 2)
                            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                            if (bitmap != null) {
                                emit(bitmap)
                            }
                            // Any bytes after EOI stay for next scan
                            frameBuffer.reset()
                            val remaining = data.size - (eoiPos + 2)
                            if (remaining > 0) {
                                frameBuffer.write(data, eoiPos + 2, remaining)
                            }
                            inFrame = false
                        }
                        break // we consumed the rest of this chunk
                    }
                }
            }
        } finally {
            close()
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun connect(): SSLSocket {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
        rawSocket.soTimeout = readTimeoutMs

        val sslSocket = sslContext.socketFactory.createSocket(
            rawSocket, ip, port, true
        ) as SSLSocket

        sslSocket.sslParameters = sslSocket.sslParameters.apply {
            endpointIdentificationAlgorithm = null
        }

        sslSocket.startHandshake()
        return sslSocket
    }

    private fun sendAuthPayload(socket: SSLSocket) {
        // 80-byte auth packet: 16-byte header + 32-byte username + 32-byte access code
        val payload = ByteArray(80)

        // Bytes 0-3: 0x40 (little-endian u32)
        payload[0] = 0x40; payload[1] = 0; payload[2] = 0; payload[3] = 0
        // Bytes 4-7: 0x3000 (little-endian u32)
        payload[4] = 0; payload[5] = 0x30; payload[6] = 0; payload[7] = 0
        // Bytes 8-15: zeros (already zeroed)

        // Bytes 16-47: username "bblp" (32 bytes, null-padded)
        val username = "bblp".toByteArray(Charsets.US_ASCII)
        username.copyInto(payload, 16)

        // Bytes 48-79: access code (32 bytes, null-padded)
        val code = accessCode.toByteArray(Charsets.US_ASCII)
        code.copyInto(payload, 48, 0, minOf(code.size, 32))

        socket.outputStream.write(payload)
        socket.outputStream.flush()
    }

    companion object {
        /** Find the last occurrence of FF D9 (JPEG EOI) in the byte array */
        private fun findEoi(data: ByteArray): Int {
            for (i in data.size - 2 downTo 0) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                    return i
                }
            }
            return -1
        }
    }
}
