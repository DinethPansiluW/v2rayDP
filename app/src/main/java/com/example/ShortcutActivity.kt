package com.example

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.core.V2RayCoreManager
import com.example.model.ConnectionState
import com.example.receiver.VpnActionReceiver
import com.example.service.V2RayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ShortcutActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            executeStartVpn()
        }
        finishWithNoTransition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        val action = intent?.action
        if (action == null) {
            finishWithNoTransition()
            return
        }

        when (action) {
            VpnActionReceiver.ACTION_START_VPN -> {
                handleStartVpn()
            }
            VpnActionReceiver.ACTION_STOP_VPN -> {
                handleStopVpn()
            }
            VpnActionReceiver.ACTION_SWITCH_PROFILE -> {
                handleSwitchProfile()
            }
            else -> {
                finishWithNoTransition()
            }
        }
    }

    private fun handleStartVpn() {
        val app = application as? V2RayApplication ?: V2RayApplication.get()
        val dao = app.database.serverProfileDao()
        val profiles = runBlocking(Dispatchers.IO) { dao.getAllProfiles() }

        if (profiles.isEmpty()) {
            Toast.makeText(applicationContext, "No profile selected", Toast.LENGTH_SHORT).show()
            finishWithNoTransition()
            return
        }

        val currentState = V2RayCoreManager.currentConnectionState.value
        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            // Start while Connecting/Connected = ignored
            finishWithNoTransition()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            executeStartVpn()
            finishWithNoTransition()
        } else {
            // Need user consent dialog
            vpnPrepareLauncher.launch(prepareIntent)
        }
    }

    private fun executeStartVpn() {
        val app = application as? V2RayApplication ?: V2RayApplication.get()
        val dao = app.database.serverProfileDao()
        val profiles = runBlocking(Dispatchers.IO) { dao.getAllProfiles() }

        if (profiles.isEmpty()) {
            Toast.makeText(applicationContext, "No profile selected", Toast.LENGTH_SHORT).show()
            return
        }

        val currentState = V2RayVpnService.serviceState.value
        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            return
        }

        app.preferencesRepository.clearManualOverride()
        Toast.makeText(applicationContext, "VPN starting…", Toast.LENGTH_SHORT).show()
        V2RayVpnService.start(applicationContext, null, isAutomated = false)
    }

    private fun handleStopVpn() {
        val app = application as? V2RayApplication ?: V2RayApplication.get()
        val currentState = V2RayVpnService.serviceState.value
        if (currentState is ConnectionState.Disconnected || currentState is ConnectionState.Error) {
            // Stop while already stopped = ignored
            finishWithNoTransition()
            return
        }

        val netKey = app.automationManager.getCurrentNetworkKey()
        app.preferencesRepository.setManualOverride("Launcher shortcut", netKey)
        app.preferencesRepository.clearStoppedByPolicy()
        Toast.makeText(applicationContext, "VPN stopping…", Toast.LENGTH_SHORT).show()
        V2RayVpnService.stop(applicationContext, "Launcher shortcut", isAutomated = false)
        finishWithNoTransition()
    }

    private fun handleSwitchProfile() {
        val app = application as? V2RayApplication ?: V2RayApplication.get()
        val dao = app.database.serverProfileDao()
        val profiles = runBlocking(Dispatchers.IO) { dao.getAllProfiles() }

        if (profiles.isEmpty()) {
            Toast.makeText(applicationContext, "No profile selected", Toast.LENGTH_SHORT).show()
            finishWithNoTransition()
            return
        }

        if (profiles.size == 1) {
            Toast.makeText(applicationContext, "Only one profile", Toast.LENGTH_SHORT).show()
            finishWithNoTransition()
            return
        }

        val currentIndex = profiles.indexOfFirst { it.isSelected }
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % profiles.size
        val nextProfile = profiles[nextIndex]

        runBlocking(Dispatchers.IO) {
            dao.setSelected(nextProfile.id)
        }

        val currentState = V2RayCoreManager.currentConnectionState.value
        if (currentState.isRunning) {
            V2RayVpnService.start(applicationContext, nextProfile.id, isAutomated = false)
        }

        Toast.makeText(applicationContext, "Profile: ${nextProfile.name}", Toast.LENGTH_SHORT).show()
        finishWithNoTransition()
    }

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun finishWithNoTransition() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
