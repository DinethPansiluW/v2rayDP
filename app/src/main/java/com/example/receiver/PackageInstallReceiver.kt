package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.V2RayApplication

class PackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (isReplacing) return

            val packageName = intent.data?.schemeSpecificPart ?: return
            Log.d(TAG, "New package installed: $packageName")

            val app = V2RayApplication.get()
            val prefs = app.preferencesRepository
            if (prefs.isAutoBypassNewApps) {
                val current = prefs.bypassedPackages.toMutableSet()
                if (current.add(packageName)) {
                    prefs.bypassedPackages = current
                    Log.i(TAG, "Automatically added $packageName to bypassed packages list")
                }
            }
        }
    }

    companion object {
        private const val TAG = "PackageInstallReceiver"
    }
}
