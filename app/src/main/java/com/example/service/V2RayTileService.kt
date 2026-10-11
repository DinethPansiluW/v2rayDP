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
        if (currentState.isRunning) {
            V2RayVpnService.stop(this, "Quick Settings Tile")
        } else {
            // Check if VPN consent is already prepared
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                // Requires user consent in UI
                val appIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("action_trigger_vpn", true)
                }
                startActivityAndCollapse(appIntent)
            } else {
                V2RayVpnService.start(this)
            }
        }
    }

    private fun updateTileState(state: ConnectionState) {
        val tile = qsTile ?: return
        when (state) {
            is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "v2rayDP"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = state.profile.name
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is ConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Connecting…"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = state.profileName
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "v2rayDP"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Disconnected"
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
        }
        tile.updateTile()
    }
}
