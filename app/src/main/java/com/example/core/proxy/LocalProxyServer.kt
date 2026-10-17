package com.example.core.proxy

import android.util.Log
import com.example.core.vmess.VmessProtocolEngine
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalProxyServer(
    private val vmessEngine: VmessProtocolEngine,
    val socksPort: Int = 10808,
    val httpPort: Int = 10809
) {
    private val isRunning = AtomicBoolean(false)
    private val firstConnectLogged = AtomicBoolean(false)
    private var socksServerSocket: ServerSocket? = null
    private var httpServerSocket: ServerSocket? = null
    private var threadPool: ExecutorService? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        threadPool = Executors.newCachedThreadPool()

        // SOCKS5 Server
        threadPool?.submit {
            try {
                // Bind to 0.0.0.0 to enable both localhost and Wi-Fi hotspot client access
                socksServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), socksPort), 50)
                }
                Log.d(TAG, "Local SOCKS5 proxy listening on 0.0.0.0:$socksPort")
                while (isRunning.get()) {
                    val clientSocket = socksServerSocket?.accept() ?: break
                    threadPool?.submit {
                        handleSocksClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "SOCKS server error: ${e.message}")
            }
        }

        // HTTP Proxy Server
        threadPool?.submit {
            try {
                httpServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), httpPort), 50)
                }
                Log.d(TAG, "Local HTTP proxy listening on 0.0.0.0:$httpPort")
                while (isRunning.get()) {
                    val clientSocket = httpServerSocket?.accept() ?: break
                    threadPool?.submit {
                        handleHttpClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "HTTP server error: ${e.message}")
            }
        }
    }

    private fun readFully(inStream: InputStream, target: ByteArray) {
        var total = 0
        while (total < target.size) {
            val r = inStream.read(target, total, target.size - total)
            if (r < 0) throw java.io.EOFException("Unexpected EOF reading ${target.size} bytes (got $total)")
            total += r
        }
    }

    private fun handleSocksClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val inStream = client.getInputStream()
            val outStream = client.getOutputStream()

            // 1. Handshake: version 5
            val ver = inStream.read()
            if (ver != 0x05) {
                client.close()
                return
            }
            val nMethods = inStream.read()
            if (nMethods < 0) return
            val methods = ByteArray(nMethods)
            readFully(inStream, methods)

            // Respond: 0x05, 0x00 (NO AUTH)
            outStream.write(byteArrayOf(0x05, 0x00))
            outStream.flush()

            // 2. Request
            val reqVer = inStream.read()
            val cmd = inStream.read()
            inStream.read() // RSV
            val atyp = inStream.read()

            if (cmd != 0x01) { // CONNECT command only
                outStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Command not supported
                client.close()
                return
            }

            var destHost = ""
            when (atyp) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4)
                    readFully(inStream, ip)
                    destHost = InetAddress.getByAddress(ip).hostAddress ?: "127.0.0.1"
                }
                0x03 -> { // Domain name
                    val len = inStream.read()
                    if (len <= 0) return
                    val domainBytes = ByteArray(len)
                    readFully(inStream, domainBytes)
                    destHost = String(domainBytes, StandardCharsets.UTF_8)
                }
                0x04 -> { // IPv6
                    val ip6 = ByteArray(16)
                    readFully(inStream, ip6)
                    destHost = InetAddress.getByAddress(ip6).hostAddress ?: "::1"
                }
                else -> {
                    client.close()
                    return
                }
            }

            val portHigh = inStream.read()
            val portLow = inStream.read()
            if (portHigh < 0 || portLow < 0) return
            val destPort = (portHigh shl 8) or portLow

            val isFirst = firstConnectLogged.compareAndSet(false, true)
            if (isFirst) {
                Log.d(TAG, "FIRST SOCKS5 CONNECT request seen: $destHost:$destPort")
            } else {
                Log.d(TAG, "SOCKS5 CONNECT request: $destHost:$destPort")
            }

            // Open remote VLESS / VMess connection
            val tunnel = vmessEngine.openRemoteConnection(destHost, destPort)

            // Respond success: 0x05, 0x00 (succeeded), 0x00 (RSV), 0x01 (IPv4), 0,0,0,0, 0,0
            outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outStream.flush()

            // Duplex relay
            tunnel.relayDuplex(inStream, outStream)
        } catch (e: Exception) {
            // Handshake or network abort
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readRawHttpHeader(inStream: InputStream): String {
        val baos = java.io.ByteArrayOutputStream()
        var state = 0 // 0: normal, 1: \r, 2: \r\n, 3: \r\n\r, 4: \r\n\r\n
        while (true) {
            val b = inStream.read()
            if (b == -1) break
            baos.write(b)
            when (state) {
                0 -> if (b == '\r'.code) state = 1 else if (b == '\n'.code) state = 2
                1 -> if (b == '\n'.code) state = 2 else state = 0
                2 -> if (b == '\r'.code) state = 3 else if (b == '\n'.code) break else state = 0
                3 -> if (b == '\n'.code) break else state = 0
            }
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    private fun handleHttpClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val inStream = client.getInputStream()
            val outStream = client.getOutputStream()

            val rawHeader = readRawHttpHeader(inStream)
            if (rawHeader.isEmpty()) return

            val lines = rawHeader.split("\r\n")
            val initialLine = lines.firstOrNull() ?: return

            if (initialLine.startsWith("CONNECT ", ignoreCase = true)) {
                // CONNECT host:port HTTP/1.1
                val parts = initialLine.split(" ")
                val hostPort = parts[1].split(":")
                val host = hostPort[0]
                val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

                // Connect to remote VLESS / VMess
                val tunnel = vmessEngine.openRemoteConnection(host, port)

                // Send 200 Connection Established
                outStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                outStream.flush()

                // Tunnel remainder of TLS handshake / duplex traffic
                tunnel.relayDuplex(inStream, outStream)
            } else {
                // Transparent HTTP GET / POST
                var hostHeader = ""
                for (line in lines) {
                    if (line.startsWith("Host:", ignoreCase = true)) {
                        hostHeader = line.substring(5).trim()
                        break
                    }
                }

                if (hostHeader.isNotEmpty()) {
                    val hp = hostHeader.split(":")
                    val targetHost = hp[0]
                    val targetPort = if (hp.size > 1) hp[1].toIntOrNull() ?: 80 else 80

                    // Rewrite absolute URI in request line (e.g. "GET http://example.com/foo" -> "GET /foo")
                    val firstLineParts = initialLine.split(" ")
                    val method = firstLineParts.getOrNull(0) ?: "GET"
                    val rawUri = firstLineParts.getOrNull(1) ?: "/"
                    val version = firstLineParts.getOrNull(2) ?: "HTTP/1.1"

                    val relativePath = if (rawUri.startsWith("http://", ignoreCase = true)) {
                        val slashIdx = rawUri.indexOf('/', 7)
                        if (slashIdx != -1) rawUri.substring(slashIdx) else "/"
                    } else rawUri

                    val rewrittenLines = mutableListOf<String>()
                    rewrittenLines.add("$method $relativePath $version")
                    for (i in 1 until lines.size) {
                        rewrittenLines.add(lines[i])
                    }
                    val requestToSend = rewrittenLines.joinToString("\r\n") + "\r\n\r\n"

                    val tunnel = vmessEngine.openRemoteConnection(targetHost, targetPort)
                    // Write normalized HTTP request header to tunnel
                    tunnel.outputStream.write(requestToSend.toByteArray(StandardCharsets.UTF_8))
                    tunnel.outputStream.flush()

                    // Relay remainder of request / response
                    tunnel.relayDuplex(inStream, outStream)
                }
            }
        } catch (e: Exception) {
            // Client closed or connection aborted
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning.set(false)
        try { socksServerSocket?.close() } catch (_: Exception) {}
        try { httpServerSocket?.close() } catch (_: Exception) {}
        threadPool?.shutdownNow()
        threadPool = null
        socksServerSocket = null
        httpServerSocket = null
    }

    companion object {
        private const val TAG = "LocalProxyServer"
    }
}
