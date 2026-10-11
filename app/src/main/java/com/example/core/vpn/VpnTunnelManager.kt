package com.example.core.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.model.ServerProfile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VpnTunnelManager(
    private val vpnService: VpnService
) {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)

    private val tunTxBytes = AtomicLong(0)
    private val tunRxBytes = AtomicLong(0)

    val txBytes: Long get() = tunTxBytes.get()
    val rxBytes: Long get() = tunRxBytes.get()

    fun establishTunnel(
        profile: ServerProfile,
        primaryDns: String = "1.1.1.1",
        secondaryDns: String = "8.8.8.8",
        bypassLan: Boolean = true
    ): Boolean {
        try {
            val builder = vpnService.Builder().apply {
                setSession("v2rayDP: ${profile.name}")
                setMtu(1500)
                addAddress("26.26.26.1", 30)

                // Add routes for full tunneling
                if (bypassLan) {
                    // Standard bypass private subnets (RFC 1918)
                    addRoute("0.0.0.0", 5)       // 0.0.0.0 - 7.255.255.255
                    addRoute("8.0.0.0", 7)       // 8.0.0.0 - 9.255.255.255
                    addRoute("11.0.0.0", 8)      // 11.0.0.0 - 11.255.255.255
                    addRoute("12.0.0.0", 6)
                    addRoute("16.0.0.0", 4)
                    addRoute("32.0.0.0", 3)
                    addRoute("64.0.0.0", 2)
                    addRoute("128.0.0.0", 3)
                    addRoute("160.0.0.0", 5)
                    addRoute("168.0.0.0", 6)
                    addRoute("172.0.0.0", 12)
                    addRoute("172.32.0.0", 11)
                    addRoute("172.64.0.0", 10)
                    addRoute("172.128.0.0", 9)
                    addRoute("173.0.0.0", 8)
                    addRoute("174.0.0.0", 7)
                    addRoute("176.0.0.0", 4)
                    addRoute("192.0.0.0", 9)
                    addRoute("192.128.0.0", 11)
                    addRoute("192.160.0.0", 13)
                    addRoute("192.169.0.0", 16)
                    addRoute("192.170.0.0", 15)
                    addRoute("192.172.0.0", 14)
                    addRoute("192.176.0.0", 12)
                    addRoute("193.0.0.0", 8)
                    addRoute("194.0.0.0", 7)
                    addRoute("196.0.0.0", 6)
                    addRoute("200.0.0.0", 5)
                    addRoute("208.0.0.0", 4)
                } else {
                    addRoute("0.0.0.0", 0)
                }

                // DNS servers
                addDnsServer(primaryDns)
                if (secondaryDns.isNotEmpty()) {
                    addDnsServer(secondaryDns)
                }

                setBlocking(false)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Builder.establish() returned null - VPN permission denied or user cancelled")
                return false
            }

            isRunning.set(true)
            startTunPacketLoop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN tunnel: ${e.message}", e)
            return false
        }
    }

    private fun startTunPacketLoop() {
        val pfd = vpnInterface ?: return
        Thread({
            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteBuffer.allocate(32768)

            try {
                while (isRunning.get()) {
                    val bytesRead = inStream.channel.read(buffer)
                    if (bytesRead > 0) {
                        tunTxBytes.addAndGet(bytesRead.toLong())
                        buffer.clear()
                    } else if (bytesRead == 0) {
                        Thread.sleep(10)
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.d(TAG, "TUN packet loop ended: ${e.message}")
                }
            } finally {
                try { inStream.close() } catch (_: Exception) {}
                try { outStream.close() } catch (_: Exception) {}
            }
        }, "v2rayDP-TunLoop").start()
    }

    fun stopTunnel() {
        isRunning.set(false)
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
    }

    companion object {
        private const val TAG = "VpnTunnelManager"
    }
}
