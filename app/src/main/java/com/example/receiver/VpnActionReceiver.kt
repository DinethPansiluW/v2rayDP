package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.V2RayApplication
import com.example.model.AppLog
import com.example.model.ConnectionState
import com.example.service.V2RayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VpnActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val profileId = intent.getLongExtra("profile_id", -1L)

        CoroutineScope(Dispatchers.IO).launch {
            val app = V2RayApplication.get()
            when (action) {
                ACTION_START_VPN -> {
                    app.database.appLogDao().insertLog(
                        AppLog(
                            tag = "SamsungRoutines",
                            message = "External intent ACTION_START_VPN received",
                            level = "INFO",
                            triggerReason = "Samsung Modes & Routines / Shortcut"
                        )
                    )
                    V2RayVpnService.start(context, if (profileId > 0) profileId else null)
                }
                ACTION_STOP_VPN -> {
                    app.database.appLogDao().insertLog(
                        AppLog(
                            tag = "SamsungRoutines",
                            message = "External intent ACTION_STOP_VPN received",
                            level = "INFO",
                            triggerReason = "Samsung Modes & Routines / Shortcut"
                        )
                    )
                    // Set manual override so automation doesn't immediately restart
                    app.preferencesRepository.setManualOverride(true, "Stopped via Samsung Routine / Shortcut")
                    V2RayVpnService.stop(context, "Samsung Modes & Routines")
                }
                ACTION_TOGGLE_VPN -> {
                    val isRunning = V2RayVpnService.serviceState.value.isRunning
                    if (isRunning) {
                        V2RayVpnService.stop(context, "Toggled from Routine")
                    } else {
                        V2RayVpnService.start(context)
                    }
                }
                ACTION_SWITCH_PROFILE -> {
                    if (profileId > 0) {
                        app.database.serverProfileDao().setSelected(profileId)
                        val isRunning = V2RayVpnService.serviceState.value.isRunning
                        if (isRunning) {
                            // Restart with new profile
                            V2RayVpnService.start(context, profileId)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_START_VPN = "com.example.v2raydp.ACTION_START_VPN"
        const val ACTION_STOP_VPN = "com.example.v2raydp.ACTION_STOP_VPN"
        const val ACTION_TOGGLE_VPN = "com.example.v2raydp.ACTION_TOGGLE_VPN"
        const val ACTION_SWITCH_PROFILE = "com.example.v2raydp.ACTION_SWITCH_PROFILE"
    }
}
