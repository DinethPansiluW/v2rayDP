package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.AutomationSettings
import com.example.model.WifiLeavePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("v2raydp_prefs", Context.MODE_PRIVATE)

    private val _automationSettings: MutableStateFlow<AutomationSettings>

    init {
        if (!prefs.getBoolean("automation_defaults_v2", false)) {
            prefs.edit()
                .putBoolean(KEY_AUTO_ENABLED, false)
                .putBoolean(KEY_BACKGROUND_RUN, false)
                .putBoolean(KEY_START_ON_LAUNCH, false)
                .putBoolean("automation_defaults_v2", true)
                .apply()
        }
        _automationSettings = MutableStateFlow(loadAutomationSettings())
    }

    val automationSettings: StateFlow<AutomationSettings> = _automationSettings.asStateFlow()

    fun getAutomationSettings(): AutomationSettings = _automationSettings.value

    fun updateAutomationSettings(newSettings: AutomationSettings) {
        prefs.edit().apply {
            putBoolean(KEY_AUTO_ENABLED, newSettings.isAutomationGloballyEnabled)
            putBoolean(KEY_START_ON_LAUNCH, newSettings.startOnLaunch)
            putBoolean(KEY_BACKGROUND_RUN, newSettings.backgroundRunEnabled)
            putBoolean(KEY_BACKGROUND_START_BLOCKED, newSettings.backgroundStartBlocked)
            putBoolean(KEY_WIFI_AUTO_START, newSettings.autoStartOnWifi)
            putBoolean(KEY_WIFI_MATCH_ANY, newSettings.matchAnyWifiNetwork)
            putString(KEY_WIFI_SPECIFIC_SSID, newSettings.specificSsid)
            putStringSet(KEY_WIFI_TARGET_SSIDS, newSettings.targetWifiSsids)
            putLong(KEY_WIFI_PROFILE_ID, newSettings.wifiProfileId ?: -1L)
            putBoolean(KEY_WIFI_AUTO_STOP_LEAVE, newSettings.autoStopOnWifiLeave)
            putString(KEY_WIFI_LEAVE_POLICY, newSettings.wifiLeavePolicy.name)
            putBoolean(KEY_WIFI_AUTO_STOP_OFF, newSettings.autoStopOnWifiOff)
            putBoolean(KEY_WIFI_AUTO_RECONNECT, newSettings.autoReconnectOnWifiReturn)

            putBoolean(KEY_MOBILE_AUTO_START, newSettings.autoStartOnMobileData)
            putBoolean(KEY_MOBILE_AUTO_STOP, newSettings.autoStopOnMobileDataDisabled)
            putLong(KEY_MOBILE_PROFILE_ID, newSettings.mobileProfileId ?: -1L)

            putLong(KEY_DEFAULT_PROFILE_ID, newSettings.defaultProfileId ?: -1L)

            putBoolean(KEY_MANUAL_OVERRIDE, newSettings.manualOverrideSuppressed)
            putString(KEY_MANUAL_OVERRIDE_REASON, newSettings.manualOverrideReason)
            putString(KEY_MANUAL_OVERRIDE_NETWORK_KEY, newSettings.manualOverrideNetworkKey)

            putBoolean(KEY_STOPPED_BY_POLICY_MOBILE, newSettings.stoppedByPolicyMobileSession)
            putString(KEY_STOPPED_BY_LEAVE_WIFI_SSID, newSettings.stoppedByLeaveWifiSsid)

            putBoolean(KEY_HOTSPOT_ENABLED, newSettings.isHotspotSharingEnabled)
            putInt(KEY_HOTSPOT_PORT, newSettings.hotspotProxyPort)
            putBoolean(KEY_HOTSPOT_ROOT, newSettings.isRootHotspotRoutingEnabled)
            apply()
        }
        _automationSettings.value = newSettings
    }

    fun setManualOverride(source: String, networkKey: String?) {
        val current = _automationSettings.value
        val updated = current.copy(
            manualOverrideSuppressed = true,
            manualOverrideReason = source,
            manualOverrideNetworkKey = networkKey
        )
        updateAutomationSettings(updated)
    }

    fun clearManualOverride() {
        val current = _automationSettings.value
        val updated = current.copy(
            manualOverrideSuppressed = false,
            manualOverrideReason = "",
            manualOverrideNetworkKey = null,
            stoppedByPolicyMobileSession = false
        )
        updateAutomationSettings(updated)
    }

    fun setStoppedByPolicy(mobileSession: Boolean, wifiSsid: String?) {
        val current = _automationSettings.value
        val updated = current.copy(
            stoppedByPolicyMobileSession = mobileSession,
            stoppedByLeaveWifiSsid = wifiSsid ?: current.stoppedByLeaveWifiSsid
        )
        updateAutomationSettings(updated)
    }

    fun clearStoppedByPolicy() {
        val current = _automationSettings.value
        if (current.stoppedByPolicyMobileSession) {
            updateAutomationSettings(current.copy(stoppedByPolicyMobileSession = false))
        }
    }

    fun clearStoppedByLeaveWifiSsid() {
        val current = _automationSettings.value
        if (current.stoppedByLeaveWifiSsid != null) {
            updateAutomationSettings(current.copy(stoppedByLeaveWifiSsid = null))
        }
    }

    private fun loadAutomationSettings(): AutomationSettings {
        val policyStr = prefs.getString(KEY_WIFI_LEAVE_POLICY, WifiLeavePolicy.STOP_VPN.name) ?: WifiLeavePolicy.STOP_VPN.name
        val policy = runCatching { WifiLeavePolicy.valueOf(policyStr) }.getOrDefault(WifiLeavePolicy.STOP_VPN)

        val wifiProfId = prefs.getLong(KEY_WIFI_PROFILE_ID, -1L)
        val mobileProfId = prefs.getLong(KEY_MOBILE_PROFILE_ID, -1L)
        val defaultProfId = prefs.getLong(KEY_DEFAULT_PROFILE_ID, -1L)

        return AutomationSettings(
            isAutomationGloballyEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false),
            startOnLaunch = prefs.getBoolean(KEY_START_ON_LAUNCH, false),
            backgroundRunEnabled = prefs.getBoolean(KEY_BACKGROUND_RUN, false),
            backgroundStartBlocked = prefs.getBoolean(KEY_BACKGROUND_START_BLOCKED, false),
            autoStartOnWifi = prefs.getBoolean(KEY_WIFI_AUTO_START, true),
            matchAnyWifiNetwork = prefs.getBoolean(KEY_WIFI_MATCH_ANY, true),
            specificSsid = prefs.getString(KEY_WIFI_SPECIFIC_SSID, "") ?: "",
            targetWifiSsids = prefs.getStringSet(KEY_WIFI_TARGET_SSIDS, emptySet()) ?: emptySet(),
            wifiProfileId = if (wifiProfId > 0) wifiProfId else null,
            autoStopOnWifiLeave = prefs.getBoolean(KEY_WIFI_AUTO_STOP_LEAVE, true),
            wifiLeavePolicy = policy,
            autoStopOnWifiOff = prefs.getBoolean(KEY_WIFI_AUTO_STOP_OFF, true),
            autoReconnectOnWifiReturn = prefs.getBoolean(KEY_WIFI_AUTO_RECONNECT, true),

            autoStartOnMobileData = prefs.getBoolean(KEY_MOBILE_AUTO_START, false),
            autoStopOnMobileDataDisabled = prefs.getBoolean(KEY_MOBILE_AUTO_STOP, true),
            mobileProfileId = if (mobileProfId > 0) mobileProfId else null,

            defaultProfileId = if (defaultProfId > 0) defaultProfId else null,

            manualOverrideSuppressed = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false),
            manualOverrideReason = prefs.getString(KEY_MANUAL_OVERRIDE_REASON, "") ?: "",
            manualOverrideNetworkKey = prefs.getString(KEY_MANUAL_OVERRIDE_NETWORK_KEY, null),

            stoppedByPolicyMobileSession = prefs.getBoolean(KEY_STOPPED_BY_POLICY_MOBILE, false),
            stoppedByLeaveWifiSsid = prefs.getString(KEY_STOPPED_BY_LEAVE_WIFI_SSID, null),

            isHotspotSharingEnabled = prefs.getBoolean(KEY_HOTSPOT_ENABLED, false),
            hotspotProxyPort = prefs.getInt(KEY_HOTSPOT_PORT, 10808),
            isRootHotspotRoutingEnabled = prefs.getBoolean(KEY_HOTSPOT_ROOT, false)
        )
    }

    // App Preferences (DNS, Sniffing, Bypass LAN)
    var isBypassLan: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_LAN, true)
        set(value) = prefs.edit().putBoolean(KEY_BYPASS_LAN, value).apply()

    var primaryDns: String
        get() = prefs.getString(KEY_PRIMARY_DNS, "1.1.1.1") ?: "1.1.1.1"
        set(value) = prefs.edit().putString(KEY_PRIMARY_DNS, value).apply()

    var secondaryDns: String
        get() = prefs.getString(KEY_SECONDARY_DNS, "8.8.8.8") ?: "8.8.8.8"
        set(value) = prefs.edit().putString(KEY_SECONDARY_DNS, value).apply()

    // Domain Blocking Preferences (Feature 2)
    var isBlockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, value).apply()

    var isBlockDohProviders: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_DOH_PROVIDERS, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_DOH_PROVIDERS, value).apply()

    var isAutoUpdateWeekly: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_WEEKLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_WEEKLY, value).apply()

    var blockedTotalCount: Long
        get() = prefs.getLong(KEY_BLOCKED_TOTAL_COUNT, 0L)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_TOTAL_COUNT, value).apply()

    fun getPresetEnabled(id: String, default: Boolean = true): Boolean {
        return prefs.getBoolean("preset_enabled_$id", default)
    }

    fun setPresetEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("preset_enabled_$id", enabled).apply()
    }

    fun getPresetLastUpdated(id: String): Long {
        return prefs.getLong("preset_updated_$id", 0L)
    }

    fun setPresetLastUpdated(id: String, timestamp: Long) {
        prefs.edit().putLong("preset_updated_$id", timestamp).apply()
    }

    fun getPresetEntryCount(id: String): Int {
        return prefs.getInt("preset_count_$id", 0)
    }

    fun setPresetEntryCount(id: String, count: Int) {
        prefs.edit().putInt("preset_count_$id", count).apply()
    }

    var backgroundStartBlocked: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_START_BLOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BACKGROUND_START_BLOCKED, value).apply()
            _automationSettings.value = _automationSettings.value.copy(backgroundStartBlocked = value)
        }

    var isBlockingStrictMode: Boolean
        get() = prefs.getBoolean(KEY_BLOCKING_STRICT_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKING_STRICT_MODE, value).apply()

    fun resetBlockedTotalCount() {
        blockedTotalCount = 0L
    }

    companion object {
        private const val KEY_AUTO_ENABLED = "auto_globally_enabled"
        private const val KEY_START_ON_LAUNCH = "start_on_launch"
        private const val KEY_BACKGROUND_RUN = "background_run_enabled"
        private const val KEY_BACKGROUND_START_BLOCKED = "background_start_blocked"
        private const val KEY_WIFI_AUTO_START = "wifi_auto_start"
        private const val KEY_WIFI_MATCH_ANY = "wifi_match_any"
        private const val KEY_WIFI_SPECIFIC_SSID = "wifi_specific_ssid"
        private const val KEY_WIFI_TARGET_SSIDS = "wifi_target_ssids"
        private const val KEY_WIFI_PROFILE_ID = "wifi_profile_id"
        private const val KEY_WIFI_AUTO_STOP_LEAVE = "wifi_auto_stop_leave"
        private const val KEY_WIFI_LEAVE_POLICY = "wifi_leave_policy"
        private const val KEY_WIFI_AUTO_STOP_OFF = "wifi_auto_stop_off"
        private const val KEY_WIFI_AUTO_RECONNECT = "wifi_auto_reconnect"

        private const val KEY_MOBILE_AUTO_START = "mobile_auto_start"
        private const val KEY_MOBILE_AUTO_STOP = "mobile_auto_stop"
        private const val KEY_MOBILE_PROFILE_ID = "mobile_profile_id"

        private const val KEY_DEFAULT_PROFILE_ID = "default_profile_id"

        private const val KEY_MANUAL_OVERRIDE = "manual_override_suppressed"
        private const val KEY_MANUAL_OVERRIDE_REASON = "manual_override_reason"
        private const val KEY_MANUAL_OVERRIDE_NETWORK_KEY = "manual_override_network_key"

        private const val KEY_STOPPED_BY_POLICY_MOBILE = "stopped_by_policy_mobile_session"
        private const val KEY_STOPPED_BY_LEAVE_WIFI_SSID = "stopped_by_leave_wifi_ssid"

        private const val KEY_HOTSPOT_ENABLED = "hotspot_sharing_enabled"
        private const val KEY_HOTSPOT_PORT = "hotspot_proxy_port"
        private const val KEY_HOTSPOT_ROOT = "hotspot_root_routing"

        private const val KEY_BYPASS_LAN = "bypass_lan"
        private const val KEY_PRIMARY_DNS = "primary_dns"
        private const val KEY_SECONDARY_DNS = "secondary_dns"

        private const val KEY_BLOCKING_ENABLED = "domain_blocking_enabled"
        private const val KEY_BLOCKING_STRICT_MODE = "blocking_strict_mode"
        private const val KEY_BLOCK_DOH_PROVIDERS = "block_doh_providers"
        private const val KEY_AUTO_UPDATE_WEEKLY = "auto_update_weekly"
        private const val KEY_BLOCKED_TOTAL_COUNT = "blocked_total_count"
    }
}
