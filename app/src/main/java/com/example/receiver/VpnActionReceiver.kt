package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.V2RayApplication
import com.example.core.V2RayCoreManager
import com.example.model.AppLog
import com.example.service.V2RayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VpnActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val profileId = intent.getLongExtra("profile_id", -1L)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = V2RayApplication.get()
                val currentState = V2RayCoreManager.currentConnectionState.value

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
                        app.automationManager.onUserExplicitConnect()
                        V2RayVpnService.start(context, if (profileId > 0) profileId else null, isAutomated = false)
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
                        app.automationManager.onUserExplicitDisconnect()
                        V2RayVpnService.stop(context, "Samsung Modes & Routines / Shortcut", isAutomated = false)
                    }
                    ACTION_TOGGLE_VPN -> {
                        if (currentState.isRunning) {
                            app.automationManager.onUserExplicitDisconnect()
                            V2RayVpnService.stop(context, "Toggled from Routine", isAutomated = false)
                        } else {
                            app.automationManager.onUserExplicitConnect()
                            V2RayVpnService.start(context, if (profileId > 0) profileId else null, isAutomated = false)
                        }
                    }
                    ACTION_SWITCH_PROFILE -> {
                        if (profileId > 0) {
                            app.database.serverProfileDao().setSelected(profileId)
                            if (currentState.isRunning) {
                                V2RayVpnService.start(context, profileId, isAutomated = false)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
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
