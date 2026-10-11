package com.example.model

enum class WifiLeavePolicy(val displayName: String) {
    STOP_VPN("Stop VPN service"),
    SWITCH_TO_MOBILE("Switch to Mobile Data rule"),
    KEEP_VPN("Keep VPN running")
}

data class AutomationSettings(
    val isAutomationGloballyEnabled: Boolean = true,
    // Wi-Fi rules
    val autoStartOnWifi: Boolean = true,
    val matchAnyWifiNetwork: Boolean = true, // When true, any active Wi-Fi connects without requiring location permission
    val targetWifiSsids: Set<String> = emptySet(),
    val wifiProfileId: Long? = null,
    val autoStopOnWifiLeave: Boolean = true,
    val wifiLeavePolicy: WifiLeavePolicy = WifiLeavePolicy.STOP_VPN,
    val autoStopOnWifiOff: Boolean = true,
    val autoReconnectOnWifiReturn: Boolean = true,

    // Mobile Data rules
    val autoStartOnMobileData: Boolean = false,
    val autoStopOnMobileDataDisabled: Boolean = false,
    val mobileProfileId: Long? = null,

    // Manual override suppression
    val manualOverrideSuppressed: Boolean = false,
    val manualOverrideReason: String = "",

    // Hotspot sharing
    val isHotspotSharingEnabled: Boolean = false,
    val hotspotProxyPort: Int = 10808,
    val isRootHotspotRoutingEnabled: Boolean = false
)
