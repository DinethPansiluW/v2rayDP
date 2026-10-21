package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.V2RayApplication
import com.example.service.V2RayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = V2RayApplication.get()
        val settings = app.preferencesRepository.getAutomationSettings()
        val vpnRunning = V2RayVpnService.serviceState.value.isRunning

        // BOOT_COMPLETED and MY_PACKAGE_REPLACED: only call applyMonitoringPolicy()
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            app.automationManager.applyMonitoringPolicy(settings, vpnRunning)
            return
        }

        // If the master switch is off or policy says "not needed", return immediately
        val wifiRuleOn = settings.autoStartOnWifi
        val mobileRuleOn = settings.autoStartOnMobileData
        val backgroundNeeded = settings.isAutomationGloballyEnabled && settings.backgroundRunEnabled && (wifiRuleOn || mobileRuleOn)
        val stopRulesNeeded = settings.isAutomationGloballyEnabled && vpnRunning &&
                (settings.autoStopOnWifiLeave || settings.autoStopOnWifiOff || settings.autoStopOnMobileDataDisabled)

        if (!settings.isAutomationGloballyEnabled || (!backgroundNeeded && !stopRulesNeeded)) {
            return
        }

        if (action == android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION) {
            val state = intent.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN)
            app.automationManager.onWifiStateChanged(state)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(10000L) {
                    if (action == ACTION_CONNECTIVITY_CALLBACK) {
                        app.automationManager.evaluateNow("system_pending_intent_callback")
                    } else if (action == android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION) {
                        app.automationManager.evaluateNow("receiver_wifi_state_changed")
                    } else {
                        app.automationManager.evaluateNow("receiver_$action")
                    }
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONNECTIVITY_CALLBACK = "com.example.v2raydp.ACTION_CONNECTIVITY_CALLBACK"
        const val REQUEST_CODE_NETWORK_CALLBACK = 9001
    }
}
