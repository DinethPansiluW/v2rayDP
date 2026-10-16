package com.example.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.model.ServerProfile
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the Android VpnService TUN interface and bridges IP traffic to
 * the local SOCKS5 proxy via the native hev-socks5-tunnel (tun2socks) engine.
 */
class VpnTunnelManager(
    private val vpnService: VpnService
) {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)

    val txBytes: Long
        get() {
            return try {
                if (isRunning.get() && hev.htproxy.TProxyService.TProxyIsRunning()) {
                    val stats = hev.htproxy.TProxyService.TProxyGetStats()
                    if (stats.size >= 4) stats[1] else 0L
                } else 0L
            } catch (_: Throwable) {
                0L
            }
        }

    val rxBytes: Long
        get() {
            return try {
                if (isRunning.get() && hev.htproxy.TProxyService.TProxyIsRunning()) {
                    val stats = hev.htproxy.TProxyService.TProxyGetStats()
                    if (stats.size >= 4) stats[3] else 0L
                } else 0L
            } catch (_: Throwable) {
                0L
            }
        }

    fun establishTunnel(
        profile: ServerProfile,
        socksPort: Int = 10808,
        primaryDns: String = "1.1.1.1",
        secondaryDns: String = "8.8.8.8",
        bypassLan: Boolean = true
    ): Boolean {
        try {
            val builder = vpnService.Builder().apply {
                setSession("v2rayDP: ${profile.name}")
                setMtu(1500)

                // Virtual TUN interface address
                addAddress("172.19.0.1", 30)

                // IPv6 interface address & default route to prevent IPv6 traffic leakage
                try {
                    addAddress("fd00:172:19::1", 126)
                    addRoute("::", 0)
                } catch (e: Exception) {
                    Log.w(TAG, "IPv6 address/route configuration note: ${e.message}")
                }

                // Prevent VPN routing loop by excluding the app itself
                try {
                    addDisallowedApplication(vpnService.packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication warning: ${e.message}")
                }

                // Routing & LAN bypass configuration
                if (bypassLan) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ (API 33+): route default traffic and exclude private LAN ranges natively
                        addRoute("0.0.0.0", 0)
                        try {
                            excludeRoute(android.net.IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
                            excludeRoute(android.net.IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                            excludeRoute(android.net.IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                            excludeRoute(android.net.IpPrefix(InetAddress.getByName("169.254.0.0"), 16))
                            excludeRoute(android.net.IpPrefix(InetAddress.getByName("127.0.0.0"), 8))
                            excludeRoute(android.net.IpPrefix(InetAddress.getByName("224.0.0.0"), 4))
                        } catch (e: Exception) {
                            Log.w(TAG, "excludeRoute failed: ${e.message}")
                        }
                    } else {
                        // Below API 33: build route list as 0.0.0.0/0 minus private ranges (10/8, 172.16/12, 192.168/16, etc.)
                        for ((subnet, prefix) in BYPASS_LAN_IPV4_SUBNETS) {
                            try {
                                addRoute(subnet, prefix)
                            } catch (e: Exception) {
                                Log.w(TAG, "addRoute $subnet/$prefix warning: ${e.message}")
                            }
                        }
                    }
                } else {
                    addRoute("0.0.0.0", 0)
                }

                // Route mapdns fake-IP subnet into TUN
                try {
                    addRoute("240.0.0.0", 4)
                } catch (_: Exception) {}

                // DNS Configuration: Set to hev-socks5-tunnel mapdns address (172.19.0.2)
                addDnsServer("172.19.0.2")
                if (primaryDns.isNotBlank() && primaryDns != "172.19.0.2") {
                    try { addDnsServer(primaryDns.trim()) } catch (_: Exception) {}
                }

                // Associate underlying active network so Android inherits validated network status
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    try {
                        val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        val activeNet = cm?.activeNetwork
                        if (activeNet != null) {
                            vpnService.setUnderlyingNetworks(arrayOf(activeNet))
                        } else {
                            vpnService.setUnderlyingNetworks(null)
                        }
                    } catch (_: Exception) {}
                }

                // Follow hev-socks5-tunnel / sockstun standard non-blocking file descriptor setup
                setBlocking(false)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Builder.establish() returned null - VPN permission denied or user cancelled")
                return false
            }

            val fd = vpnInterface!!.fd
            Log.d(TAG, "TUN established (fd: $fd)")

            // Generate hev-socks5-tunnel YAML configuration
            val configFile = File(vpnService.cacheDir, "tun2socks.yml")
            val configContent = """
                misc:
                  log-level: warn
                  task-stack-size: 81920
                tunnel:
                  mtu: 1500
                  ipv4: 172.19.0.1
                  ipv6: 'fd00:172:19::1'
                socks5:
                  port: $socksPort
                  address: '127.0.0.1'
                  udp: 'tcp'
                mapdns:
                  address: 172.19.0.2
                  port: 53
                  network: 240.0.0.0
                  netmask: 240.0.0.0
                  cache-size: 10000
            """.trimIndent()
            configFile.writeText(configContent)

            // Start native tun2socks service
            val started = try {
                hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to call TProxyStartService (JNI): ${e.message}", e)
                false
            }
            Log.d(TAG, "tunnel started: $started")

            if (!started) {
                Log.e(TAG, "TProxyStartService returned false - failed to initialize tun2socks")
                stopTunnel()
                return false
            }

            isRunning.set(true)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN tunnel: ${e.message}", e)
            stopTunnel()
            return false
        }
    }

    fun stopTunnel() {
        if (!isRunning.getAndSet(false) && vpnInterface == null) return
        Log.d(TAG, "Stopping tun2socks tunnel and closing TUN interface")

        // Stop hev-socks5-tunnel first before closing the file descriptor
        try {
            hev.htproxy.TProxyService.TProxyStopService()
        } catch (e: Throwable) {
            Log.w(TAG, "TProxyStopService error: ${e.message}")
        }

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
    }

    companion object {
        private const val TAG = "VpnTunnelManager"

        // IPv4 subnets spanning 0.0.0.0/0 excluding private LAN ranges (10/8, 172.16/12, 192.168/16, loopback, link-local, multicast)
        private val BYPASS_LAN_IPV4_SUBNETS = listOf(
            Pair("1.0.0.0", 8),
            Pair("2.0.0.0", 7),
            Pair("4.0.0.0", 6),
            Pair("8.0.0.0", 7),
            Pair("11.0.0.0", 8),
            Pair("12.0.0.0", 6),
            Pair("16.0.0.0", 4),
            Pair("32.0.0.0", 3),
            Pair("64.0.0.0", 3),
            Pair("96.0.0.0", 4),
            Pair("112.0.0.0", 5),
            Pair("120.0.0.0", 6),
            Pair("124.0.0.0", 7),
            Pair("126.0.0.0", 8),
            Pair("128.0.0.0", 3),
            Pair("160.0.0.0", 5),
            Pair("168.0.0.0", 8),
            Pair("169.0.0.0", 9),
            Pair("169.128.0.0", 10),
            Pair("169.192.0.0", 11),
            Pair("169.224.0.0", 12),
            Pair("169.240.0.0", 13),
            Pair("169.248.0.0", 14),
            Pair("169.252.0.0", 15),
            Pair("169.255.0.0", 16),
            Pair("170.0.0.0", 7),
            Pair("172.0.0.0", 12),
            Pair("172.32.0.0", 11),
            Pair("172.64.0.0", 10),
            Pair("172.128.0.0", 9),
            Pair("173.0.0.0", 8),
            Pair("174.0.0.0", 7),
            Pair("176.0.0.0", 4),
            Pair("192.0.0.0", 9),
            Pair("192.128.0.0", 11),
            Pair("192.160.0.0", 13),
            Pair("192.169.0.0", 16),
            Pair("192.170.0.0", 15),
            Pair("192.172.0.0", 14),
            Pair("192.176.0.0", 12),
            Pair("192.192.0.0", 10),
            Pair("193.0.0.0", 8),
            Pair("194.0.0.0", 7),
            Pair("196.0.0.0", 6),
            Pair("200.0.0.0", 5),
            Pair("208.0.0.0", 4),
            Pair("240.0.0.0", 4)
        )
    }
}
