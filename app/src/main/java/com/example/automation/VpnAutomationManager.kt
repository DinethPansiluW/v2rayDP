package com.example.automation

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.V2RayApplication
import com.example.core.V2RayCoreManager
import com.example.model.AppLog
import com.example.model.AutomationSettings
import com.example.model.ConnectionState
import com.example.model.WifiLeavePolicy
import com.example.receiver.NetworkChangeReceiver
import com.example.service.V2RayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VpnAutomationManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val actionMutex = Mutex()

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var inProcessCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null

    // Channel/Flow to funnel all events into a single debounced evaluation stream
    private val eventFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _isWifiConnected = MutableStateFlow(false)
    val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    private val _isWifiRadioEnabled = MutableStateFlow(true)
    val isWifiRadioEnabled: StateFlow<Boolean> = _isWifiRadioEnabled.asStateFlow()

    private val _isCellularConnected = MutableStateFlow(false)
    val isCellularConnected: StateFlow<Boolean> = _isCellularConnected.asStateFlow()

    private val _lastAutomationStatus = MutableStateFlow("Initialized and monitoring")
    val lastAutomationStatus: StateFlow<String> = _lastAutomationStatus.asStateFlow()

    // Handover grace period job
    private var handoverGraceJob: Job? = null

    // User paused flag until physical network changes
    private var userPausedUntilNetworkChange = false
    private var pausedNetworkType: String? = null
    private var pausedSsid: String? = null

    private val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    fun initialize() {
        _isWifiRadioEnabled.value = wifiManager?.isWifiEnabled ?: true

        // 1. Start event debounce collector
        scope.launch {
            eventFlow
                .debounce(DEBOUNCE_MS)
                .collect { source ->
                    actionMutex.withLock {
                        evaluateNetworkStateInternal(source)
                    }
                }
        }

        // 2. Register callbacks & receivers
        registerMonitoring()

        // 3. Initial evaluation
        notifyNetworkEvent("init")
    }

    private fun registerMonitoring() {
        // API 26+: Register system PendingIntent network callback (survives process death)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && connectivityManager != null) {
            try {
                val intent = Intent(context, NetworkChangeReceiver::class.java).apply {
                    action = NetworkChangeReceiver.ACTION_CONNECTIVITY_CALLBACK
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    NetworkChangeReceiver.REQUEST_CODE_NETWORK_CALLBACK,
                    intent,
                    flags
                )
                connectivityManager.registerNetworkCallback(networkRequest, pendingIntent)
            } catch (e: Exception) {
                logAutoEvent("WARN", "PendingIntent network callback registration: ${e.message}")
            }
        }

        // API 24-25: Start foreground Automation monitor service
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                val monitorIntent = Intent(context, AutomationMonitorService::class.java)
                context.startService(monitorIntent)
            } catch (e: Exception) {
                logAutoEvent("WARN", "AutomationMonitorService start: ${e.message}")
            }
        }

        // In-process network callback for fast evaluation while process is alive
        if (connectivityManager != null && inProcessCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    notifyNetworkEvent("cb_onAvailable")
                }

                override fun onLost(network: Network) {
                    notifyNetworkEvent("cb_onLost")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    notifyNetworkEvent("cb_onCapabilitiesChanged")
                }
            }
            inProcessCallback = cb
            try {
                connectivityManager.registerNetworkCallback(networkRequest, cb)
            } catch (_: Exception) {}
        }

        // Dynamic receiver for Wi-Fi radio toggle only (RECEIVER_NOT_EXPORTED)
        registerDynamicWifiReceiver()
    }

    private fun registerDynamicWifiReceiver() {
        if (wifiStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    onWifiStateChanged(state)
                }
            }
        }
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            wifiStateReceiver = receiver
        } catch (e: Exception) {
            logAutoEvent("WARN", "Failed to register wifi receiver: ${e.message}")
        }
    }

    fun onBootCompleted() {
        val app = V2RayApplication.get()
        val settings = app.preferencesRepository.getAutomationSettings()
        if (settings.isAutomationGloballyEnabled) {
            registerMonitoring()
            notifyNetworkEvent("boot_completed")
        }
    }

    fun notifyNetworkEvent(source: String) {
        eventFlow.tryEmit(source)
    }

    fun forceEvaluateNow() {
        notifyNetworkEvent("force_evaluate")
    }

    fun onUserExplicitDisconnect() {
        userPausedUntilNetworkChange = true
        val status = checkCurrentPhysicalNetworks()
        if (status.hasWifi) {
            pausedNetworkType = "WIFI"
            pausedSsid = cleanSsidString(detectCurrentWifiSsid(status.wifiCaps))
        } else if (status.hasCellular) {
            pausedNetworkType = "CELLULAR"
            pausedSsid = null
        } else {
            pausedNetworkType = "NONE"
            pausedSsid = null
        }
        _lastAutomationStatus.value = "Paused by user disconnect until network changes"
        logAutoEvent("INFO", "User manually disconnected. Automation paused until physical network switches.", "User Manual Disconnect")
    }

    fun onUserExplicitConnect() {
        userPausedUntilNetworkChange = false
        pausedNetworkType = null
        pausedSsid = null
    }

    fun onWifiStateChanged(wifiState: Int) {
        scope.launch {
            val app = V2RayApplication.get()
            val settings = app.preferencesRepository.getAutomationSettings()

            if (wifiState == WifiManager.WIFI_STATE_DISABLED || wifiState == WifiManager.WIFI_STATE_DISABLING) {
                _isWifiRadioEnabled.value = false
                _isWifiConnected.value = false
                _currentSsid.value = null

                if (settings.isAutomationGloballyEnabled && settings.autoStopOnWifiOff) {
                    val coreState = V2RayCoreManager.currentConnectionState.value
                    if (coreState.isRunning) {
                        _lastAutomationStatus.value = "Wi-Fi turned OFF -> Auto-stopping VPN"
                        logAutoEvent("AUTO", "Wi-Fi radio switched OFF. Auto-stopping VPN service.", "Wi-Fi disabled by user")
                        V2RayVpnService.stop(context, "Wi-Fi disabled by user", isAutomated = true)
                    }
                }
            } else if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                _isWifiRadioEnabled.value = true
                notifyNetworkEvent("wifi_radio_on")
            }
        }
    }

    suspend fun evaluateNetworkState() {
        notifyNetworkEvent("direct_evaluate")
    }

    data class PhysicalNetworkStatus(
        val hasWifi: Boolean,
        val wifiCaps: NetworkCapabilities?,
        val hasCellular: Boolean
    )

    private fun checkCurrentPhysicalNetworks(): PhysicalNetworkStatus {
        val allNetworks = connectivityManager?.allNetworks ?: emptyArray()
        var hasWifi = false
        var wifiCaps: NetworkCapabilities? = null
        var hasCellular = false

        for (net in allNetworks) {
            val caps = connectivityManager?.getNetworkCapabilities(net) ?: continue
            // Exclude TRANSPORT_VPN to use only physical networks
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue
            }
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = !REQUIRE_VALIDATED_INTERNET || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && hasInternet && isValidated) {
                hasWifi = true
                wifiCaps = caps
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && hasInternet && isValidated) {
                hasCellular = true
            }
        }
        return PhysicalNetworkStatus(hasWifi, wifiCaps, hasCellular)
    }

    private suspend fun evaluateNetworkStateInternal(source: String) {
        val app = V2RayApplication.get()
        val settings = app.preferencesRepository.getAutomationSettings()

        if (!settings.isAutomationGloballyEnabled) {
            _lastAutomationStatus.value = "Automation is globally disabled"
            return
        }

        if (!settings.backgroundRunEnabled && source != "force_evaluate") {
            _lastAutomationStatus.value = "Background Run is disabled (Automation paused)"
            return
        }

        if (settings.manualOverrideSuppressed && source != "force_evaluate") {
            _lastAutomationStatus.value = "Suppressed by Manual Override (${settings.manualOverrideReason})"
            return
        }

        // State drift / feedback loop rule:
        // Do not evaluate while core is in CONNECTING state. Ignore network events until it settles.
        val coreState = V2RayCoreManager.currentConnectionState.value
        if (coreState is ConnectionState.Connecting) {
            return
        }

        val (hasWifi, wifiCaps, hasCellular) = checkCurrentPhysicalNetworks()
        _isWifiConnected.value = hasWifi
        _isCellularConnected.value = hasCellular
        _isWifiRadioEnabled.value = wifiManager?.isWifiEnabled ?: hasWifi

        // Check user paused state: unpause ONLY when physical network switches
        if (userPausedUntilNetworkChange) {
            val currentType = when {
                hasWifi -> "WIFI"
                hasCellular -> "CELLULAR"
                else -> "NONE"
            }
            val currentDetectedSsid = if (hasWifi) cleanSsidString(detectCurrentWifiSsid(wifiCaps)) else null
            val hasSwitched = when {
                currentType != pausedNetworkType -> true
                currentType == "WIFI" && currentDetectedSsid != null && pausedSsid != null &&
                        !currentDetectedSsid.equals(pausedSsid, ignoreCase = true) -> true
                else -> false
            }

            if (hasSwitched) {
                userPausedUntilNetworkChange = false
                pausedNetworkType = null
                pausedSsid = null
                logAutoEvent("AUTO", "Physical network switched ($pausedNetworkType -> $currentType). Automation unpaused.", "Network Switched")
            } else {
                _lastAutomationStatus.value = "Paused: waiting for physical network change"
                return
            }
        }

        val isVpnRunning = coreState.isRunning

        // 1. Handover Grace Period & Disconnect Check:
        if (hasWifi || hasCellular) {
            // Cancel any pending handover stop if network returned
            if (handoverGraceJob?.isActive == true) {
                handoverGraceJob?.cancel()
                handoverGraceJob = null
                logAutoEvent("AUTO", "Physical network restored within handover grace period. Disconnect cancelled.", "Handover Recovered")
            }
        } else {
            // All physical networks lost
            _currentSsid.value = null
            if (isVpnRunning) {
                if (handoverGraceJob?.isActive == true) {
                    return
                }
                _lastAutomationStatus.value = "Networks lost. Waiting ${HANDOVER_GRACE_PERIOD_MS / 1000}s handover grace period…"
                handoverGraceJob = scope.launch {
                    delay(HANDOVER_GRACE_PERIOD_MS)
                    actionMutex.withLock {
                        val recheck = checkCurrentPhysicalNetworks()
                        if (!recheck.hasWifi && !recheck.hasCellular) {
                            val latestState = V2RayCoreManager.currentConnectionState.value
                            if (latestState.isRunning) {
                                _lastAutomationStatus.value = "Networks lost after grace period -> Stopped VPN"
                                logAutoEvent("AUTO", "All physical networks remained lost for ${HANDOVER_GRACE_PERIOD_MS / 1000}s. Auto-stopping VPN.", "Handover Grace Expired")
                                V2RayVpnService.stop(context, "Network lost (Handover grace expired)", isAutomated = true)
                            }
                        }
                        handoverGraceJob = null
                    }
                }
                return
            } else {
                _lastAutomationStatus.value = "No network connections (Data & Wi-Fi OFF)"
                return
            }
        }

        // 2. Wi-Fi SSID Automation & "Any SSID" Toggle Logic:
        if (hasWifi) {
            val detectedSsid = detectCurrentWifiSsid(wifiCaps)
            val currentCleanSsid = cleanSsidString(detectedSsid)
            _currentSsid.value = currentCleanSsid

            if (!settings.matchAnyWifiNetwork) {
                // "Any SSID" is OFF: user requires specific SSID
                if (currentCleanSsid == null) {
                    // Rule 4: If matchAnyWifiNetwork is false and SSID is null:
                    // do NOT stop the VPN. Keep the current state, set status
                    // "SSID unavailable - grant location permission and turn on Location"
                    // so the user is informed, and do not oscillate the VPN on/off.
                    _lastAutomationStatus.value = "SSID unavailable - grant location permission and turn on Location"
                    return
                }

                val specificClean = cleanSsidString(settings.specificSsid)
                val configuredClean = settings.targetWifiSsids.mapNotNull { cleanSsidString(it) }
                val isApproved = (specificClean != null && currentCleanSsid.equals(specificClean, ignoreCase = true)) ||
                        configuredClean.any { it.equals(currentCleanSsid, ignoreCase = true) }

                if (isApproved) {
                    handleApprovedWifi(currentCleanSsid, settings)
                } else {
                    if (isVpnRunning) {
                        _lastAutomationStatus.value = "Unapproved Wi-Fi SSID ($currentCleanSsid) -> Stopped VPN"
                        logAutoEvent("AUTO", "Wi-Fi SSID ($currentCleanSsid) does not match approved target. Stopping VPN.", "SSID Mismatch")
                        V2RayVpnService.stop(context, "Unapproved Wi-Fi SSID", isAutomated = true)
                    } else {
                        _lastAutomationStatus.value = "Wi-Fi ($currentCleanSsid) does not match target SSID"
                    }
                }
            } else {
                // "Any SSID" is ON: any active Wi-Fi network connects
                handleApprovedWifi(currentCleanSsid ?: "Any Wi-Fi", settings)
            }
        } else if (hasCellular && !hasWifi) {
            _currentSsid.value = null
            // Mobile Data only
            if (isVpnRunning) {
                if (settings.autoStopOnWifiLeave) {
                    when (settings.wifiLeavePolicy) {
                        WifiLeavePolicy.STOP_VPN -> {
                            _lastAutomationStatus.value = "Left Wi-Fi -> Stopped VPN per policy"
                            logAutoEvent("AUTO", "Disconnected from Wi-Fi. Stopping VPN per leave policy.", "Left target Wi-Fi")
                            V2RayVpnService.stop(context, "Left target Wi-Fi", isAutomated = true)
                        }
                        WifiLeavePolicy.SWITCH_TO_MOBILE -> {
                            if (settings.autoStartOnMobileData) {
                                val profile = V2RayVpnService.resolveProfile(settings.mobileProfileId)
                                if (profile != null) {
                                    _lastAutomationStatus.value = "Left Wi-Fi -> Switched to Mobile Data"
                                    logAutoEvent("AUTO", "Switched from Wi-Fi to Mobile Data. Starting with mobile profile.", "Mobile Data Switch")
                                    V2RayVpnService.start(context, profile.id, isAutomated = true)
                                }
                            } else {
                                _lastAutomationStatus.value = "Left Wi-Fi -> Stopped VPN (Mobile Data auto-start disabled)"
                                V2RayVpnService.stop(context, "Left target Wi-Fi", isAutomated = true)
                            }
                        }
                        WifiLeavePolicy.KEEP_VPN -> {
                            _lastAutomationStatus.value = "Left Wi-Fi -> VPN kept running on Mobile Data"
                        }
                    }
                } else {
                    _lastAutomationStatus.value = "VPN active on Mobile Data"
                }
            } else {
                if (settings.autoStartOnMobileData) {
                    val profile = V2RayVpnService.resolveProfile(settings.mobileProfileId)
                    if (profile != null) {
                        _lastAutomationStatus.value = "Mobile Data active -> Started VPN"
                        logAutoEvent("AUTO", "Mobile data connected. Auto-starting VPN…", "Mobile Data Connected")
                        V2RayVpnService.start(context, profile.id, isAutomated = true)
                    } else {
                        _lastAutomationStatus.value = "Mobile Data active, but no profiles configured"
                    }
                } else {
                    _lastAutomationStatus.value = "Mobile Data active (Auto-start on Mobile Data OFF)"
                }
            }
        }
    }

    private suspend fun handleApprovedWifi(ssidLabel: String, settings: AutomationSettings) {
        val coreState = V2RayCoreManager.currentConnectionState.value
        if (settings.autoStartOnWifi && !coreState.isRunning) {
            if (android.net.VpnService.prepare(context) == null) {
                val profile = V2RayVpnService.resolveProfile(settings.wifiProfileId)
                if (profile == null) {
                    _lastAutomationStatus.value = "Wi-Fi active ($ssidLabel), but no profiles configured"
                    logAutoEvent("ERROR", "No profiles configured to auto-start VPN", "No Profiles")
                    return
                }
                _lastAutomationStatus.value = "Connected to Wi-Fi ($ssidLabel) -> Auto-started VPN"
                logAutoEvent("AUTO", "Approved Wi-Fi active ($ssidLabel). Auto-starting VPN…", "Approved Wi-Fi Connected")
                V2RayVpnService.start(context, profile.id, isAutomated = true)
            } else {
                _lastAutomationStatus.value = "Wi-Fi connected ($ssidLabel), tap Start in app once to grant VPN permission"
            }
        } else if (coreState.isRunning) {
            _lastAutomationStatus.value = "VPN active on Wi-Fi ($ssidLabel)"
        }
    }

    private fun detectCurrentWifiSsid(caps: NetworkCapabilities?): String? {
        val app = V2RayApplication.get()
        val settings = app.preferencesRepository.getAutomationSettings()

        if (!settings.wifiSsidPermissionEnabled) {
            return null
        }

        // On API 31+ (Android 12 S), prefer NetworkCapabilities.transportInfo as WifiInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps != null) {
            val transportInfo = caps.transportInfo
            if (transportInfo is WifiInfo) {
                val clean = cleanSsidString(transportInfo.ssid)
                if (clean != null) return clean
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
            val transportInfo = caps.transportInfo
            if (transportInfo is WifiInfo) {
                val clean = cleanSsidString(transportInfo.ssid)
                if (clean != null) return clean
            }
        }

        // Fallback to WifiManager.connectionInfo
        val hasLocationPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPerm && wifiManager != null) {
            try {
                val connectionInfo = wifiManager.connectionInfo
                if (connectionInfo != null) {
                    val clean = cleanSsidString(connectionInfo.ssid)
                    if (clean != null) return clean
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun cleanSsidString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "<unknown ssid>" || raw == "\"<unknown ssid>\"") {
            return null
        }
        var s = raw.trim()
        // Strip matching quotes: "MySSID" -> MySSID
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.trim().ifEmpty { null }
    }

    private fun logAutoEvent(level: String, message: String, triggerReason: String = "") {
        scope.launch(Dispatchers.IO) {
            val app = V2RayApplication.get()
            app.database.appLogDao().insertLog(
                AppLog(
                    tag = "Automation",
                    message = message,
                    level = level,
                    triggerReason = triggerReason
                )
            )
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 800L
        private const val HANDOVER_GRACE_PERIOD_MS = 4000L
        private const val REQUIRE_VALIDATED_INTERNET = false
    }
}

/**
 * Foreground service for API 24-25 devices to keep network callback active in the background
 */
class AutomationMonitorService : Service() {

    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        val notification = NotificationCompat.Builder(this, V2RayApplication.NOTIFICATION_CHANNEL_AUTOMATION)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle("v2rayDP Automation Monitor")
            .setContentText("Monitoring network state")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_AUTOMATION_MONITOR,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID_AUTOMATION_MONITOR, notification)
            }
        } catch (_: Exception) {}

        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                V2RayApplication.get().automationManager.notifyNetworkEvent("monitor_onAvailable")
            }

            override fun onLost(network: Network) {
                V2RayApplication.get().automationManager.notifyNetworkEvent("monitor_onLost")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                V2RayApplication.get().automationManager.notifyNetworkEvent("monitor_onCapabilitiesChanged")
            }
        }
        callback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID_AUTOMATION_MONITOR = 2002
    }
}
