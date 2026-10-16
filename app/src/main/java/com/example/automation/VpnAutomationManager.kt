package com.example.automation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.V2RayApplication
import com.example.model.AppLog
import com.example.model.AutomationSettings
import com.example.model.ConnectionState
import com.example.model.WifiLeavePolicy
import com.example.receiver.NetworkChangeReceiver
import com.example.service.V2RayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class VpnAutomationManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val actionMutex = Mutex()

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Dynamic in-process callback and receiver (active ONLY while app is in foreground)
    private var inProcessCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null
    private var isForegroundActive = false

    // Tracked map of Network -> NetworkCapabilities (Item 9: replaces deprecated allNetworks)
    private val networkCapabilitiesMap = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val networkSsidMap = ConcurrentHashMap<Network, String?>()

    // Conflated channel for in-process debounced events (Item 2)
    private val inProcessChannel = Channel<String>(Channel.CONFLATED)

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

    // Cooldown & retry state (Item 6)
    private var lastActionTimestamp = 0L
    private var autoRetryCount = 0
    private var lastRetryNetworkKey: String? = null

    private val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    fun initialize() {
        _isWifiRadioEnabled.value = wifiManager?.isWifiEnabled ?: true

        // 1. Register system PendingIntent network callback (API 26+) for background monitoring
        registerBackgroundPendingIntentCallback()

        // 2. Start in-process channel consumer with 800ms conflated delay
        scope.launch {
            for (source in inProcessChannel) {
                delay(DEBOUNCE_MS)
                evaluateNow(source)
            }
        }

        // 3. Initial evaluation
        notifyNetworkEvent("init")
    }

    // --- Lifecycle observation (Item 8: in-process callback only while in foreground) ---

    fun onAppForegrounded() {
        isForegroundActive = true
        registerInProcessMonitoring()
        notifyNetworkEvent("foreground")
    }

    fun onAppBackgrounded() {
        isForegroundActive = false
        unregisterInProcessMonitoring()
    }

    private fun registerBackgroundPendingIntentCallback() {
        if (connectivityManager == null) return
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
            logAutoEvent("WARN", "PendingIntent network callback registration failed: ${e.message}", source = "init")
        }
    }

    private fun registerInProcessMonitoring() {
        if (connectivityManager != null && inProcessCallback == null) {
            val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onAvailable(network: Network) {
                        notifyNetworkEvent("cb_onAvailable")
                    }

                    override fun onLost(network: Network) {
                        networkCapabilitiesMap.remove(network)
                        networkSsidMap.remove(network)
                        notifyNetworkEvent("cb_onLost")
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        networkCapabilitiesMap[network] = caps
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            val transportInfo = caps.transportInfo
                            if (transportInfo is WifiInfo) {
                                networkSsidMap[network] = cleanSsidString(transportInfo.ssid)
                            }
                        }
                        notifyNetworkEvent("cb_onCapabilitiesChanged")
                    }
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        notifyNetworkEvent("cb_onAvailable")
                    }

                    override fun onLost(network: Network) {
                        networkCapabilitiesMap.remove(network)
                        networkSsidMap.remove(network)
                        notifyNetworkEvent("cb_onLost")
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        networkCapabilitiesMap[network] = caps
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val transportInfo = caps.transportInfo
                            if (transportInfo is WifiInfo) {
                                networkSsidMap[network] = cleanSsidString(transportInfo.ssid)
                            }
                        }
                        notifyNetworkEvent("cb_onCapabilitiesChanged")
                    }
                }
            }
            inProcessCallback = cb
            try {
                connectivityManager.registerNetworkCallback(networkRequest, cb)
            } catch (_: Exception) {}
        }

        registerDynamicWifiReceiver()
    }

    private fun unregisterInProcessMonitoring() {
        inProcessCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            inProcessCallback = null
        }
        networkCapabilitiesMap.clear()
        networkSsidMap.clear()

        wifiStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            wifiStateReceiver = null
        }
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
        } catch (_: Exception) {}
    }

    suspend fun onBootCompleted() {
        val app = V2RayApplication.get()
        val settings = app.preferencesRepository.getAutomationSettings()
        if (settings.isAutomationGloballyEnabled) {
            registerBackgroundPendingIntentCallback()
            evaluateNow("boot_completed")
        }
    }

    fun notifyNetworkEvent(source: String) {
        inProcessChannel.trySend(source)
    }

    fun forceEvaluateNow() {
        scope.launch {
            evaluateNow("force_evaluate")
        }
    }

    suspend fun evaluateNetworkState() {
        evaluateNow("direct_evaluate")
    }

    /**
     * Executes evaluation immediately inside the caller's coroutine window (Item 2).
     * Protected by the shared actionMutex so concurrent evaluations never overlap.
     */
    suspend fun evaluateNow(source: String) {
        actionMutex.withLock {
            evaluateNetworkStateInternal(source)
        }
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
                    val currentState = V2RayVpnService.serviceState.value
                    if (currentState.isRunning) {
                        _lastAutomationStatus.value = "Wi-Fi turned OFF -> Auto-stopping VPN"
                        logAutoEvent("AUTO", "Wi-Fi radio switched OFF. Auto-stopping VPN service.", getCurrentNetworkKey(), null, "wifi_radio_off")
                        stopVpnWithCooldown("Wi-Fi disabled by user")
                    }
                }
            } else if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                _isWifiRadioEnabled.value = true
                notifyNetworkEvent("wifi_radio_on")
            }
        }
    }

    data class PhysicalNetworkStatus(
        val isWifiUsable: Boolean,
        val wifiNetwork: Network?,
        val wifiCaps: NetworkCapabilities?,
        val isCellularUsable: Boolean,
        val cellNetwork: Network?,
        val cellCaps: NetworkCapabilities?
    )

    private fun checkCurrentPhysicalNetworks(): PhysicalNetworkStatus {
        var wifiNet: Network? = null
        var wifiCaps: NetworkCapabilities? = null
        var cellNet: Network? = null
        var cellCaps: NetworkCapabilities? = null

        // Check tracked capability map first
        for ((net, caps) in networkCapabilitiesMap) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (hasInternet && isValidated) {
                    wifiNet = net
                    wifiCaps = caps
                }
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                if (hasInternet && isValidated) {
                    cellNet = net
                    cellCaps = caps
                }
            }
        }

        // If tracked map was empty (e.g. background wake), query active network from ConnectivityManager
        if (wifiCaps == null && cellCaps == null && connectivityManager != null) {
            val activeNet = connectivityManager.activeNetwork
            if (activeNet != null) {
                val caps = connectivityManager.getNetworkCapabilities(activeNet)
                if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (hasInternet && isValidated) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            wifiNet = activeNet
                            wifiCaps = caps
                            networkCapabilitiesMap[activeNet] = caps
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            cellNet = activeNet
                            cellCaps = caps
                            networkCapabilitiesMap[activeNet] = caps
                        }
                    }
                }
            }
        }

        val isWifiUsable = wifiCaps != null
        val isCellularUsable = cellCaps != null

        return PhysicalNetworkStatus(
            isWifiUsable = isWifiUsable,
            wifiNetwork = wifiNet,
            wifiCaps = wifiCaps,
            isCellularUsable = isCellularUsable,
            cellNetwork = cellNet,
            cellCaps = cellCaps
        )
    }

    fun getCurrentNetworkKey(): String {
        val status = checkCurrentPhysicalNetworks()
        return if (status.isWifiUsable) {
            val ssid = cleanSsidString(detectCurrentWifiSsid(status.wifiNetwork, status.wifiCaps))
            "WIFI:${ssid ?: "<no-ssid>"}"
        } else if (status.isCellularUsable) {
            "CELLULAR:cell"
        } else {
            "NONE:none"
        }
    }

    private suspend fun evaluateNetworkStateInternal(source: String) {
        val app = V2RayApplication.get()
        val prefs = app.preferencesRepository
        val settings = prefs.getAutomationSettings()

        if (!settings.isAutomationGloballyEnabled) {
            _lastAutomationStatus.value = "Automation is globally disabled"
            return
        }

        // State Machine Guard (Item 6 & 7):
        // Single source of truth is V2RayVpnService.serviceState
        val serviceState = V2RayVpnService.serviceState.value

        if (serviceState is ConnectionState.Connecting) {
            logAutoEvent("SKIP", "Ignoring evaluation: VPN service is currently Connecting", getCurrentNetworkKey(), null, source)
            return
        }
        if (serviceState is ConnectionState.Disconnected && serviceState.reason.startsWith("Stopping")) {
            logAutoEvent("SKIP", "Ignoring evaluation: VPN service is currently Stopping", getCurrentNetworkKey(), null, source)
            return
        }

        // Reset retry count on connected
        if (serviceState is ConnectionState.Connected) {
            autoRetryCount = 0
        }

        // Inspect physical networks with strict quality validation (Item 9)
        val netStatus = checkCurrentPhysicalNetworks()
        val isWifiUsable = netStatus.isWifiUsable
        val isCellularUsable = netStatus.isCellularUsable

        _isWifiConnected.value = isWifiUsable
        _isCellularConnected.value = isCellularUsable
        _isWifiRadioEnabled.value = wifiManager?.isWifiEnabled ?: isWifiUsable

        val detectedSsid = if (isWifiUsable) detectCurrentWifiSsid(netStatus.wifiNetwork, netStatus.wifiCaps) else null
        val currentCleanSsid = cleanSsidString(detectedSsid)
        _currentSsid.value = currentCleanSsid

        // Compute current network key (Item 1)
        val currentNetworkKey = when {
            isWifiUsable -> "WIFI:${currentCleanSsid ?: "<no-ssid>"}"
            isCellularUsable -> "CELLULAR:cell"
            else -> "NONE:none"
        }

        // Error retry guard (Item 6: at most 2 retries per network key)
        if (serviceState is ConnectionState.Error) {
            if (lastRetryNetworkKey != currentNetworkKey) {
                autoRetryCount = 0
                lastRetryNetworkKey = currentNetworkKey
            }
            if (autoRetryCount >= 2 && source != "force_evaluate") {
                _lastAutomationStatus.value = "Automatic retries exhausted for this network (2/2 attempts failed)"
                logAutoEvent("SKIP", "Max auto-retries reached (2/2) for network $currentNetworkKey", currentNetworkKey, currentCleanSsid, source)
                return
            }
        }

        // 5s post-action cooldown (Item 6)
        val now = System.currentTimeMillis()
        if (now - lastActionTimestamp < 5000L && source != "force_evaluate" && source != "init") {
            logAutoEvent("SKIP", "Ignoring evaluation during 5s post-action cooldown", currentNetworkKey, currentCleanSsid, source)
            return
        }

        // Check manual override (Item 1: network-scoped persisted override)
        if (settings.manualOverrideSuppressed) {
            if (settings.manualOverrideNetworkKey != null && settings.manualOverrideNetworkKey == currentNetworkKey && source != "force_evaluate") {
                _lastAutomationStatus.value = "Paused by manual stop (this network)"
                logAutoEvent("SKIP", "Paused by manual stop on this network ($currentNetworkKey)", currentNetworkKey, currentCleanSsid, source)
                return
            } else {
                // Physical network changed -> clear manual override!
                prefs.clearManualOverride()
                logAutoEvent("AUTO", "Physical network switched (${settings.manualOverrideNetworkKey} -> $currentNetworkKey). Manual override cleared.", currentNetworkKey, currentCleanSsid, source)
            }
        }

        val isVpnRunning = serviceState.isRunning

        // 1. Handover Grace Period & Disconnect Check:
        if (isWifiUsable || isCellularUsable) {
            // Cancel pending handover stop if physical network restored
            if (handoverGraceJob?.isActive == true) {
                handoverGraceJob?.cancel()
                handoverGraceJob = null
                logAutoEvent("AUTO", "Physical network restored within handover grace period. Disconnect cancelled.", currentNetworkKey, currentCleanSsid, source)
            }
        } else {
            // All usable physical networks lost
            _currentSsid.value = null
            if (isVpnRunning) {
                // Item 3: when all networks are lost and autoStopOnMobileDataDisabled is false, do not stop the VPN!
                if (!settings.autoStopOnMobileDataDisabled) {
                    _lastAutomationStatus.value = "Networks lost, VPN kept running (auto-stop on network loss is OFF)"
                    logAutoEvent("SKIP", "Networks lost but autoStopOnMobileDataDisabled is false; keeping VPN running", currentNetworkKey, null, source)
                    return
                }

                if (handoverGraceJob?.isActive == true) {
                    return
                }
                _lastAutomationStatus.value = "Networks lost. Waiting ${HANDOVER_GRACE_PERIOD_MS / 1000}s handover grace period…"
                handoverGraceJob = scope.launch {
                    delay(HANDOVER_GRACE_PERIOD_MS)
                    actionMutex.withLock {
                        val recheck = checkCurrentPhysicalNetworks()
                        if (!recheck.isWifiUsable && !recheck.isCellularUsable) {
                            val latestState = V2RayVpnService.serviceState.value
                            if (latestState.isRunning) {
                                _lastAutomationStatus.value = "Networks lost after grace period -> Stopped VPN"
                                logAutoEvent("AUTO", "All physical networks remained lost for ${HANDOVER_GRACE_PERIOD_MS / 1000}s. Auto-stopping VPN.", currentNetworkKey, null, source)
                                stopVpnWithCooldown("Network lost (Handover grace expired)")
                            }
                        }
                        handoverGraceJob = null
                    }
                }
                return
            } else {
                _lastAutomationStatus.value = "No network connections (Data & Wi-Fi OFF)"
                logAutoEvent("SKIP", "No usable physical internet connection", currentNetworkKey, null, source)
                return
            }
        }

        // 2. Wi-Fi Automation Logic:
        if (isWifiUsable) {
            // Clear mobile session leave policy when back on Wi-Fi
            if (settings.stoppedByPolicyMobileSession) {
                prefs.clearStoppedByPolicy()
            }

            // Item 3: autoReconnectOnWifiReturn logic
            if (settings.stoppedByLeaveWifiSsid != null && currentCleanSsid != null &&
                settings.stoppedByLeaveWifiSsid.equals(currentCleanSsid, ignoreCase = true)
            ) {
                if (!settings.autoReconnectOnWifiReturn) {
                    _lastAutomationStatus.value = "Wi-Fi ($currentCleanSsid) returned, but auto-reconnect on return is OFF"
                    logAutoEvent("SKIP", "Auto-reconnect on Wi-Fi return is disabled for $currentCleanSsid", currentNetworkKey, currentCleanSsid, source)
                    return
                } else {
                    prefs.clearStoppedByLeaveWifiSsid()
                }
            }

            if (!settings.matchAnyWifiNetwork) {
                // "Any SSID" is OFF: requires specific approved SSID match

                // Item 5: Detect system location turned off
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && locationManager?.isLocationEnabled == false) {
                    _lastAutomationStatus.value = "Turn on Location to read the Wi-Fi name"
                    logAutoEvent("WARN", "System Location is turned off; cannot read Wi-Fi SSID", currentNetworkKey, currentCleanSsid, source)
                    return
                }

                if (currentCleanSsid == null) {
                    _lastAutomationStatus.value = "SSID unavailable - grant location permission and turn on Location"
                    logAutoEvent("WARN", "SSID unavailable - location permission or service required", currentNetworkKey, null, source)
                    return
                }

                val specificClean = cleanSsidString(settings.specificSsid)
                val configuredClean = settings.targetWifiSsids.mapNotNull { cleanSsidString(it) }
                val isApproved = (specificClean != null && currentCleanSsid.equals(specificClean, ignoreCase = true)) ||
                        configuredClean.any { it.equals(currentCleanSsid, ignoreCase = true) }

                if (isApproved) {
                    handleApprovedWifi(currentCleanSsid, settings, currentNetworkKey, source)
                } else {
                    if (isVpnRunning) {
                        _lastAutomationStatus.value = "Unapproved Wi-Fi SSID ($currentCleanSsid) -> Stopped VPN"
                        logAutoEvent("AUTO", "Wi-Fi SSID ($currentCleanSsid) does not match approved target. Stopping VPN.", currentNetworkKey, currentCleanSsid, source)
                        stopVpnWithCooldown("Unapproved Wi-Fi SSID")
                    } else {
                        _lastAutomationStatus.value = "Wi-Fi ($currentCleanSsid) does not match target SSID"
                        logAutoEvent("SKIP", "Wi-Fi ($currentCleanSsid) is not in approved list", currentNetworkKey, currentCleanSsid, source)
                    }
                }
            } else {
                // "Any SSID" is ON: any usable Wi-Fi network connects (no location permission needed)
                handleApprovedWifi(currentCleanSsid ?: "Any Wi-Fi", settings, currentNetworkKey, source)
            }
        } else if (isCellularUsable && !isWifiUsable) {
            // Mobile Data session
            _currentSsid.value = null

            if (isVpnRunning) {
                if (settings.autoStopOnWifiLeave) {
                    when (settings.wifiLeavePolicy) {
                        WifiLeavePolicy.STOP_VPN -> {
                            // Item 4: persist stoppedByPolicy for current mobile session and do NOT auto-start on mobile
                            prefs.setStoppedByPolicy(mobileSession = true, wifiSsid = currentCleanSsid)
                            _lastAutomationStatus.value = "Left Wi-Fi -> Stopped VPN per policy (stay off on mobile)"
                            logAutoEvent("AUTO", "Left Wi-Fi. Stopping VPN per STOP_VPN policy (stay off on mobile).", currentNetworkKey, null, source)
                            stopVpnWithCooldown("Left target Wi-Fi (STOP_VPN)")
                        }
                        WifiLeavePolicy.SWITCH_TO_MOBILE -> {
                            _lastAutomationStatus.value = "Left Wi-Fi -> Switching to Mobile Data profile"
                            logAutoEvent("AUTO", "Left Wi-Fi. Switching to Mobile Data profile.", currentNetworkKey, null, source)
                            val profile = V2RayVpnService.resolveProfile(settings.mobileProfileId)
                            if (profile != null) {
                                startVpnWithCooldown(profile.id, "Left Wi-Fi (SWITCH_TO_MOBILE)")
                            } else {
                                _lastAutomationStatus.value = "Left Wi-Fi, but no mobile profile configured"
                                logAutoEvent("ERROR", "No profiles found for mobile data switch", currentNetworkKey, null, source)
                            }
                        }
                        WifiLeavePolicy.KEEP_VPN -> {
                            _lastAutomationStatus.value = "Left Wi-Fi -> VPN kept running on Mobile Data"
                            logAutoEvent("SKIP", "Left Wi-Fi with KEEP_VPN policy. Seamlessly continuing.", currentNetworkKey, null, source)
                        }
                    }
                } else {
                    _lastAutomationStatus.value = "VPN active on Mobile Data"
                }
            } else {
                // Item 4: if stopped by leave-policy on mobile, stay off until network switches
                if (settings.stoppedByPolicyMobileSession) {
                    _lastAutomationStatus.value = "Mobile Data active (Stopped by Wi-Fi leave policy)"
                    logAutoEvent("SKIP", "Mobile Data auto-start suppressed by leave-Wi-Fi policy for this session", currentNetworkKey, null, source)
                    return
                }

                if (settings.autoStartOnMobileData) {
                    // Item 6: Mobile-data start path checks VpnService.prepare(context) == null
                    if (VpnService.prepare(context) != null) {
                        _lastAutomationStatus.value = "Open the app once to grant VPN permission"
                        logAutoEvent("WARN", "Mobile Data active but VPN permission not yet granted", currentNetworkKey, null, source)
                        return
                    }

                    val profile = V2RayVpnService.resolveProfile(settings.mobileProfileId)
                    if (profile != null) {
                        _lastAutomationStatus.value = "Mobile Data active -> Started VPN"
                        logAutoEvent("AUTO", "Mobile data connected. Auto-starting VPN…", currentNetworkKey, null, source)
                        startVpnWithCooldown(profile.id, "Mobile Data active")
                    } else {
                        _lastAutomationStatus.value = "Mobile Data active, but no profiles configured"
                        logAutoEvent("ERROR", "No profiles configured to auto-start VPN on mobile data", currentNetworkKey, null, source)
                    }
                } else {
                    _lastAutomationStatus.value = "Mobile Data active (Auto-start on Mobile Data OFF)"
                    logAutoEvent("SKIP", "Mobile Data active but auto-start on mobile is disabled", currentNetworkKey, null, source)
                }
            }
        }
    }

    private suspend fun handleApprovedWifi(
        ssidLabel: String,
        settings: AutomationSettings,
        currentNetworkKey: String,
        source: String
    ) {
        val serviceState = V2RayVpnService.serviceState.value
        if (settings.autoStartOnWifi && !serviceState.isRunning) {
            if (VpnService.prepare(context) == null) {
                val profile = V2RayVpnService.resolveProfile(settings.wifiProfileId)
                if (profile == null) {
                    _lastAutomationStatus.value = "Wi-Fi active ($ssidLabel), but no profiles configured"
                    logAutoEvent("ERROR", "No profiles configured to auto-start VPN on Wi-Fi", currentNetworkKey, ssidLabel, source)
                    return
                }
                _lastAutomationStatus.value = "Connected to Wi-Fi ($ssidLabel) -> Auto-started VPN"
                logAutoEvent("AUTO", "Approved Wi-Fi active ($ssidLabel). Auto-starting VPN…", currentNetworkKey, ssidLabel, source)
                startVpnWithCooldown(profile.id, "Approved Wi-Fi ($ssidLabel)")
            } else {
                _lastAutomationStatus.value = "Wi-Fi connected ($ssidLabel), tap Start in app once to grant VPN permission"
                logAutoEvent("WARN", "Wi-Fi connected ($ssidLabel) but VPN permission is not yet prepared", currentNetworkKey, ssidLabel, source)
            }
        } else if (serviceState.isRunning) {
            _lastAutomationStatus.value = "VPN active on Wi-Fi ($ssidLabel)"
            logAutoEvent("SKIP", "VPN already running on Wi-Fi ($ssidLabel)", currentNetworkKey, ssidLabel, source)
        }
    }

    private fun detectCurrentWifiSsid(network: Network?, caps: NetworkCapabilities?): String? {
        // 1. Check cached SSID from in-process callback with FLAG_INCLUDE_LOCATION_INFO (API 31+)
        if (network != null) {
            val cached = networkSsidMap[network]
            if (cached != null) return cached
        }

        // 2. Read directly from NetworkCapabilities.transportInfo (API 29+)
        if (caps != null) {
            val transportInfo = caps.transportInfo
            if (transportInfo is WifiInfo) {
                val clean = cleanSsidString(transportInfo.ssid)
                if (clean != null) return clean
            }
        }

        // 3. Fallback to WifiManager.connectionInfo
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
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.trim().ifEmpty { null }
    }

    private fun startVpnWithCooldown(profileId: Long?, triggerReason: String) {
        autoRetryCount++
        lastActionTimestamp = System.currentTimeMillis()
        V2RayVpnService.start(context, profileId, isAutomated = true)
    }

    private fun stopVpnWithCooldown(reason: String) {
        lastActionTimestamp = System.currentTimeMillis()
        V2RayVpnService.stop(context, reason, isAutomated = true)
    }

    private fun logAutoEvent(
        level: String,
        message: String,
        networkKey: String = getCurrentNetworkKey(),
        ssid: String? = null,
        source: String = ""
    ) {
        scope.launch(Dispatchers.IO) {
            val app = V2RayApplication.get()
            val fullMsg = buildString {
                append(message)
                if (networkKey.isNotEmpty()) append(" [net: $networkKey]")
                if (!ssid.isNullOrEmpty()) append(" [ssid: $ssid]")
                if (source.isNotEmpty()) append(" [src: $source]")
            }
            app.database.appLogDao().insertLog(
                AppLog(
                    tag = "Automation",
                    message = fullMsg,
                    level = level,
                    triggerReason = if (source.isNotEmpty()) "Source: $source" else "Network Event"
                )
            )
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 800L
        private const val HANDOVER_GRACE_PERIOD_MS = 4000L
    }
}
