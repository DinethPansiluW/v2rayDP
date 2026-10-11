package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.example.V2RayApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = V2RayApplication.get()

        when (action) {
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                app.automationManager.onWifiStateChanged(wifiState)
            }
            "android.net.conn.CONNECTIVITY_CHANGE",
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                CoroutineScope(Dispatchers.IO).launch {
                    app.automationManager.evaluateNetworkState()
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                CoroutineScope(Dispatchers.IO).launch {
                    app.automationManager.evaluateNetworkState()
                }
            }
        }
    }
}
