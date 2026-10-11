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
                    val app = V2RayApplication.get()
                    val profile = if (profileId > 0) {
                        app.database.serverProfileDao().getProfileById(profileId)
                    } else {
                        app.database.serverProfileDao().getActiveProfile()
                    }

                    if (profile != null) {
                        startVpnWithProfile(profile)
                    } else {
                        val all = app.database.serverProfileDao().getAllProfiles()
                        if (all.isNotEmpty()) {
                            val first = all.first()
                            app.database.serverProfileDao().setSelected(first.id)
                            startVpnWithProfile(first)
                        } else {
                            _serviceState.value = ConnectionState.Error("No server profiles available")
                            logEvent("ERROR", "No server profiles found to connect")
                            stopVpn("No profiles found")
                        }
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

    private fun observeCoreState() {
        val manager = coreManager ?: return

        stateObserverJob = serviceScope.launch {
            manager.connectionState.collect { state ->
                _serviceState.value = state
                when (state) {
                    is ConnectionState.Connected -> {
                        hasStartedConnecting = true
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
        const val ACTION_START = "com.example.v2raydp.START"
        const val ACTION_STOP = "com.example.v2raydp.STOP"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_REASON = "extra_reason"
        private const val NOTIFICATION_ID = 1001

        private val _serviceState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        val serviceState: StateFlow<ConnectionState> = _serviceState.asStateFlow()

        private val _serviceTrafficStats = MutableStateFlow(TrafficStats())
        val serviceTrafficStats: StateFlow<TrafficStats> = _serviceTrafficStats.asStateFlow()

        var instance: V2RayVpnService? = null
            private set

        fun start(context: Context, profileId: Long? = null) {
            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = ACTION_START
                if (profileId != null) {
                    putExtra(EXTRA_PROFILE_ID, profileId)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, reason: String = "User requested disconnect") {
            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_REASON, reason)
            }
            // Use startService (or stopSelf on active instance)
            if (instance != null) {
                instance?.stopVpn(reason)
            } else {
                context.startService(intent)
            }
        }
    }
}
