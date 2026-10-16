package com.example.model

enum class WifiLeavePolicy(val displayName: String) {
    STOP_VPN("Stop VPN service"),
    SWITCH_TO_MOBILE("Switch to Mobile Data rule"),
    KEEP_VPN("Keep VPN running")
}

data class AutomationSettings(
    val isAutomationGloballyEnabled: Boolean = true,

    // Service Automation
    val startOnLaunch: Boolean = false,
    val backgroundRunEnabled: Boolean = true,

    // Wi-Fi rules
    val wifiSsidPermissionEnabled: Boolean = true,
    val autoStartOnWifi: Boolean = true,
    val matchAnyWifiNetwork: Boolean = true, // When true ("Any SSID"), any active Wi-Fi connects without requiring location/SSID match
    val specificSsid: String = "", // User-defined target SSID when "Any SSID" is turned OFF
    val targetWifiSsids: Set<String> = emptySet(),
    val wifiProfileId: Long? = null,
    val autoStopOnWifiLeave: Boolean = true,
    val wifiLeavePolicy: WifiLeavePolicy = WifiLeavePolicy.STOP_VPN,
    val autoStopOnWifiOff: Boolean = true,
    val autoReconnectOnWifiReturn: Boolean = true,

    // Mobile Data rules
    val autoStartOnMobileData: Boolean = false,
    val autoStopOnMobileDataDisabled: Boolean = true, // When Mobile Data is switched OFF or lost, auto-stop VPN immediately
    val mobileProfileId: Long? = null,

    // Default Profile Resolution
    val defaultProfileId: Long? = null,

    // Manual override suppression
    val manualOverrideSuppressed: Boolean = false,
    val manualOverrideReason: String = "",

    // Hotspot sharing
    val isHotspotSharingEnabled: Boolean = false,
    val hotspotProxyPort: Int = 10808,
    val isRootHotspotRoutingEnabled: Boolean = false
)
