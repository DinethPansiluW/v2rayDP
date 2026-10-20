package com.example.model

/**
 * Policy defining action when device disconnects from target Wi-Fi:
 * - STOP_VPN: Stop and stay off on mobile data (suppresses mobile auto-start for current session).
 * - SWITCH_TO_MOBILE: Restart VPN immediately with the configured mobile data profile.
 * - KEEP_VPN: Do nothing, keep running seamlessly without interruption.
 */
enum class WifiLeavePolicy(val displayName: String, val description: String) {
    STOP_VPN("Stop VPN service", "Stop and stay off on mobile data"),
    SWITCH_TO_MOBILE("Switch to Mobile Data rule", "Restart with mobile profile"),
    KEEP_VPN("Keep VPN running", "Keep running seamlessly without interruption")
}

data class AutomationSettings(
    val isAutomationGloballyEnabled: Boolean = false,

    // Service Automation
    val startOnLaunch: Boolean = false,
    val backgroundRunEnabled: Boolean = false,
    val backgroundStartBlocked: Boolean = false,

    // Wi-Fi rules
    val autoStartOnWifi: Boolean = true,
    val matchAnyWifiNetwork: Boolean = true, // When true ("Any SSID"), connects on any Wi-Fi without SSID match
    val specificSsid: String = "", // User-defined target SSID when "Any SSID" is turned OFF
    val targetWifiSsids: Set<String> = emptySet(),
    val wifiProfileId: Long? = null,
    val autoStopOnWifiLeave: Boolean = true,
    val wifiLeavePolicy: WifiLeavePolicy = WifiLeavePolicy.STOP_VPN,
    val autoStopOnWifiOff: Boolean = true,
    val autoReconnectOnWifiReturn: Boolean = true,

    // Mobile Data rules
    val autoStartOnMobileData: Boolean = false,
    val autoStopOnMobileDataDisabled: Boolean = true, // When all networks lost: true = stop after grace, false = do not stop
    val mobileProfileId: Long? = null,

    // Default Profile Resolution
    val defaultProfileId: Long? = null,

    // Manual override suppression (persisted, network-scoped)
    val manualOverrideSuppressed: Boolean = false,
    val manualOverrideReason: String = "",
    val manualOverrideNetworkKey: String? = null,

    // Policy conflict resolution:
    val stoppedByPolicyMobileSession: Boolean = false, // When STOP_VPN triggered, stay off until network switches
    val stoppedByLeaveWifiSsid: String? = null, // Stores SSID for autoReconnectOnWifiReturn

    // Hotspot sharing
    val isHotspotSharingEnabled: Boolean = false,
    val hotspotProxyPort: Int = 10808,
    val isRootHotspotRoutingEnabled: Boolean = false
)
