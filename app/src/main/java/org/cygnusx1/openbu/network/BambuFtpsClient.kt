package org.cygnusx1.openbu.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.conscrypt.Conscrypt

import org.conscrypt.SSLClientSessionCache
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class BambuFtpsClient(
    private val ip: String,
    private val accessCode: String,
    private val port: Int = 990,
    private val rawSocketFactory: ((String, Int) -> Socket)? = null,
) {
    private var controlSocket: SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null
    @Volatile
    private var activeDataSocket: SSLSocket? = null
    private var dataUseSsl = false

    // Bridges the control channel's TLS session to data channels so vsFTPd accepts
    // the connection when require_ssl_reuse=YES (P2S/X1C). Returns the same session
    // bytes regardless of host:port, since Conscrypt keys its cache by host:port and
    // the data channel uses an ephemeral PASV port that would otherwise miss.
    private val sessionCache = object : SSLClientSessionCache {
        @Volatile private var sessionData: ByteArray? = null
        override fun getSessionData(host: String, port: Int): ByteArray? = sessionData
        override fun putSessionData(session: SSLSession, sessionData: ByteArray?) {
            if (sessionData != null) this.sessionData = sessionData
        }
    }

    private val sslContext: SSLContext by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider())
        ctx.init(null, trustAllCerts, java.security.SecureRandom())
        Conscrypt.setClientSessionCache(ctx, sessionCache)
        ctx
    }

    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "connect() called, rawSocketFactory=${rawSocketFactory != null}")
        try {
            val rawSocket = if (rawSocketFactory != null) {
                Log.d(TAG, "Using rawSocketFactory to create connection to $ip:$port")
                rawSocketFactory.invoke(ip, port)
            } else {
                Log.d(TAG, "Using direct socket connection to $ip:$port")
                val socket = Socket()
                Log.d(TAG, "About to call connect() on raw socket...")
                socket.connect(InetSocketAddress(ip, port), 10_000)
                Log.d(TAG, "Raw socket connect() returned successfully")
                socket
            }
            Log.d(TAG, "Raw socket connected: localPort=${rawSocket.localPort}")
            rawSocket.soTimeout = 30_000

            Log.d(TAG, "Creating SSL socket to $ip:$port")
            val ssl = sslContext.socketFactory.createSocket(rawSocket, ip, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = null }
            // TLS 1.2: session IDs are available immediately after handshake.
            // TLS 1.3 delivers session tickets in post-handshake messages that may not
            // arrive before the data channel opens, leaving the session cache empty.
            ssl.enabledProtocols = arrayOf("TLSv1.2")
            Log.d(TAG, "Enabled protocols: ${ssl.enabledProtocols.joinToString()}")
            Log.d(TAG, "Enabled cipher suites: ${ssl.enabledCipherSuites.joinToString()}")
            Log.d(TAG, "Starting TLS handshake...")
            try {
                ssl.startHandshake()
                Log.d(TAG, "TLS handshake successful")
            } catch (e: Exception) {
                Log.e(TAG, "TLS handshake failed: ${e.message}", e)
                throw e
            }
            val session = ssl.session
            Log.d(TAG, "TLS handshake complete: protocol=${session.protocol}, cipher=${session.cipherSuite}")

            controlSocket = ssl
            reader = BufferedReader(InputStreamReader(ssl.inputStream))
            writer = ssl.outputStream

            Log.d(TAG, "Reading FTP welcome banner...")
            readResponse() // welcome banner

            sendCommand("USER bblp")
            val userResp = readResponse()
            Log.d(TAG, "USER response code: ${userResp.code}")
            if (userResp.code == 331 || userResp.code == 230) {
                if (userResp.code == 331) {
                    Log.d(TAG, "Sending PASS command")
                    sendCommand("PASS $accessCode")
                    val passResp = readResponse()
                    Log.d(TAG, "PASS response code: ${passResp.code}")
                    if (passResp.code != 230) throw IOException("FTP login failed: ${passResp.text}")
                }
            } else {
                throw IOException("FTP USER failed: ${userResp.text}")
            }

            // Enable TLS on data channels. Required even with implicit FTPS (port 990)
            // because vsftpd only sets data_use_ssl via PROT P, not via implicit_ssl.
            sendCommand("PBSZ 0")
            readResponse()
            sendCommand("PROT P")
            val protResp = readResponse()
            if (protResp.code == 200) {
                dataUseSsl = true
            } else {
                Log.w(TAG, "PROT P not supported, falling back to PROT C (clear data channels)")
                sendCommand("PROT C")
                readResponse()
                dataUseSsl = false
            }

            sendCommand("TYPE I")
            readResponse()
        } catch (e: Exception) {
            Log.e(TAG, "connect() failed with exception: ${e.message}", e)
            throw e
        }
    }

    suspend fun listDirectory(path: String): List<FtpFileEntry> = withContext(Dispatchers.IO) {
        sendCommand("CWD $path")
        val cwdResp = readResponse()
        if (cwdResp.code != 250) throw IOException("CWD failed: ${cwdResp.text}")

        val rawDataSocket = openDataConnection()
        sendCommand("LIST")
        val listResp = readResponse()
        if (listResp.code != 150 && listResp.code != 125) {
            rawDataSocket.close()
            throw IOException("LIST failed: ${listResp.text}")
        }

        val dataSocket = maybeUpgradeToSsl(rawDataSocket)

        val dataReader = BufferedReader(InputStreamReader(dataSocket.inputStream))
        val lines = mutableListOf<String>()
        var line = dataReader.readLine()
        while (line != null) {
            lines.add(line)
            line = dataReader.readLine()
        }
        dataSocket.close()

        readResponse() // 226 Transfer complete

        lines.mapNotNull { parseFtpListLine(it) }
    }

    suspend fun downloadFile(
        remotePath: String,
        output: OutputStream,
        totalSize: Long = -1,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val rawDataSocket = openDataConnection()
        sendCommand("RETR $remotePath")
        val resp = readResponse()
        if (resp.code != 150 && resp.code != 125) {
            rawDataSocket.close()
            throw IOException("RETR failed: ${resp.text}")
        }

        val dataSocket = maybeUpgradeToSsl(rawDataSocket)
        activeDataSocket = dataSocket as? SSLSocket
        try {
            val input = dataSocket.inputStream
            val buf = ByteArray(8192)
            var totalRead = 0L
            var n = input.read(buf)
            while (n != -1) {
                output.write(buf, 0, n)
                totalRead += n
                onProgress?.invoke(totalRead, totalSize)
                n = input.read(buf)
            }
            dataSocket.close()
            output.flush()

            readResponse() // 226 Transfer complete
        } finally {
            activeDataSocket = null
        }
    }

    suspend fun uploadFile(
        remotePath: String,
        input: InputStream,
        totalSize: Long = -1,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val rawDataSocket = openDataConnection()
        sendCommand("STOR $remotePath")
        val resp = readResponse()
        if (resp.code != 150 && resp.code != 125) {
            rawDataSocket.close()
            throw IOException("STOR failed: ${resp.text}")
        }

        val dataSocket = maybeUpgradeToSsl(rawDataSocket)
        activeDataSocket = dataSocket as? SSLSocket
        try {
            val output = dataSocket.outputStream
            val buf = ByteArray(8192)
            var written = 0L
            var n = input.read(buf)
            while (n != -1) {
                output.write(buf, 0, n)
                written += n
                onProgress?.invoke(written, totalSize)
                n = input.read(buf)
            }
            output.flush()
            dataSocket.close()

            readResponse() // 226 Transfer complete
        } finally {
            activeDataSocket = null
        }
    }

    suspend fun deleteFile(remotePath: String): Unit = withContext(Dispatchers.IO) {
        sendCommand("DELE $remotePath")
        val resp = readResponse()
        if (resp.code != 250) throw IOException("DELE failed: ${resp.text}")
    }

    fun cancelTransfer() {
        try { activeDataSocket?.close() } catch (_: Exception) {}
        activeDataSocket = null
    }

    suspend fun pwd(): String = withContext(Dispatchers.IO) {
        sendCommand("PWD")
        val resp = readResponse()
        // Extract path from response like: 257 "/" is the current directory
        val match = Regex("\"(.+?)\"").find(resp.text)
        match?.groupValues?.get(1) ?: "/"
    }

    fun close() {
        try {
            writer?.let {
                it.write("QUIT\r\n".toByteArray())
                it.flush()
            }
        } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        reader = null
        writer = null
    }

    private fun sendCommand(cmd: String) {
        Log.d(TAG, ">>> $cmd")
        val w = writer ?: throw IOException("Not connected")
        w.write("$cmd\r\n".toByteArray())
        w.flush()
    }

    private fun readResponse(): FtpResponse {
        val r = reader ?: throw IOException("Not connected")
        val firstLine = r.readLine() ?: throw IOException("Connection closed")
        Log.d(TAG, "<<< $firstLine")

        val code = firstLine.take(3).toIntOrNull() ?: throw IOException("Invalid FTP response: $firstLine")
        val sb = StringBuilder(firstLine)

        // Multi-line response: first line has "code-", continues until "code "
        if (firstLine.length > 3 && firstLine[3] == '-') {
            while (true) {
                val next = r.readLine() ?: break
                Log.d(TAG, "<<< $next")
                sb.appendLine().append(next)
                if (next.length >= 3 && next.take(3).toIntOrNull() == code && (next.length == 3 || next[3] == ' ')) {
                    break
                }
            }
        }

        return FtpResponse(code, sb.toString())
    }

    /**
     * Opens a PASV data connection and returns the raw TCP socket.
     * SSL upgrade is deferred — vsFTPd doesn't call SSL_accept on the data channel
     * until after it receives the FTP command (LIST/RETR/STOR) and sends the 150
     * response. Upgrading before that deadlocks both sides.
     */
    private fun openDataConnection(): Socket {
        sendCommand("PASV")
        val resp = readResponse()
        Log.d(TAG, "PASV response: code=${resp.code}, text=${resp.text}")
        if (resp.code != 227) throw IOException("PASV failed: ${resp.text}")

        // Parse: 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
        val match = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(resp.text)
        if (match == null) {
            Log.e(TAG, "Cannot parse PASV response. Full response: ${resp.text}")
            throw IOException("Cannot parse PASV response: ${resp.text}")
        }
        val parts = match.groupValues.drop(1).map { it.toInt() }
        Log.d(TAG, "PASV parsed parts: $parts")
        val dataHost = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
        val dataPort = parts[4] * 256 + parts[5]
        Log.d(TAG, "PASV result: host=$dataHost, port=$dataPort (calculated from ${parts[4]}*256 + ${parts[5]})")

        Log.d(TAG, "Data channel: connecting to $dataHost:$dataPort, rawSocketFactory=${rawSocketFactory != null}")
        val rawSocket = if (rawSocketFactory != null) {
            Log.d(TAG, "Data channel: using rawSocketFactory")
            rawSocketFactory.invoke(dataHost, dataPort)
        } else {
            Log.d(TAG, "Data channel: using direct socket")
            Socket().apply { connect(InetSocketAddress(dataHost, dataPort), 10_000) }
        }
        rawSocket.soTimeout = 30_000
        Log.d(TAG, "Data channel: TCP connected, localPort=${rawSocket.localPort}")
        return rawSocket
    }

    /**
     * Wraps a raw data channel socket in TLS if PROT P was negotiated, resuming
     * the control channel's session so vsFTPd's require_ssl_reuse check passes.
     * Returns the raw socket unchanged if data channels are clear (PROT C).
     */
    private fun maybeUpgradeToSsl(rawSocket: Socket): Socket {
        if (!dataUseSsl) return rawSocket
        Log.d(TAG, "Data channel: starting SSL handshake")
        val ssl = sslContext.socketFactory.createSocket(
            rawSocket,
            rawSocket.inetAddress.hostAddress,
            rawSocket.port,
            true
        ) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = null }
        ssl.enabledProtocols = arrayOf("TLSv1.2")
        ssl.startHandshake()
        Log.d(TAG, "Data channel: SSL handshake complete, reused=${ssl.session.id.contentEquals(controlSocket?.session?.id ?: byteArrayOf())}")
        return ssl
    }

    private fun parseFtpListLine(line: String): FtpFileEntry? {
        // Unix-style: drwxr-xr-x 2 user group 4096 Jan  1 12:00 dirname
        // Or: -rw-r--r-- 1 user group 12345 Jan  1 12:00 filename
        if (line.isBlank()) return null
        val isDir = line.startsWith('d')
        val parts = line.split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val size = parts[4].toLongOrNull() ?: 0
        val date = "${parts[5]} ${parts[6]} ${parts[7]}"
        val name = parts[8]
        if (name == "." || name == "..") return null
        return FtpFileEntry(name = name, size = size, lastModified = date, isDirectory = isDir)
    }

    private data class FtpResponse(val code: Int, val text: String)

    companion object {
        private const val TAG = "BambuFtps"
    }
}
