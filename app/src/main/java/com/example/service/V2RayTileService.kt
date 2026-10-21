package com.example.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.MainActivity
import com.example.R
import com.example.V2RayApplication
import com.example.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class V2RayTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            V2RayVpnService.serviceState.collect { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentState = V2RayVpnService.serviceState.value
        val app = V2RayApplication.get()
        if (currentState.isRunning) {
            val netKey = app.automationManager.getCurrentNetworkKey()
            app.preferencesRepository.setManualOverride("Quick Settings Tile", netKey)
            app.preferencesRepository.clearStoppedByPolicy()
            V2RayVpnService.stop(this, "Quick Settings Tile", isAutomated = false)
        } else {
            app.preferencesRepository.clearManualOverride()
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                val appIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("action_trigger_vpn", true)
                }
                startActivityAndCollapse(appIntent)
            } else {
                V2RayVpnService.start(this, isAutomated = false)
            }
        }
    }

    private fun updateTileState(state: ConnectionState) {
        val tile = qsTile ?: return
        when (state) {
            is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "v2rayDP: ON"
                tile.subtitle = state.profile.name
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is ConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "v2rayDP"
                tile.subtitle = "Connecting…"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is ConnectionState.Stopping -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "v2rayDP"
                tile.subtitle = "Stopping…"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is ConnectionState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "v2rayDP"
                tile.subtitle = "Error"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is ConnectionState.Disconnected -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "v2rayDP: OFF"
                tile.subtitle = "Tap to connect"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        stateJob?.cancel()
        super.onDestroy()
    }
}
