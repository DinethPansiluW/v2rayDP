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

    private val _automationSettings = MutableStateFlow(loadAutomationSettings())
    val automationSettings: StateFlow<AutomationSettings> = _automationSettings.asStateFlow()

    fun getAutomationSettings(): AutomationSettings = _automationSettings.value

    fun updateAutomationSettings(newSettings: AutomationSettings) {
        prefs.edit().apply {
            putBoolean(KEY_AUTO_ENABLED, newSettings.isAutomationGloballyEnabled)
            putBoolean(KEY_START_ON_LAUNCH, newSettings.startOnLaunch)
            putBoolean(KEY_BACKGROUND_RUN, newSettings.backgroundRunEnabled)
            putBoolean(KEY_WIFI_SSID_PERMISSION, newSettings.wifiSsidPermissionEnabled)
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

            putBoolean(KEY_MANUAL_OVERRIDE, newSettings.manualOverrideSuppressed)
            putString(KEY_MANUAL_OVERRIDE_REASON, newSettings.manualOverrideReason)

            putBoolean(KEY_HOTSPOT_ENABLED, newSettings.isHotspotSharingEnabled)
            putInt(KEY_HOTSPOT_PORT, newSettings.hotspotProxyPort)
            putBoolean(KEY_HOTSPOT_ROOT, newSettings.isRootHotspotRoutingEnabled)
            apply()
        }
        _automationSettings.value = newSettings
    }

    fun setManualOverride(suppressed: Boolean, reason: String = "") {
        val current = _automationSettings.value
        val updated = current.copy(
            manualOverrideSuppressed = suppressed,
            manualOverrideReason = reason
        )
        updateAutomationSettings(updated)
    }

    private fun loadAutomationSettings(): AutomationSettings {
        val policyStr = prefs.getString(KEY_WIFI_LEAVE_POLICY, WifiLeavePolicy.STOP_VPN.name) ?: WifiLeavePolicy.STOP_VPN.name
        val policy = runCatching { WifiLeavePolicy.valueOf(policyStr) }.getOrDefault(WifiLeavePolicy.STOP_VPN)

        val wifiProfId = prefs.getLong(KEY_WIFI_PROFILE_ID, -1L)
        val mobileProfId = prefs.getLong(KEY_MOBILE_PROFILE_ID, -1L)

        return AutomationSettings(
            isAutomationGloballyEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, true),
            startOnLaunch = prefs.getBoolean(KEY_START_ON_LAUNCH, false),
            backgroundRunEnabled = prefs.getBoolean(KEY_BACKGROUND_RUN, true),
            wifiSsidPermissionEnabled = prefs.getBoolean(KEY_WIFI_SSID_PERMISSION, true),
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

            manualOverrideSuppressed = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false),
            manualOverrideReason = prefs.getString(KEY_MANUAL_OVERRIDE_REASON, "") ?: "",

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

    companion object {
        private const val KEY_AUTO_ENABLED = "auto_globally_enabled"
        private const val KEY_START_ON_LAUNCH = "start_on_launch"
        private const val KEY_BACKGROUND_RUN = "background_run_enabled"
        private const val KEY_WIFI_SSID_PERMISSION = "wifi_ssid_permission_enabled"
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

        private const val KEY_MANUAL_OVERRIDE = "manual_override_suppressed"
        private const val KEY_MANUAL_OVERRIDE_REASON = "manual_override_reason"

        private const val KEY_HOTSPOT_ENABLED = "hotspot_sharing_enabled"
        private const val KEY_HOTSPOT_PORT = "hotspot_proxy_port"
        private const val KEY_HOTSPOT_ROOT = "hotspot_root_routing"

        private const val KEY_BYPASS_LAN = "bypass_lan"
        private const val KEY_PRIMARY_DNS = "primary_dns"
        private const val KEY_SECONDARY_DNS = "secondary_dns"
    }
}
