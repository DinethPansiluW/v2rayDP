package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.V2RayApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8000L) {
                    val app = V2RayApplication.get()
                    when (action) {
                        Intent.ACTION_BOOT_COMPLETED -> {
                            app.automationManager.onBootCompleted()
                        }
                        ACTION_CONNECTIVITY_CALLBACK -> {
                            app.automationManager.evaluateNow("system_pending_intent_callback")
                        }
                        else -> {
                            app.automationManager.evaluateNow("receiver_$action")
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
        const val ACTION_CONNECTIVITY_CALLBACK = "com.example.v2raydp.ACTION_CONNECTIVITY_CALLBACK"
        const val REQUEST_CODE_NETWORK_CALLBACK = 9001
    }
}
