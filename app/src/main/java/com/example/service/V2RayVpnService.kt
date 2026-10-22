package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.V2RayApplication
import com.example.core.V2RayCoreManager
import com.example.model.AppBypassConfig
import com.example.model.AppLog
import com.example.model.ConnectionState
import com.example.model.ServerProfile
import com.example.model.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class V2RayVpnService : VpnService() {

    val ownerId: Int = ownerCounter.incrementAndGet()
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var coreManager: V2RayCoreManager? = null
    private var stateObserverJob: Job? = null
    private var statsObserverJob: Job? = null
    private var connectingWatchdogJob: Job? = null
    private var stoppingWatchdogJob: Job? = null
    private var hasStartedConnecting = false
    @Volatile
    private var isStopping = false

    private var packageChangeReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        activeOwnerId = ownerId
        instance = this
        isStopping = false
        hasStartedConnecting = false
        promoteToForeground("v2rayDP", "VPN service ready")
        coreManager = V2RayCoreManager(this)
        registerPackageChangeReceiver()
        observeCoreState()
    }

    private fun registerPackageChangeReceiver() {
        if (packageChangeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED) {
                    val app = V2RayApplication.get()
                    val changed = app.preferencesRepository.reconcileNewApps(packageManager)
                    if (changed) {
                        _reconnectNeeded.value = true
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        try {
            registerReceiver(receiver, filter)
            packageChangeReceiver = receiver
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to register dynamic package receiver: ${e.message}")
        }
    }

    private fun unregisterPackageChangeReceiver() {
        packageChangeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            packageChangeReceiver = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val isAutomated = intent?.getBooleanExtra(EXTRA_IS_AUTOMATED, false) ?: false

        if (action == ACTION_STOP) {
            val reason = intent?.getStringExtra(EXTRA_REASON) ?: "Stopped by user"
            if (!isAutomated) {
                val app = V2RayApplication.get()
                val netKey = app.automationManager.getCurrentNetworkKey()
                app.preferencesRepository.setManualOverride("Notification stop action", netKey)
                app.preferencesRepository.clearStoppedByPolicy()
            }
            stopVpn(reason)
            return START_NOT_STICKY
        }

        promoteToForeground("v2rayDP", "Connecting to proxy…")

        if (action == ACTION_START) {
            isStopping = false
            val profileId = intent?.getLongExtra(EXTRA_PROFILE_ID, -1L) ?: -1L
            startConnectingWatchdog()
            serviceScope.launch(Dispatchers.IO) {
                com.example.core.block.BlockEngine.getInstance(applicationContext).loadIfNeeded()
                val profile = resolveProfile(if (profileId > 0) profileId else null)

                if (profile != null) {
                    startVpnWithProfile(profile)
                } else {
                    val errorMsg = "No profiles configured"
                    cancelConnectingWatchdog()
                    publish(ownerId, ConnectionState.Error(errorMsg), "service_no_profiles")
                    logEvent("ERROR", errorMsg)
                    stopVpn(errorMsg)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startConnectingWatchdog() {
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = serviceScope.launch {
            delay(20_000L)
            val curr = _serviceState.value
            if (curr is ConnectionState.Connecting) {
                logEvent("ERROR", "Connecting watchdog timed out after 20s")
                val errState = ConnectionState.Error("Connection timed out after 20s")
                publish(ownerId, errState, "connecting_watchdog")
                stopVpn("Connecting watchdog timed out")
            }
        }
    }

    private fun cancelConnectingWatchdog() {
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = null
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

        // Reconcile launchable apps before building the tunnel
        prefs.reconcileNewApps(packageManager)
        val appBypassConfig = prefs.getAppBypassConfig()
        _reconnectNeeded.value = false

        logEvent("INFO", "Starting VPN with ${profile.protocol.uppercase()}: ${profile.name} (${profile.address}:${profile.port})")
        updateNotification("Connecting to ${profile.name}…", "Configuring tunnel…")

        val success = coreManager?.startCore(
            profile = profile,
            socksPort = settings.hotspotProxyPort,
            httpPort = settings.hotspotProxyPort + 1,
            primaryDns = prefs.primaryDns,
            secondaryDns = prefs.secondaryDns,
            bypassLan = prefs.isBypassLan,
            appBypassConfig = appBypassConfig
        ) ?: false

        if (success) {
            logEvent("INFO", "VPN connected successfully via ${profile.protocol.uppercase()} to ${profile.address}")
        } else {
            cancelConnectingWatchdog()
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
                    try {
                        setUnderlyingNetworks(arrayOf(network))
                        android.util.Log.d(TAG, "Re-set underlying network: $network")
                    } catch (_: Exception) {}
                }

                override fun onLost(network: android.net.Network) {
                    try {
                        val active = cm.activeNetwork
                        setUnderlyingNetworks(if (active != null) arrayOf(active) else null)
                        android.util.Log.d(TAG, "Underlying network changed/lost; new active: $active")
                    } catch (_: Exception) {}
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
                // 1. Ignore replayed pre-start value
                if (state is ConnectionState.Disconnected && !hasStartedConnecting) {
                    return@collect
                }

                when (state) {
                    is ConnectionState.Connecting -> {
                        hasStartedConnecting = true
                        publish(ownerId, state, "core")
                        updateNotification("Connecting to ${state.profileName}", state.step)
                    }
                    is ConnectionState.Connected -> {
                        hasStartedConnecting = true
                        cancelConnectingWatchdog()
                        registerUnderlyingNetworkListener()
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
                        publish(ownerId, state, "core")
                        updateNotification(
                            "Connected to ${state.profile.name}",
                            "SOCKS5 Proxy on :${state.localProxyPort}"
                        )
                    }
                    is ConnectionState.Stopping -> {
                        isStopping = true
                        publish(ownerId, state, "core")
                    }
                    is ConnectionState.Disconnected -> {
                        if (hasStartedConnecting) {
                            publish(ownerId, state, "core")
                            finishSession(state.reason)
                        }
                    }
                    is ConnectionState.Error -> {
                        publish(ownerId, state, "core")
                        finishSession(state.message)
                    }
                }
            }
        }

        statsObserverJob = serviceScope.launch {
            manager.trafficStats.collect { stats ->
                if (isStopping) return@collect
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

    private fun finishSession(reason: String) {
        isStopping = true
        cancelConnectingWatchdog()
        stoppingWatchdogJob?.cancel()
        stoppingWatchdogJob = null
        unregisterUnderlyingNetworkListener()
        unregisterPackageChangeReceiver()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        nm?.cancel(NOTIFICATION_ID + 1)
        stopSelf()
    }

    private fun updateNotification(title: String, text: String) {
        if (isStopping || instance !== this) return
        val currentState = _serviceState.value
        if (currentState !is ConnectionState.Connected && currentState !is ConnectionState.Connecting) return

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
            putExtra(EXTRA_IS_AUTOMATED, false)
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
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                stopVpn(reason)
            }
            return
        }

        isStopping = true
        publish(ownerId, ConnectionState.Stopping(reason), "service_stop")
        logEvent("INFO", "Stopping VPN: $reason")

        cancelConnectingWatchdog()
        unregisterUnderlyingNetworkListener()
        unregisterPackageChangeReceiver()

        stoppingWatchdogJob?.cancel()
        stoppingWatchdogJob = serviceScope.launch {
            delay(5000L)
            if (_serviceState.value is ConnectionState.Stopping) {
                android.util.Log.w(TAG, "Stopping watchdog timed out after 5s. Forcing Disconnected.")
                publish(ownerId, ConnectionState.Disconnected("Stop timeout"), "stopping_watchdog")
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
                val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                nm?.cancel(NOTIFICATION_ID)
                nm?.cancel(NOTIFICATION_ID + 1)
                stopSelf()
            }
        }

        try {
            com.example.core.block.BlockEngine.getInstance(applicationContext).flushCounters()
        } catch (_: Throwable) {}

        try {
            coreManager?.stopCore(reason)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error stopping core: ${e.message}")
        }

        publish(ownerId, ConnectionState.Disconnected(reason), "service_stop")

        stateObserverJob?.cancel()
        statsObserverJob?.cancel()
        stateObserverJob = null
        statsObserverJob = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
        notificationManager?.cancel(NOTIFICATION_ID + 1)

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
        isStopping = true
        cancelConnectingWatchdog()
        stoppingWatchdogJob?.cancel()
        stoppingWatchdogJob = null
        unregisterUnderlyingNetworkListener()
        unregisterPackageChangeReceiver()
        stateObserverJob?.cancel()
        statsObserverJob?.cancel()
        stateObserverJob = null
        statsObserverJob = null
        try {
            com.example.core.block.BlockEngine.getInstance(applicationContext).flushCounters()
        } catch (_: Throwable) {}
        try {
            coreManager?.stopCore("Service destroyed")
        } catch (_: Throwable) {}
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
        notificationManager?.cancel(NOTIFICATION_ID + 1)

        if (instance === this) {
            instance = null
        }

        if (_serviceState.value !is ConnectionState.Disconnected && ownerId == activeOwnerId) {
            publish(ownerId, ConnectionState.Disconnected("Service destroyed"), "service_destroy")
        }

        super.onDestroy()
    }

    override fun onRevoke() {
        logEvent("WARN", "VPN permission revoked by system or user")
        stopVpn("Permission revoked")
        super.onRevoke()
    }

    companion object {
        private const val TAG = "V2RayVpnService"
        private const val TAG_STATE = "VpnState"
        const val ACTION_START = "com.example.v2raydp.START"
        const val ACTION_STOP = "com.example.v2raydp.STOP"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_IS_AUTOMATED = "extra_is_automated"
        private const val NOTIFICATION_ID = 1001

        const val OWNER_ACTIVE = -1

        private val ownerCounter = AtomicInteger(0)
        @Volatile
        var activeOwnerId: Int = 0
            private set

        private val _serviceState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        val serviceState: StateFlow<ConnectionState> = _serviceState.asStateFlow()

        private val _serviceTrafficStats = MutableStateFlow(TrafficStats())
        val serviceTrafficStats: StateFlow<TrafficStats> = _serviceTrafficStats.asStateFlow()

        private val _reconnectNeeded = MutableStateFlow(false)
        val reconnectNeeded: StateFlow<Boolean> = _reconnectNeeded.asStateFlow()

        private val actionMutex = Mutex()
        private val companionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun publish(ownerId: Int, state: ConnectionState, source: String = "") {
            if (ownerId != OWNER_ACTIVE && ownerId != activeOwnerId) {
                android.util.Log.d(TAG_STATE, "Dropped write from owner=$ownerId (active=$activeOwnerId) -> $state source=$source")
                return
            }
            android.util.Log.d(TAG_STATE, "owner=$ownerId ${_serviceState.value} -> $state source=$source")
            _serviceState.value = state
        }

        fun setReconnectNeeded(needed: Boolean) {
            _reconnectNeeded.value = needed
        }

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

            return null
        }

        fun start(context: Context, profileId: Long? = null, isAutomated: Boolean = false) {
            companionScope.launch {
                actionMutex.withLock {
                    val currentState = serviceState.value
                    if (currentState is ConnectionState.Connected) {
                        if (profileId == null || profileId <= 0L || currentState.profile.id == profileId) {
                            android.util.Log.d(TAG, "VPN is already connected with requested profile. Ignoring start call.")
                            return@withLock
                        }
                    } else if (currentState is ConnectionState.Connecting) {
                        if (profileId == null || profileId <= 0L) {
                            android.util.Log.d(TAG, "VPN is already connecting. Ignoring start call.")
                            return@withLock
                        }
                    }

                    if (currentState is ConnectionState.Stopping) {
                        android.util.Log.d(TAG, "VPN is stopping. Waiting up to 3s for Disconnected...")
                        withTimeoutOrNull(3000L) {
                            serviceState.first { it is ConnectionState.Disconnected }
                        }
                    }

                    val profileName = try {
                        val profile = resolveProfile(if (profileId != null && profileId > 0L) profileId else null)
                        profile?.name ?: "Starting…"
                    } catch (_: Exception) {
                        "Starting…"
                    }

                    publish(
                        OWNER_ACTIVE,
                        ConnectionState.Connecting(profileName, "Starting service…"),
                        "ui_start"
                    )

                    val intent = Intent(context, V2RayVpnService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_IS_AUTOMATED, isAutomated)
                        if (profileId != null && profileId > 0L) {
                            putExtra(EXTRA_PROFILE_ID, profileId)
                        }
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to start VPN foreground service: ${e.message}")
                        val isFgNotAllowed = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            e is android.app.ForegroundServiceStartNotAllowedException) ||
                            e.javaClass.name.contains("ForegroundServiceStartNotAllowedException")
                        if (isFgNotAllowed) {
                            try {
                                val app = V2RayApplication.get()
                                app.preferencesRepository.backgroundStartBlocked = true
                                app.automationManager.logBackgroundStartBlocked("fgs_start")
                            } catch (_: Throwable) {}

                            var startedViaPendingIntent = false
                            try {
                                val pi = PendingIntent.getForegroundService(
                                    context,
                                    1002,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                pi.send()
                                startedViaPendingIntent = true
                            } catch (_: Exception) {}

                            if (!startedViaPendingIntent) {
                                promptUserToStartVpn(context, profileId)
                            }
                            return@withLock
                        }

                        val errState = ConnectionState.Error(e.message ?: "Failed to start service")
                        publish(OWNER_ACTIVE, errState, "service_start_error")
                        try {
                            val app = V2RayApplication.get()
                            app.database.appLogDao().insertLog(
                                AppLog(
                                    tag = "V2RayVpnService",
                                    message = "Failed to start service: ${e.message}",
                                    level = "ERROR",
                                    triggerReason = "start()"
                                )
                            )
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        fun promptUserToStartVpn(context: Context, profileId: Long?) {
            try {
                val startIntent = Intent(context, V2RayVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_IS_AUTOMATED, false)
                    if (profileId != null && profileId > 0L) {
                        putExtra(EXTRA_PROFILE_ID, profileId)
                    }
                }
                val pendingIntent = PendingIntent.getService(
                    context,
                    1003,
                    startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, V2RayApplication.NOTIFICATION_CHANNEL_VPN)
                    .setSmallIcon(R.drawable.ic_vpn_key)
                    .setContentTitle("Wi-Fi Connected: Tap to Connect VPN")
                    .setContentText("Tap to start your VPN tunnel")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                val nm = context.getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                nm?.notify(NOTIFICATION_ID + 1, notification)
            } catch (_: Exception) {}
        }

        fun stop(context: Context, reason: String = "User requested disconnect", isAutomated: Boolean = false) {
            companionScope.launch {
                actionMutex.withLock {
                    val inst = instance
                    if (inst != null) {
                        publish(OWNER_ACTIVE, ConnectionState.Stopping(reason), "ui_stop")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            inst.stopVpn(reason)
                        }
                    } else {
                        if (_serviceState.value !is ConnectionState.Disconnected) {
                            publish(OWNER_ACTIVE, ConnectionState.Disconnected(reason), "ui_stop")
                        }
                        val nm = context.getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                        nm?.cancel(NOTIFICATION_ID)
                        nm?.cancel(NOTIFICATION_ID + 1)
                    }
                }
            }
        }

        fun restart(context: Context, profileId: Long? = null, reason: String = "Restarting VPN") {
            companionScope.launch {
                stop(context, reason, isAutomated = false)
                withTimeoutOrNull(3000L) {
                    serviceState.first { it is ConnectionState.Disconnected }
                }
                delay(300L)
                start(context, profileId, isAutomated = false)
                _reconnectNeeded.value = false
            }
        }
    }
}
