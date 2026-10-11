package com.example.core

import android.net.VpnService
import com.example.core.proxy.LocalProxyServer
import com.example.core.vmess.VmessProtocolEngine
import com.example.core.vpn.VpnTunnelManager
import com.example.model.ConnectionState
import com.example.model.ServerProfile
import com.example.model.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket

class V2RayCoreManager(
    private val vpnService: VpnService
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var statsJob: Job? = null

    private var localProxyServer: LocalProxyServer? = null
    private var vmessEngine: VmessProtocolEngine? = null
    private var tunnelManager: VpnTunnelManager? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private var activeProfile: ServerProfile? = null
    private var connectionStartTime: Long = 0

    @Synchronized
    fun startCore(
        profile: ServerProfile,
        socksPort: Int = 10808,
        httpPort: Int = 10809,
        primaryDns: String = "1.1.1.1",
        secondaryDns: String = "8.8.8.8",
        bypassLan: Boolean = true
    ): Boolean {
        if (_connectionState.value.isRunning) {
            stopCore("Restarting with new profile")
        }

        _connectionState.value = ConnectionState.Connecting(profile.name, "Initializing VMess protocol engine…")
        activeProfile = profile
        connectionStartTime = System.currentTimeMillis()

        try {
            // 1. Initialize VMess Protocol Engine with socket protection
            val engine = VmessProtocolEngine(profile) { socket: Socket ->
                vpnService.protect(socket)
            }
            vmessEngine = engine

            // 2. Start Local SOCKS5 / HTTP Proxy Server (bound to 0.0.0.0 for localhost + hotspot clients)
            _connectionState.value = ConnectionState.Connecting(profile.name, "Starting local proxy servers…")
            val proxyServer = LocalProxyServer(engine, socksPort, httpPort)
            proxyServer.start()
            localProxyServer = proxyServer

            // 3. Establish VPN Tunnel (TUN interface)
            _connectionState.value = ConnectionState.Connecting(profile.name, "Configuring Android VPN tunnel…")
            val tunnel = VpnTunnelManager(vpnService)
            val success = tunnel.establishTunnel(profile, primaryDns, secondaryDns, bypassLan)
            if (!success) {
                stopCore("Failed to establish TUN interface")
                _connectionState.value = ConnectionState.Error("VPN permission required or TUN creation failed")
                return false
            }
            tunnelManager = tunnel

            _connectionState.value = ConnectionState.Connected(profile, connectionStartTime, socksPort)
            startStatsMonitoring()
            return true
        } catch (e: Exception) {
            stopCore("Initialization error: ${e.message}")
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown core error")
            return false
        }
    }

    private fun startStatsMonitoring() {
        statsJob?.cancel()
        statsJob = coroutineScope.launch {
            var lastTx: Long = 0
            var lastRx: Long = 0
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                delay(1000)

                val engineTx = vmessEngine?.totalTxBytes ?: 0L
                val engineRx = vmessEngine?.totalRxBytes ?: 0L
                val tunTx = tunnelManager?.txBytes ?: 0L
                val tunRx = tunnelManager?.rxBytes ?: 0L

                val totalTx = engineTx + tunTx
                val totalRx = engineRx + tunRx

                val speedTx = if (lastTx == 0L) 0L else (totalTx - lastTx).coerceAtLeast(0)
                val speedRx = if (lastRx == 0L) 0L else (totalRx - lastRx).coerceAtLeast(0)

                lastTx = totalTx
                lastRx = totalRx

                val duration = (System.currentTimeMillis() - connectionStartTime) / 1000

                _trafficStats.value = TrafficStats(
                    uploadSpeedBps = speedTx,
                    downloadSpeedBps = speedRx,
                    sessionTxBytes = totalTx,
                    sessionRxBytes = totalRx,
                    connectedDurationSeconds = duration
                )
            }
        }
    }

    @Synchronized
    fun stopCore(reason: String = "User requested disconnect") {
        statsJob?.cancel()
        statsJob = null

        tunnelManager?.stopTunnel()
        tunnelManager = null

        localProxyServer?.stop()
        localProxyServer = null

        vmessEngine = null
        activeProfile = null

        _trafficStats.value = TrafficStats()
        _connectionState.value = ConnectionState.Disconnected(reason)
    }
}
