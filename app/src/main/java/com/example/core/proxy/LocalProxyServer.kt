package com.example.core.proxy

import android.util.Log
import com.example.core.vmess.VmessProtocolEngine
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
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
                socksServerSocket = ServerSocket(socksPort, 50, InetAddress.getByName("0.0.0.0"))
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
                httpServerSocket = ServerSocket(httpPort, 50, InetAddress.getByName("0.0.0.0"))
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
            val methods = ByteArray(nMethods)
            inStream.read(methods)

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
                    inStream.read(ip)
                    destHost = InetAddress.getByAddress(ip).hostAddress ?: "127.0.0.1"
                }
                0x03 -> { // Domain name
                    val len = inStream.read()
                    val domainBytes = ByteArray(len)
                    inStream.read(domainBytes)
                    destHost = String(domainBytes, StandardCharsets.UTF_8)
                }
                0x04 -> { // IPv6
                    val ip6 = ByteArray(16)
                    inStream.read(ip6)
                    destHost = InetAddress.getByAddress(ip6).hostAddress ?: "::1"
                }
            }

            val portHigh = inStream.read()
            val portLow = inStream.read()
            val destPort = (portHigh shl 8) or portLow

            // Open remote VMess connection
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

    private fun handleHttpClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val inStream = client.getInputStream()
            val outStream = client.getOutputStream()

            val reader = inStream.bufferedReader(StandardCharsets.UTF_8)
            val initialLine = reader.readLine() ?: return

            if (initialLine.startsWith("CONNECT ", ignoreCase = true)) {
                // CONNECT host:port HTTP/1.1
                val parts = initialLine.split(" ")
                val hostPort = parts[1].split(":")
                val host = hostPort[0]
                val port = if (hostPort.size > 1) hostPort[1].toInt() else 443

                // Drain rest of headers
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                // Connect to remote VMess
                val tunnel = vmessEngine.openRemoteConnection(host, port)

                // Send 200 OK
                outStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                outStream.flush()

                tunnel.relayDuplex(inStream, outStream)
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
