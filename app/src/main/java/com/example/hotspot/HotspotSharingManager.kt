package com.example.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotSharingManager {

    /**
     * Inspects active network interfaces to find the Hotspot / AP tethering gateway IP address.
     * Typically on Android this is on interface 'ap0', 'wlan1', 'swlan0', or 'rndis0' with IP 192.168.43.1 or 192.168.49.1.
     */
    fun getHotspotGatewayIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val name = nif.name.lowercase()
                if (name.contains("ap") || name.contains("wlan1") || name.contains("rndis") || name.contains("tether")) {
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "192.168.43.1"
                        }
                    }
                }
            }

            // Fallback: check any non-loopback IPv4 address in private range
            val allInterfaces = NetworkInterface.getNetworkInterfaces()
            while (allInterfaces.hasMoreElements()) {
                val nif = allInterfaces.nextElement()
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.startsWith("192.168.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return "192.168.43.1"
    }

    /**
     * Checks if the device has root access available.
     */
    suspend fun checkRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor() == 0 && !line.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Executes root iptables transparent redirection if root access is granted.
     */
    suspend fun applyRootTetheringRules(port: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val commands = arrayOf(
                "iptables -t nat -F PREROUTING",
                "iptables -t nat -A PREROUTING -i ap0 -p tcp -j REDIRECT --to-ports $port",
                "iptables -t nat -A PREROUTING -i wlan1 -p tcp -j REDIRECT --to-ports $port",
                "iptables -t nat -A PREROUTING -i rndis0 -p tcp -j REDIRECT --to-ports $port"
            )

            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            for (cmd in commands) {
                os.write("$cmd\n".toByteArray())
            }
            os.write("exit\n".toByteArray())
            os.flush()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                "Root transparent iptables rules applied successfully for port $port"
            } else {
                throw IllegalStateException("Root command returned code $exitCode")
            }
        }
    }

    /**
     * Flushes root iptables tethering redirection rules.
     */
    suspend fun clearRootTetheringRules(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("iptables -t nat -F PREROUTING\nexit\n".toByteArray())
            os.flush()
            process.waitFor()
            "Root iptables rules cleared"
        }
    }
}
