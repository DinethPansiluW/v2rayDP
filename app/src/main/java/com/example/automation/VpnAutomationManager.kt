package com.example.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.V2RayApplication
import com.example.model.AppLog
import com.example.model.WifiLeavePolicy
import com.example.service.V2RayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VpnAutomationManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val actionMutex = Mutex()

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

    private var lastKnownSsid: String? = null
    private var lastActionTime = 0L

    fun initialize() {
        _isWifiRadioEnabled.value = wifiManager?.isWifiEnabled ?: true
        registerNetworkCallback()
        registerDynamicWifiReceiver()
        scope.launch {
            delay(500)
            evaluateNetworkState()
        }
    }

    private fun registerDynamicWifiReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction("android.net.conn.CONNECTIVITY_CHANGE")
            }
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                            val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                            onWifiStateChanged(state)
                        }
                        WifiManager.NETWORK_STATE_CHANGED_ACTION,
                        "android.net.conn.CONNECTIVITY_CHANGE" -> {
                            scope.launch {
                                evaluateNetworkState()
                            }
                        }
                    }
                }
            }, filter)
        } catch (_: Exception) {}
    }

    private fun registerNetworkCallback() {
        if (connectivityManager == null) return

        val request = NetworkRequest.Builder().build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    delay(300)
                    evaluateNetworkState()
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    evaluateNetworkState()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scope.launch {
                    evaluateNetworkState()
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            logAutoEvent("WARN", "Failed to register network callback: ${e.message}")
        }
    }

    fun onWifiStateChanged(wifiState: Int) {
        scope.launch {
            actionMutex.withLock {
                val app = V2RayApplication.get()
                val settings = app.preferencesRepository.getAutomationSettings()

                if (wifiState == WifiManager.WIFI_STATE_DISABLED || wifiState == WifiManager.WIFI_STATE_DISABLING) {
                    _isWifiRadioEnabled.value = false
                    _isWifiConnected.value = false
                    _currentSsid.value = null
                    lastKnownSsid = null

                    if (settings.isAutomationGloballyEnabled && settings.autoStopOnWifiOff) {
                        if (V2RayVpnService.serviceState.value.isRunning) {
                            _lastAutomationStatus.value = "Wi-Fi turned OFF -> Auto-stopping VPN"
                            logAutoEvent("AUTO", "Wi-Fi radio switched OFF. Auto-stopping VPN service.", "Wi-Fi disabled by user")
                            V2RayVpnService.stop(context, "Wi-Fi disabled by user")
                        }
                    }
                } else if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                    _isWifiRadioEnabled.value = true
                    _lastAutomationStatus.value = "Wi-Fi radio turned ON"
                    logAutoEvent("INFO", "Wi-Fi radio turned ON. Evaluating connection…", "Wi-Fi enabled")
                    // Automatically clear manual override when user turns Wi-Fi back ON
                    app.preferencesRepository.setManualOverride(false)
                    delay(600)
                    evaluateNetworkStateInternal(app.preferencesRepository.getAutomationSettings(), force = true)
                }
            }
        }
    }

    fun forceEvaluateNow() {
        scope.launch {
            actionMutex.withLock {
                val app = V2RayApplication.get()
                // Clear manual override when user tests manually
                app.preferencesRepository.setManualOverride(false)
                evaluateNetworkStateInternal(app.preferencesRepository.getAutomationSettings(), force = true)
            }
        }
    }

    suspend fun evaluateNetworkState() = actionMutex.withLock {
        val app = V2RayApplication.get()
        evaluateNetworkStateInternal(app.preferencesRepository.getAutomationSettings(), force = false)
    }

    private suspend fun evaluateNetworkStateInternal(settings: com.example.model.AutomationSettings, force: Boolean = false) {
        if (!settings.isAutomationGloballyEnabled) {
            _lastAutomationStatus.value = "Automation is globally disabled"
            return
        }

        if (!settings.backgroundRunEnabled && !force) {
            _lastAutomationStatus.value = "Background Run is disabled (Automation paused)"
            return
        }

        if (settings.manualOverrideSuppressed && !force) {
            _lastAutomationStatus.value = "Suppressed by Manual Override (${settings.manualOverrideReason})"
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastActionTime < 1000) {
            return
        }

        // Multi-network inspection to catch underlying Wi-Fi even under VPN
        val allNetworks = connectivityManager?.allNetworks ?: emptyArray()
        var hasWifi = false
        var wifiCaps: NetworkCapabilities? = null
        var hasCellular = false

        for (net in allNetworks) {
            val caps = connectivityManager?.getNetworkCapabilities(net) ?: continue
            // Exclude the VPN network itself from physical interface checks
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                hasWifi = true
                wifiCaps = caps
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                hasCellular = true
            }
        }

        val radioOn = wifiManager?.isWifiEnabled ?: hasWifi
        _isWifiRadioEnabled.value = radioOn
        _isWifiConnected.value = hasWifi
        _isCellularConnected.value = hasCellular

        val ssid = if (hasWifi) detectCurrentWifiSsid(wifiCaps) else null
        _currentSsid.value = ssid

        val isVpnRunning = V2RayVpnService.serviceState.value.isRunning

        // 1. Critical Disconnect Check (Data OFF -> VPN OFF):
        // When Mobile Data is turned OFF or all internet connections are lost, VPN immediately stops!
        if (!hasWifi && !hasCellular) {
            _lastAutomationStatus.value = "No network connections (Data & Wi-Fi OFF)"
            if (isVpnRunning) {
                lastActionTime = now
                lastKnownSsid = null
                _lastAutomationStatus.value = "Data/Wi-Fi OFF -> Stopped VPN"
                logAutoEvent("AUTO", "All physical networks lost (Mobile Data & Wi-Fi OFF). Stopping VPN service.", "Network Disconnected / Data OFF")
                V2RayVpnService.stop(context, "Network disconnected / Data OFF")
                return
            }
        }

        // 2. Wi-Fi SSID Automation & "Any SSID" Toggle Logic:
        if (hasWifi) {
            val currentCleanSsid = cleanSsidString(ssid)
            val specificClean = cleanSsidString(settings.specificSsid)
            val configuredClean = settings.targetWifiSsids.mapNotNull { cleanSsidString(it) }

            // If "Any SSID" is checked, connect on any Wi-Fi.
            // If unchecked, connect ONLY if the connected SSID matches user-defined input string.
            val isTargetSsid = if (settings.matchAnyWifiNetwork) {
                true
            } else {
                if (currentCleanSsid == null) {
                    false
                } else {
                    (specificClean != null && currentCleanSsid.equals(specificClean, ignoreCase = true)) ||
                    configuredClean.any { it.equals(currentCleanSsid, ignoreCase = true) }
                }
            }

            if (isTargetSsid) {
                if (settings.autoStartOnWifi && !isVpnRunning) {
                    if (android.net.VpnService.prepare(context) == null) {
                        lastActionTime = now
                        lastKnownSsid = currentCleanSsid ?: ssid
                        _lastAutomationStatus.value = "Connected to Wi-Fi (${currentCleanSsid ?: "Any Wi-Fi"}) -> Auto-started VPN"
                        logAutoEvent("AUTO", "Approved Wi-Fi active (${currentCleanSsid ?: "Any Wi-Fi"}). Auto-starting VPN…", "Approved Wi-Fi Connected")
                        V2RayVpnService.start(context, settings.wifiProfileId)
                    } else {
                        _lastAutomationStatus.value = "Wi-Fi connected (${currentCleanSsid ?: "Wi-Fi"}), tap Start in app once to grant VPN permission"
                    }
                } else if (isVpnRunning) {
                    lastKnownSsid = currentCleanSsid ?: ssid
                    _lastAutomationStatus.value = "VPN active on Wi-Fi (${currentCleanSsid ?: "Wi-Fi"})"
                }
            } else {
                // Connected to an unapproved Wi-Fi SSID when "Any SSID" is OFF:
                if (isVpnRunning && !settings.matchAnyWifiNetwork) {
                    lastActionTime = now
                    _lastAutomationStatus.value = "Unapproved Wi-Fi SSID (${currentCleanSsid ?: "Unknown"}) -> Stopped VPN"
                    logAutoEvent("AUTO", "Wi-Fi SSID (${currentCleanSsid ?: "Unknown"}) does not match approved target. Stopping VPN.", "SSID Mismatch")
                    V2RayVpnService.stop(context, "Unapproved Wi-Fi SSID")
                } else {
                    _lastAutomationStatus.value = "Wi-Fi (${currentCleanSsid ?: "Unknown"}) does not match target SSID"
                }
            }
        } else if (!hasWifi && (lastKnownSsid != null || isVpnRunning)) {
            // Disconnected from Wi-Fi
            val leftSsid = lastKnownSsid ?: "Wi-Fi"
            lastKnownSsid = null

            if (isVpnRunning) {
                if (!hasCellular) {
                    // Mobile Data is OFF and Wi-Fi is lost -> immediately stop VPN!
                    lastActionTime = now
                    _lastAutomationStatus.value = "Left Wi-Fi (Mobile Data OFF) -> Stopped VPN"
                    logAutoEvent("AUTO", "Disconnected from Wi-Fi and Mobile Data is OFF. Immediately stopping VPN.", "Network Lost")
                    V2RayVpnService.stop(context, "Network lost (No Mobile Data)")
                } else if (settings.autoStopOnWifiLeave) {
                    when (settings.wifiLeavePolicy) {
                        WifiLeavePolicy.STOP_VPN -> {
                            lastActionTime = now
                            _lastAutomationStatus.value = "Left Wi-Fi ($leftSsid) -> Stopped VPN"
                            logAutoEvent("AUTO", "Disconnected from Wi-Fi ($leftSsid). Stopping VPN per policy.", "Left target Wi-Fi")
                            V2RayVpnService.stop(context, "Left target Wi-Fi")
                        }
                        WifiLeavePolicy.SWITCH_TO_MOBILE -> {
                            if (settings.autoStartOnMobileData) {
                                lastActionTime = now
                                _lastAutomationStatus.value = "Left Wi-Fi ($leftSsid) -> Switched to Mobile Data"
                                logAutoEvent("AUTO", "Switched from Wi-Fi to Mobile Data. Starting with mobile profile.", "Mobile Data Switch")
                                V2RayVpnService.start(context, settings.mobileProfileId)
                            } else {
                                lastActionTime = now
                                _lastAutomationStatus.value = "Left Wi-Fi ($leftSsid) -> Stopped VPN"
                                V2RayVpnService.stop(context, "Left target Wi-Fi")
                            }
                        }
                        WifiLeavePolicy.KEEP_VPN -> {
                            _lastAutomationStatus.value = "Left Wi-Fi -> VPN kept running on Mobile Data"
                        }
                    }
                }
            }
        } else if (hasCellular && !hasWifi) {
            // Mobile data only
            if (settings.autoStartOnMobileData && !isVpnRunning) {
                lastActionTime = now
                _lastAutomationStatus.value = "Mobile Data active -> Started VPN"
                logAutoEvent("AUTO", "Mobile data connected. Auto-starting VPN…", "Mobile Data Connected")
                V2RayVpnService.start(context, settings.mobileProfileId)
            } else if (isVpnRunning) {
                _lastAutomationStatus.value = "VPN active on Mobile Data"
            }
        }
    }

    private fun detectCurrentWifiSsid(caps: NetworkCapabilities?): String? {
        val app = V2RayApplication.get()
        val settings = app.preferencesRepository.getAutomationSettings()

        if (!settings.wifiSsidPermissionEnabled) {
            return if (_isWifiConnected.value) "Active Wi-Fi" else null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
            val transportInfo = caps.transportInfo
            if (transportInfo is WifiInfo) {
                val clean = cleanSsidString(transportInfo.ssid)
                if (clean != null) return clean
            }
        }

        val hasLocationPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPerm && wifiManager != null) {
            val connectionInfo = wifiManager.connectionInfo
            if (connectionInfo != null) {
                val clean = cleanSsidString(connectionInfo.ssid)
                if (clean != null) return clean
            }
        }

        return if (_isWifiConnected.value) "Wi-Fi Network" else null
    }

    fun cleanSsidString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "<unknown ssid>" || raw == "\"<unknown ssid>\"") {
            return null
        }
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
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
}
