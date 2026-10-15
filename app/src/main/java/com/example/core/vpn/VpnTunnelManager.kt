package com.example.core.vpn

import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.model.ServerProfile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
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
                // RFC 1918 private address space for TUN interface (avoids route conflicts)
                addAddress("172.19.0.1", 30)

                // Prevent VPN routing loop by excluding v2rayDP app itself
                try {
                    addDisallowedApplication(vpnService.packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication warning: ${e.message}")
                }

                // 1. Default Route (0.0.0.0/0): Essential for Android to grant NET_CAPABILITY_INTERNET
                addRoute("0.0.0.0", 0)

                // 2. Route Adjustments & Local Ranges Exclusion:
                // Exclude RFC 1918 private LAN, link-local, loopback, and multicast subnets from TUN routing
                // so local router, gateway, ARP, and LAN discovery traffic never get trapped in the tunnel.
                if (bypassLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        excludeRoute(android.net.IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
                        excludeRoute(android.net.IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                        excludeRoute(android.net.IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                        excludeRoute(android.net.IpPrefix(InetAddress.getByName("169.254.0.0"), 16))
                        excludeRoute(android.net.IpPrefix(InetAddress.getByName("127.0.0.0"), 8))
                        excludeRoute(android.net.IpPrefix(InetAddress.getByName("224.0.0.0"), 4))
                    } catch (e: Exception) {
                        Log.w(TAG, "excludeRoute error: ${e.message}")
                    }
                }

                // 3. DNS Configuration: Primary and secondary public DNS resolvers
                val dnsList = linkedSetOf<String>()
                if (primaryDns.isNotBlank()) dnsList.add(primaryDns.trim())
                if (secondaryDns.isNotBlank()) dnsList.add(secondaryDns.trim())
                dnsList.add("1.1.1.1")
                dnsList.add("8.8.8.8")
                for (dns in dnsList) {
                    try {
                        addDnsServer(dns)
                    } catch (e: Exception) {
                        Log.w(TAG, "addDnsServer $dns error: ${e.message}")
                    }
                }

                // 4. Associate underlying physical network so Android inherits validated network capability
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    try {
                        val cm = vpnService.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                        val activeNet = cm?.activeNetwork
                        if (activeNet != null) {
                            vpnService.setUnderlyingNetworks(arrayOf(activeNet))
                        } else {
                            vpnService.setUnderlyingNetworks(null)
                        }
                    } catch (_: Exception) {}
                }

                setBlocking(false)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Builder.establish() returned null - VPN permission denied or user cancelled")
                return false
            }

            isRunning.set(true)
            startTunPacketLoop(primaryDns)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN tunnel: ${e.message}", e)
            return false
        }
    }

    private fun startTunPacketLoop(primaryDns: String) {
        val pfd = vpnInterface ?: return
        Thread({
            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteBuffer.allocate(32768)

            val upstreamDns = try {
                InetAddress.getByName(primaryDns.ifEmpty { "1.1.1.1" })
            } catch (_: Exception) {
                InetAddress.getByName("1.1.1.1")
            }

            val dnsExecutor = Executors.newFixedThreadPool(4)
            val syntheticCaptiveIp = byteArrayOf(172.toByte(), 19.toByte(), 0.toByte(), 2.toByte())

            try {
                while (isRunning.get()) {
                    buffer.clear()
                    val bytesRead = inStream.channel.read(buffer)
                    if (bytesRead > 0) {
                        tunTxBytes.addAndGet(bytesRead.toLong())
                        buffer.flip()

                        val packet = ByteArray(bytesRead)
                        buffer.get(packet)

                        if (packet.size >= 28) {
                            val versionAndIHL = packet[0].toInt() and 0xFF
                            val version = versionAndIHL ushr 4
                            val ihl = (versionAndIHL and 0x0F) * 4
                            val protocol = packet[9].toInt() and 0xFF

                            // UDP Port 53: DNS Interceptor
                            if (version == 4 && protocol == 17 && packet.size >= ihl + 8) {
                                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                                val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)

                                if (dstPort == 53 && udpLen >= 8 && packet.size >= ihl + udpLen) {
                                    val dnsQuery = ByteArray(udpLen - 8)
                                    System.arraycopy(packet, ihl + 8, dnsQuery, 0, dnsQuery.size)

                                    val clientIp = ByteArray(4).apply { System.arraycopy(packet, 12, this, 0, 4) }
                                    val dnsServerIp = ByteArray(4).apply { System.arraycopy(packet, 16, this, 0, 4) }

                                    // Instant Sub-millisecond Captive Portal DNS Synthesis
                                    // Prevents Android NetworkMonitor probes from timing out while core proxy connects
                                    if (isCaptivePortalDnsQuery(dnsQuery)) {
                                        val syntheticResp = buildSyntheticDnsResponse(dnsQuery, syntheticCaptiveIp)
                                        if (syntheticResp != null) {
                                            val respIpPacket = buildUdpIpPacket(
                                                srcIp = dnsServerIp,
                                                dstIp = clientIp,
                                                srcPort = 53,
                                                dstPort = srcPort,
                                                payload = syntheticResp,
                                                payloadLen = syntheticResp.size
                                            )
                                            synchronized(outStream) {
                                                outStream.write(respIpPacket)
                                                outStream.flush()
                                                tunRxBytes.addAndGet(respIpPacket.size.toLong())
                                            }
                                            continue
                                        }
                                    }

                                    // Real DNS forwarding: dedicated protected DatagramSocket per query (thread-safe)
                                    dnsExecutor.submit {
                                        var socket: DatagramSocket? = null
                                        try {
                                            socket = DatagramSocket().apply {
                                                soTimeout = 3000
                                            }
                                            vpnService.protect(socket)

                                            val queryPacket = DatagramPacket(dnsQuery, dnsQuery.size, upstreamDns, 53)
                                            socket.send(queryPacket)

                                            val responseBuf = ByteArray(2048)
                                            val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
                                            socket.receive(responsePacket)

                                            val respLen = responsePacket.length
                                            val respIpPacket = buildUdpIpPacket(
                                                srcIp = dnsServerIp,
                                                dstIp = clientIp,
                                                srcPort = 53,
                                                dstPort = srcPort,
                                                payload = responseBuf,
                                                payloadLen = respLen
                                            )

                                            synchronized(outStream) {
                                                outStream.write(respIpPacket)
                                                outStream.flush()
                                                tunRxBytes.addAndGet(respIpPacket.size.toLong())
                                            }
                                        } catch (_: Exception) {
                                        } finally {
                                            try { socket?.close() } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }

                            // TCP Port 80: Captive Portal Probe Interceptor (Android NetworkMonitor /generate_204)
                            if (version == 4 && protocol == 6 && packet.size >= ihl + 20) {
                                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                                if (dstPort == 80) {
                                    val clientSeq = ((packet[ihl + 4].toLong() and 0xFF) shl 24) or
                                            ((packet[ihl + 5].toLong() and 0xFF) shl 16) or
                                            ((packet[ihl + 6].toLong() and 0xFF) shl 8) or
                                            (packet[ihl + 7].toLong() and 0xFF)
                                    val flags = packet[ihl + 13].toInt() and 0xFF
                                    val clientIp = ByteArray(4).apply { System.arraycopy(packet, 12, this, 0, 4) }
                                    val serverIp = ByteArray(4).apply { System.arraycopy(packet, 16, this, 0, 4) }
                                    val tcpDataOffset = ((packet[ihl + 12].toInt() and 0xF0) ushr 4) * 4
                                    val payloadLen = (packet.size - (ihl + tcpDataOffset)).coerceAtLeast(0)

                                    if ((flags and 0x02) != 0) {
                                        // SYN packet -> Respond with SYN-ACK immediately
                                        val synAck = buildTcpIpPacket(
                                            srcIp = serverIp,
                                            dstIp = clientIp,
                                            srcPort = dstPort,
                                            dstPort = srcPort,
                                            seq = 100000L,
                                            ack = (clientSeq + 1) and 0xFFFFFFFFL,
                                            flags = 0x12, // SYN | ACK
                                            payload = ByteArray(0)
                                        )
                                        synchronized(outStream) {
                                            outStream.write(synAck)
                                            outStream.flush()
                                        }
                                    } else if (payloadLen > 0) {
                                        // Client sent HTTP probe -> Respond with standard HTTP 204 No Content
                                        val http204 = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                            .toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
                                        val respPacket = buildTcpIpPacket(
                                            srcIp = serverIp,
                                            dstIp = clientIp,
                                            srcPort = dstPort,
                                            dstPort = srcPort,
                                            seq = 100001L,
                                            ack = (clientSeq + payloadLen) and 0xFFFFFFFFL,
                                            flags = 0x19, // FIN | PSH | ACK
                                            payload = http204
                                        )
                                        synchronized(outStream) {
                                            outStream.write(respPacket)
                                            outStream.flush()
                                            tunRxBytes.addAndGet(respPacket.size.toLong())
                                        }
                                    } else if ((flags and 0x01) != 0) {
                                        // FIN packet -> Send FIN-ACK
                                        val finAck = buildTcpIpPacket(
                                            srcIp = serverIp,
                                            dstIp = clientIp,
                                            srcPort = dstPort,
                                            dstPort = srcPort,
                                            seq = 100002L,
                                            ack = (clientSeq + 1) and 0xFFFFFFFFL,
                                            flags = 0x11, // FIN | ACK
                                            payload = ByteArray(0)
                                        )
                                        synchronized(outStream) {
                                            outStream.write(finAck)
                                            outStream.flush()
                                        }
                                    }
                                }
                            }
                        }
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
                dnsExecutor.shutdownNow()
                try { inStream.close() } catch (_: Exception) {}
                try { outStream.close() } catch (_: Exception) {}
            }
        }, "v2rayDP-TunLoop").start()
    }

    private fun isCaptivePortalDnsQuery(query: ByteArray): Boolean {
        if (query.size < 12) return false
        val probeKeywords = arrayOf(
            "connectivitycheck",
            "clients3",
            "gstatic",
            "captive",
            "msftconnecttest",
            "detectportal",
            "gen_204"
        )
        val queryStr = String(query, java.nio.charset.StandardCharsets.ISO_8859_1).lowercase()
        return probeKeywords.any { queryStr.contains(it) }
    }

    private fun buildSyntheticDnsResponse(query: ByteArray, answerIp: ByteArray): ByteArray? {
        if (query.size < 12) return null
        var offset = 12
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            if (len == 0) {
                offset += 1
                break
            }
            offset += 1 + len
        }
        offset += 4 // QTYPE (2) + QCLASS (2)
        if (offset > query.size) return null

        val questionLen = offset
        val response = ByteBuffer.allocate(questionLen + 16)
        // DNS Header (12 bytes)
        response.put(query[0]) // ID 0
        response.put(query[1]) // ID 1
        response.putShort(0x8180.toShort()) // Flags: Standard Query Response, No Error, Recursion Available
        response.putShort(1.toShort())      // Questions: 1
        response.putShort(1.toShort())      // Answer RRs: 1
        response.putShort(0.toShort())      // Authority RRs: 0
        response.putShort(0.toShort())      // Additional RRs: 0

        // Question Section
        response.put(query, 12, questionLen - 12)

        // Answer Section: Pointer to QNAME at 0x000C
        response.putShort(0xC00C.toShort())
        response.putShort(1.toShort())      // Type A (IPv4)
        response.putShort(1.toShort())      // Class IN
        response.putInt(60)                 // TTL: 60 seconds
        response.putShort(4.toShort())      // RDLENGTH: 4 bytes
        response.put(answerIp)              // RDATA: IPv4 Address

        return response.array()
    }

    private fun buildTcpIpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val payloadLen = payload.size
        val tcpLen = 20 + payloadLen
        val totalLen = 20 + tcpLen
        val packet = ByteBuffer.allocate(totalLen)

        // IPv4 Header (20 bytes)
        packet.put(0x45.toByte())
        packet.put(0x00.toByte())
        packet.putShort(totalLen.toShort())
        packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
        packet.putShort(0x4000.toShort()) // DF
        packet.put(64.toByte())
        packet.put(6.toByte()) // Protocol: TCP
        packet.putShort(0.toShort()) // Placeholder
        packet.put(srcIp)
        packet.put(dstIp)

        val ipChecksum = computeIpChecksum(packet.array(), 0, 20)
        packet.putShort(10, ipChecksum.toShort())

        // TCP Header (20 bytes)
        packet.position(20)
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putInt((seq and 0xFFFFFFFFL).toInt())
        packet.putInt((ack and 0xFFFFFFFFL).toInt())
        packet.put(0x50.toByte()) // Offset 5 (20 bytes)
        packet.put(flags.toByte())
        packet.putShort(65535.toShort()) // Window
        packet.putShort(0.toShort()) // Placeholder
        packet.putShort(0.toShort()) // Urgent pointer

        if (payloadLen > 0) {
            packet.put(payload)
        }

        // TCP Checksum calculation with Pseudo-header
        val pseudoLen = 12 + tcpLen
        val pseudo = ByteBuffer.allocate(pseudoLen)
        pseudo.put(srcIp)
        pseudo.put(dstIp)
        pseudo.put(0.toByte())
        pseudo.put(6.toByte())
        pseudo.putShort(tcpLen.toShort())
        pseudo.put(packet.array(), 20, tcpLen)

        val tcpChecksum = computeIpChecksum(pseudo.array(), 0, pseudoLen)
        packet.putShort(36, tcpChecksum.toShort())

        return packet.array()
    }

    private fun buildUdpIpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val totalLen = 20 + 8 + payloadLen
        val packet = ByteBuffer.allocate(totalLen)

        // IPv4 Header (20 bytes)
        packet.put(0x45.toByte()) // Version 4, IHL 5 (20 bytes)
        packet.put(0x00.toByte()) // DSCP / ECN
        packet.putShort(totalLen.toShort()) // Total length
        packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort()) // ID
        packet.putShort(0x4000.toShort()) // Flags: Don't Fragment
        packet.put(64.toByte()) // TTL
        packet.put(17.toByte()) // Protocol: UDP (17)
        packet.putShort(0.toShort()) // Checksum placeholder
        packet.put(srcIp)
        packet.put(dstIp)

        // Calculate IPv4 Header checksum
        val ipChecksum = computeIpChecksum(packet.array(), 0, 20)
        packet.putShort(10, ipChecksum.toShort())

        // UDP Header (8 bytes)
        packet.position(20)
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putShort((8 + payloadLen).toShort())
        packet.putShort(0.toShort()) // Optional in IPv4

        // Payload
        packet.put(payload, 0, payloadLen)

        return packet.array()
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val high = (data[i].toInt() and 0xFF) shl 8
            val low = if (i + 1 < offset + length) data[i + 1].toInt() and 0xFF else 0
            sum += (high or low)
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv()) and 0xFFFF
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
