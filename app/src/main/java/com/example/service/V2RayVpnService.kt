package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.V2RayApplication
import com.example.core.V2RayCoreManager
import com.example.model.AppLog
import com.example.model.ConnectionState
import com.example.model.ServerProfile
import com.example.model.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class V2RayVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var coreManager: V2RayCoreManager? = null
    private var stateObserverJob: Job? = null
    private var statsObserverJob: Job? = null
    private var hasStartedConnecting = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Ensure immediate foreground promotion in onCreate to prevent ForegroundServiceDidNotStartInTimeException
        promoteToForeground("v2rayDP", "VPN service ready")
        coreManager = V2RayCoreManager(this)
        observeCoreState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        // CRITICAL FIX: To prevent ForegroundServiceDidNotStartInTimeException,
        // startForeground() MUST be invoked synchronously on the main thread
        // immediately upon entering onStartCommand().
        promoteToForeground("v2rayDP", if (action == ACTION_STOP) "Stopping VPN service…" else "Connecting to proxy…")

        when (action) {
            ACTION_STOP -> {
                val reason = intent?.getStringExtra(EXTRA_REASON) ?: "Stopped by user"
                stopVpn(reason)
            }
            ACTION_START -> {
                hasStartedConnecting = true
                val profileId = intent?.getLongExtra(EXTRA_PROFILE_ID, -1L) ?: -1L
                serviceScope.launch(Dispatchers.IO) {
                    val profile = resolveProfile(if (profileId > 0) profileId else null)

                    if (profile != null) {
                        startVpnWithProfile(profile)
                    } else {
                        val errorMsg = "No profiles configured"
                        _serviceState.value = ConnectionState.Error(errorMsg)
                        V2RayCoreManager.setGlobalConnectionState(ConnectionState.Error(errorMsg))
                        logEvent("ERROR", errorMsg)
                        stopVpn(errorMsg)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun startVpnWithProfile(profile: ServerProfile) {
        val app = V2RayApplication.get()
        val prefs = app.preferencesRepository
        val settings = prefs.getAutomationSettings()

        logEvent("INFO", "Starting VPN with ${profile.protocol.uppercase()}: ${profile.name} (${profile.address}:${profile.port})")

        // Update notification with active profile
        updateNotification("Connecting to ${profile.name}…", "Configuring tunnel…")

        val success = coreManager?.startCore(
            profile = profile,
            socksPort = settings.hotspotProxyPort,
            httpPort = settings.hotspotProxyPort + 1,
            primaryDns = prefs.primaryDns,
            secondaryDns = prefs.secondaryDns,
            bypassLan = prefs.isBypassLan
        ) ?: false

        if (success) {
            logEvent("INFO", "VPN connected successfully via ${profile.protocol.uppercase()} to ${profile.address}")
        } else {
            logEvent("ERROR", "VPN Tunnel establishment failed")
            stopVpn("Tunnel establishment failed")
        }
    }

    private var defaultNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerUnderlyingNetworkListener() {
        if (defaultNetworkCallback != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try {
                            setUnderlyingNetworks(arrayOf(network))
                            android.util.Log.d(TAG, "Re-set underlying network: $network")
                        } catch (_: Exception) {}
                    }
                }

                override fun onLost(network: android.net.Network) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try {
                            val active = cm.activeNetwork
                            setUnderlyingNetworks(if (active != null) arrayOf(active) else null)
                            android.util.Log.d(TAG, "Underlying network changed/lost; new active: $active")
                        } catch (_: Exception) {}
                    }
                }
            }
            defaultNetworkCallback = callback
            try {
                cm.registerDefaultNetworkCallback(callback)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "registerDefaultNetworkCallback error: ${e.message}")
            }
        }
    }

    private fun unregisterUnderlyingNetworkListener() {
        defaultNetworkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            defaultNetworkCallback = null
        }
    }

    private fun observeCoreState() {
        val manager = coreManager ?: return

        stateObserverJob = serviceScope.launch {
            manager.connectionState.collect { state ->
                _serviceState.value = state
                when (state) {
                    is ConnectionState.Connected -> {
                        hasStartedConnecting = true
                        registerUnderlyingNetworkListener()
                        // Link VPN to system underlying default network (Wi-Fi/Cellular)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            try {
                                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                                val activeNet = cm?.activeNetwork
                                if (activeNet != null) {
                                    setUnderlyingNetworks(arrayOf(activeNet))
                                } else {
                                    setUnderlyingNetworks(null)
                                }
                            } catch (_: Exception) {}
                        }
                        updateNotification(
                            "Connected to ${state.profile.name}",
                            "SOCKS5 Proxy on :${state.localProxyPort}"
                        )
                    }
                    is ConnectionState.Connecting -> {
                        hasStartedConnecting = true
                        updateNotification("Connecting to ${state.profileName}", state.step)
                    }
                    is ConnectionState.Disconnected -> {
                        unregisterUnderlyingNetworkListener()
                        // Only stop service if it had actually started connecting
                        if (hasStartedConnecting) {
                            try {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                            } catch (_: Exception) {}
                            stopSelf()
                        }
                    }
                    is ConnectionState.Error -> {
                        if (hasStartedConnecting) {
                            try {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                            } catch (_: Exception) {}
                            stopSelf()
                        }
                    }
                }
            }
        }

        statsObserverJob = serviceScope.launch {
            manager.trafficStats.collect { stats ->
                _serviceTrafficStats.value = stats
                val currentState = _serviceState.value
                if (currentState is ConnectionState.Connected) {
                    updateNotification(
                        "Connected to ${currentState.profile.name}",
                        "⬇ ${stats.formattedDownloadSpeed}  ⬆ ${stats.formattedUploadSpeed} • ${stats.formattedDuration}"
                    )
                }
            }
        }
    }

    private fun updateNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, V2RayVpnService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_REASON, "Stopped from notification")
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, V2RayApplication.NOTIFICATION_CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_vpn_key, getString(R.string.stop_service), stopPendingIntent)
            .build()
    }

    fun stopVpn(reason: String = "User requested stop") {
        logEvent("INFO", "Stopping VPN: $reason")
        unregisterUnderlyingNetworkListener()
        _serviceState.value = ConnectionState.Disconnected(reason)
        coreManager?.stopCore(reason)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun logEvent(level: String, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            val app = V2RayApplication.get()
            app.database.appLogDao().insertLog(
                AppLog(
                    tag = "V2RayVpnService",
                    message = message,
                    level = level,
                    triggerReason = "Service Lifecycle"
                )
            )
        }
    }

    override fun onDestroy() {
        unregisterUnderlyingNetworkListener()
        stateObserverJob?.cancel()
        statsObserverJob?.cancel()
        coreManager?.stopCore("Service destroyed")
        instance = null
        _serviceState.value = ConnectionState.Disconnected("Service destroyed")
        super.onDestroy()
    }

    override fun onRevoke() {
        logEvent("WARN", "VPN permission revoked by system or user")
        stopVpn("Permission revoked")
        super.onRevoke()
    }

    companion object {
        private const val TAG = "V2RayVpnService"
        const val ACTION_START = "com.example.v2raydp.START"
        const val ACTION_STOP = "com.example.v2raydp.STOP"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_IS_AUTOMATED = "extra_is_automated"
        private const val NOTIFICATION_ID = 1001

        private val _serviceState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        val serviceState: StateFlow<ConnectionState> = _serviceState.asStateFlow()

        private val _serviceTrafficStats = MutableStateFlow(TrafficStats())
        val serviceTrafficStats: StateFlow<TrafficStats> = _serviceTrafficStats.asStateFlow()

        var instance: V2RayVpnService? = null
            private set

        suspend fun resolveProfile(targetProfileId: Long?): ServerProfile? {
            val app = V2RayApplication.get()
            val dao = app.database.serverProfileDao()
            val settings = app.preferencesRepository.getAutomationSettings()

            // 1. Explicit target ID if specified and valid
            if (targetProfileId != null && targetProfileId > 0L) {
                val target = dao.getProfileById(targetProfileId)
                if (target != null) return target
            }

            // 2. defaultProfileId from settings (if valid and exists)
            val defaultId = settings.defaultProfileId
            if (defaultId != null && defaultId > 0L) {
                val def = dao.getProfileById(defaultId)
                if (def != null) return def
            }

            // 3. Current active/selected profile in Room
            val active = dao.getActiveProfile()
            if (active != null) return active

            // 4. First profile in profile list
            val all = dao.getAllProfiles()
            if (all.isNotEmpty()) {
                val first = all.first()
                dao.setSelected(first.id)
                return first
            }

            // 5. Empty
            return null
        }

        fun start(context: Context, profileId: Long? = null, isAutomated: Boolean = false) {
            val currentState = V2RayCoreManager.currentConnectionState.value
            if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
                android.util.Log.d(TAG, "VPN is already running or connecting. Ignoring start call.")
                return
            }

            _serviceState.value = ConnectionState.Connecting("v2rayDP", "Connecting…")
            V2RayCoreManager.setGlobalConnectionState(_serviceState.value)

            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_IS_AUTOMATED, isAutomated)
                if (profileId != null && profileId > 0L) {
                    putExtra(EXTRA_PROFILE_ID, profileId)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, reason: String = "User requested disconnect", isAutomated: Boolean = false) {
            val currentState = V2RayCoreManager.currentConnectionState.value
            if (currentState is ConnectionState.Disconnected || currentState is ConnectionState.Error) {
                android.util.Log.d(TAG, "VPN is already disconnected or in error. Ignoring stop call.")
                return
            }

            _serviceState.value = ConnectionState.Disconnected(reason)
            V2RayCoreManager.setGlobalConnectionState(_serviceState.value)

            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_IS_AUTOMATED, isAutomated)
            }
            if (instance != null) {
                instance?.stopVpn(reason)
            } else {
                context.startService(intent)
            }
        }
    }
}
