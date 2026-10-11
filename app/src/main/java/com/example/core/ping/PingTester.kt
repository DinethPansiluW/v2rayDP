package com.example.core.ping

import com.example.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PingTester {

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
     * Measures actual network round-trip latency to the server host and port.
     * Uses TCP handshake (and TLS handshake if TLS is enabled with allowInsecure).
     */
    suspend fun testLatency(profile: ServerProfile, timeoutMs: Int = 3500): Int = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.tcpNoDelay = true
            val address = InetSocketAddress(profile.address, profile.port)
            socket.connect(address, timeoutMs)

            if (profile.isTlsEnabled) {
                val sslSocket = insecureSslSocketFactory.createSocket(socket, profile.address, profile.port, true) as SSLSocket
                val sniHost = profile.sni.ifEmpty { profile.address }
                val params = SSLParameters().apply {
                    serverNames = listOf(SNIHostName(sniHost))
                }
                sslSocket.sslParameters = params
                sslSocket.soTimeout = timeoutMs
                sslSocket.startHandshake()
                sslSocket.close()
            } else {
                socket.close()
            }

            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            elapsed.coerceAtLeast(1)
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            -1
        }
    }
}
