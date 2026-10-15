package com.example.core.vmess

import android.util.Base64
import com.example.model.ServerProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class VmessProtocolEngine(
    private val profile: ServerProfile,
    private val protectSocket: (Socket) -> Boolean
) {
    private val rxCounter = AtomicLong(0)
    private val txCounter = AtomicLong(0)

    val totalRxBytes: Long get() = rxCounter.get()
    val totalTxBytes: Long get() = txCounter.get()

    private val insecureSslSocketFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        sslContext.socketFactory
    }

    /**
     * Connects to the remote server using the profile's protocol (VLESS or VMess) and network transport.
     */
    fun openRemoteConnection(targetHost: String, targetPort: Int, timeoutMs: Int = 10000): RemoteTunnel {
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true

        // Protect socket so VPN does not route its own outbound traffic
        protectSocket(rawSocket)

        val serverAddress = InetSocketAddress(profile.address, profile.port)
        rawSocket.connect(serverAddress, timeoutMs)

        var activeSocket: Socket = rawSocket

        // TLS Handshake with "always allow insecure" trust manager
        if (profile.isTlsEnabled) {
            val sniHost = profile.sni.ifEmpty { profile.host.ifEmpty { profile.address } }
            val sslSocket = insecureSslSocketFactory.createSocket(rawSocket, sniHost, profile.port, true) as SSLSocket
            val params = sslSocket.sslParameters ?: SSLParameters()
            params.serverNames = listOf(SNIHostName(sniHost))
            sslSocket.sslParameters = params
            sslSocket.startHandshake()
            activeSocket = sslSocket
        }

        var outStream = activeSocket.getOutputStream()
        var inStream = activeSocket.getInputStream()

        // If WebSocket network transport, perform RFC 6455 upgrade handshake
        val isWebSocket = profile.network.equals("ws", ignoreCase = true)
        if (isWebSocket) {
            performWebSocketHandshake(outStream, inStream)
            // Wrap in WebSocket framing streams
            outStream = WebSocketOutputStream(outStream)
            inStream = WebSocketInputStream(inStream)
        }

        // Send Protocol Request Header (VLESS or VMess)
        if (profile.isVless) {
            sendVlessHeader(outStream, targetHost, targetPort)
            inStream = VlessInputStream(inStream)
        } else {
            sendVmessHeader(outStream, targetHost, targetPort)
        }

        return RemoteTunnel(activeSocket, inStream, outStream)
    }

    private fun performWebSocketHandshake(out: OutputStream, input: InputStream) {
        val path = profile.path.ifEmpty { "/" }
        val hostHeader = profile.host.ifEmpty { profile.sni.ifEmpty { profile.address } }
        val secWebSocketKey = Base64.encodeToString(UUID.randomUUID().toString().toByteArray(), Base64.NO_WRAP)

        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $secWebSocketKey\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: v2rayDP/1.0 (NetMod-Compatible)\r\n\r\n")
        }

        val requestBytes = request.toByteArray(StandardCharsets.UTF_8)
        out.write(requestBytes)
        out.flush()
        txCounter.addAndGet(requestBytes.size.toLong())

        // Read HTTP response headers until "\r\n\r\n"
        val headerBuffer = StringBuilder()
        val buffer = ByteArray(1)
        while (input.read(buffer) != -1) {
            rxCounter.incrementAndGet()
            headerBuffer.append(buffer[0].toInt().toChar())
            if (headerBuffer.endsWith("\r\n\r\n")) {
                break
            }
        }

        val responseStr = headerBuffer.toString()
        if (!responseStr.startsWith("HTTP/1.1 101") && !responseStr.startsWith("HTTP/1.0 101")) {
            throw IllegalStateException("WebSocket handshake failed: ${responseStr.lines().firstOrNull()}")
        }
    }

    private fun sendVlessHeader(out: OutputStream, targetHost: String, targetPort: Int) {
        val uuidBytes = parseUuidToBytes(profile.uuid)
        val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)

        val ipParts = targetHost.split(".")
        val isIpv4 = ipParts.size == 4 && ipParts.all { it.toIntOrNull() in 0..255 }

        // VLESS Request Format:
        // 1B Version (0x00) + 16B UUID + 1B Addons Length (0x00) + 1B Command (0x01 = TCP)
        // + 2B Port + 1B Address Type + Address Bytes
        val addrLen = if (isIpv4) 4 else (1 + hostBytes.size)
        val header = ByteBuffer.allocate(1 + 16 + 1 + 1 + 2 + 1 + addrLen)
        header.put(0x00) // Version 0
        header.put(uuidBytes)
        header.put(0x00) // Addons length = 0
        header.put(0x01) // Command: 0x01 = TCP
        header.putShort(targetPort.toShort())

        if (isIpv4) {
            header.put(0x01) // Address type: IPv4
            for (p in ipParts) {
                header.put(p.toInt().toByte())
            }
        } else {
            header.put(0x02) // Address type: Domain
            header.put(hostBytes.size.toByte())
            header.put(hostBytes)
        }

        val headerBytes = header.array()
        out.write(headerBytes)
        out.flush()
        txCounter.addAndGet(headerBytes.size.toLong())
    }

    /**
     * VLESS Response Header Stream: lazily strips the 2-byte server response header
     * [Version, AddonLength, ...Addons] upon first read of remote response.
     */
    inner class VlessInputStream(private val rawIn: InputStream) : InputStream() {
        private var headerConsumed = false

        private fun consumeHeaderIfNeeded() {
            if (headerConsumed) return
            try {
                val version = rawIn.read()
                if (version == -1) {
                    headerConsumed = true
                    return
                }
                val addonLen = rawIn.read()
                if (addonLen > 0) {
                    var skipped = 0
                    while (skipped < addonLen) {
                        val s = rawIn.skip((addonLen - skipped).toLong())
                        if (s <= 0) {
                            if (rawIn.read() == -1) break
                            skipped += 1
                        } else {
                            skipped += s.toInt()
                        }
                    }
                }
            } catch (_: Exception) {}
            headerConsumed = true
        }

        override fun read(): Int {
            consumeHeaderIfNeeded()
            val b = rawIn.read()
            if (b != -1) rxCounter.incrementAndGet()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            consumeHeaderIfNeeded()
            val r = rawIn.read(b, off, len)
            if (r != -1) rxCounter.addAndGet(r.toLong())
            return r
        }

        override fun close() {
            rawIn.close()
        }
    }

    private fun sendVmessHeader(out: OutputStream, targetHost: String, targetPort: Int) {
        val uuidBytes = parseUuidToBytes(profile.uuid)
        val timestamp = System.currentTimeMillis() / 1000

        // 1. Generate 16-byte HMAC-MD5 Authentication Chunk
        val authMac = Mac.getInstance("HmacMD5")
        authMac.init(SecretKeySpec(uuidBytes, "HmacMD5"))
        val timeBytes = ByteBuffer.allocate(8).putLong(timestamp).array()
        val authChunk = authMac.doFinal(timeBytes) // 16 bytes

        // 2. Body Header
        val headerPayload = ByteBuffer.allocate(512)
        headerPayload.put(0x01) // Version 1
        val reqIv = ByteArray(16).apply { (0..15).forEach { this[it] = (it * 3).toByte() } }
        val reqKey = ByteArray(16).apply { (0..15).forEach { this[it] = (it * 7).toByte() } }
        headerPayload.put(reqIv)
        headerPayload.put(reqKey)
        headerPayload.put(0x00) // Response auth
        headerPayload.put(0x01) // Option: standard chunk
        headerPayload.put(0x00) // Padding length
        headerPayload.put(0x01) // Security: AES-128-CFB
        headerPayload.put(0x00) // Reserved
        headerPayload.put(0x01) // Command: 0x01 = TCP

        headerPayload.putShort(targetPort.toShort())

        val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
        headerPayload.put(0x02) // Domain
        headerPayload.put(hostBytes.size.toByte())
        headerPayload.put(hostBytes)

        val bodyBytes = ByteArray(headerPayload.position())
        System.arraycopy(headerPayload.array(), 0, bodyBytes, 0, bodyBytes.size)

        val cipherKey = MessageDigest.getInstance("MD5").digest(uuidBytes)
        val cipherIv = MessageDigest.getInstance("MD5").digest(timeBytes)

        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(cipherIv))
        val encryptedBody = cipher.doFinal(bodyBytes)

        val finalHeader = ByteBuffer.allocate(authChunk.size + encryptedBody.size)
        finalHeader.put(authChunk)
        finalHeader.put(encryptedBody)

        val bytesToSend = finalHeader.array()
        out.write(bytesToSend)
        out.flush()
        txCounter.addAndGet(bytesToSend.size.toLong())
    }

    private fun parseUuidToBytes(uuidStr: String): ByteArray {
        val sanitized = uuidStr.replace("-", "").trim()
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            val byteStr = sanitized.substring(i * 2, i * 2 + 2)
            bytes[i] = byteStr.toInt(16).toByte()
        }
        return bytes
    }

    /**
     * RFC 6455 Client-to-Server Masked Binary Frame Writer
     */
    inner class WebSocketOutputStream(private val rawOut: OutputStream) : OutputStream() {
        private val random = SecureRandom()

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return

            // 0x82: FIN = 1, Opcode = 2 (Binary)
            rawOut.write(0x82)

            // Client frames must be masked (bit 7 = 1)
            if (len <= 125) {
                rawOut.write(0x80 or len)
            } else if (len <= 65535) {
                rawOut.write(0x80 or 126)
                rawOut.write((len shr 8) and 0xFF)
                rawOut.write(len and 0xFF)
            } else {
                rawOut.write(0x80 or 127)
                for (i in 7 downTo 0) {
                    rawOut.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }

            // 4-byte random masking key
            val mask = ByteArray(4)
            random.nextBytes(mask)
            rawOut.write(mask)

            // Masked payload
            val masked = ByteArray(len)
            for (i in 0 until len) {
                masked[i] = (b[off + i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            rawOut.write(masked)
            rawOut.flush()
        }

        override fun flush() {
            rawOut.flush()
        }

        override fun close() {
            rawOut.close()
        }
    }

    /**
     * RFC 6455 Server-to-Client Frame Reader & Deframer
     */
    inner class WebSocketInputStream(private val rawIn: InputStream) : InputStream() {
        private var currentPayload: ByteArray? = null
        private var payloadIndex = 0

        override fun read(): Int {
            val buf = ByteArray(1)
            val r = read(buf, 0, 1)
            return if (r == -1) -1 else (buf[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (currentPayload == null || payloadIndex >= (currentPayload?.size ?: 0)) {
                if (!readNextFrame()) {
                    return -1
                }
            }

            val payload = currentPayload ?: return -1
            val available = payload.size - payloadIndex
            val toRead = minOf(len, available)
            System.arraycopy(payload, payloadIndex, b, off, toRead)
            payloadIndex += toRead
            return toRead
        }

        private fun readNextFrame(): Boolean {
            while (true) {
                val b1 = rawIn.read()
                if (b1 == -1) return false

                val opcode = b1 and 0x0F
                val b2 = rawIn.read()
                if (b2 == -1) return false

                val isMasked = (b2 and 0x80) != 0
                var payloadLength = (b2 and 0x7F).toLong()

                if (payloadLength == 126L) {
                    val h1 = rawIn.read()
                    val h2 = rawIn.read()
                    if (h1 == -1 || h2 == -1) return false
                    payloadLength = ((h1 shl 8) or h2).toLong()
                } else if (payloadLength == 127L) {
                    var l = 0L
                    for (i in 0 until 8) {
                        val byte = rawIn.read()
                        if (byte == -1) return false
                        l = (l shl 8) or (byte.toLong() and 0xFF)
                    }
                    payloadLength = l
                }

                val mask = if (isMasked) {
                    val m = ByteArray(4)
                    var readM = 0
                    while (readM < 4) {
                        val r = rawIn.read(m, readM, 4 - readM)
                        if (r == -1) return false
                        readM += r
                    }
                    m
                } else null

                val data = ByteArray(payloadLength.toInt())
                var totalRead = 0
                while (totalRead < data.size) {
                    val r = rawIn.read(data, totalRead, data.size - totalRead)
                    if (r == -1) return false
                    totalRead += r
                }

                if (mask != null) {
                    for (i in data.indices) {
                        data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x08 -> return false // Close frame
                    0x09 -> {
                        // Ping frame: ignore
                        continue
                    }
                    0x0A -> {
                        // Pong frame: ignore
                        continue
                    }
                    0x01, 0x02 -> {
                        // Text or Binary data frame
                        currentPayload = data
                        payloadIndex = 0
                        return true
                    }
                }
            }
        }

        override fun close() {
            rawIn.close()
        }
    }

    inner class RemoteTunnel(
        val socket: Socket,
        val inputStream: InputStream,
        val outputStream: OutputStream
    ) {
        fun relayDuplex(clientIn: InputStream, clientOut: OutputStream) {
            val t1 = Thread {
                try {
                    val buffer = ByteArray(16384)
                    var count: Int
                    while (clientIn.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                        outputStream.flush()
                        txCounter.addAndGet(count.toLong())
                    }
                } catch (_: Exception) {}
                finally {
                    close()
                }
            }

            val t2 = Thread {
                try {
                    val buffer = ByteArray(16384)
                    var count: Int
                    while (inputStream.read(buffer).also { count = it } != -1) {
                        clientOut.write(buffer, 0, count)
                        clientOut.flush()
                        rxCounter.addAndGet(count.toLong())
                    }
                } catch (_: Exception) {}
                finally {
                    close()
                }
            }

            t1.start()
            t2.start()
            t1.join()
            t2.join()
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
