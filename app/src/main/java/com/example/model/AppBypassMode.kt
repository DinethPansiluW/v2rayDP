package com.example.model

enum class AppBypassMode {
    BYPASS_SELECTED,             // "Bypass only these apps": selected apps skip VPN, everything else uses it
    BYPASS_ALL_EXCEPT_SELECTED   // "Bypass all except these apps": only selected apps use VPN, everything else skips it
}

data class AppBypassConfig(
    val enabled: Boolean = false,
    val mode: AppBypassMode = AppBypassMode.BYPASS_SELECTED,
    val selectedPackages: Set<String> = emptySet()
)
