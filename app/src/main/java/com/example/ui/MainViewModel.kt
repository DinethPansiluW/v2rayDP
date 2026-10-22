package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.V2RayApplication
import com.example.core.ping.PingTester
import com.example.hotspot.HotspotSharingManager
import com.example.model.AppLog
import com.example.model.AutomationSettings
import com.example.model.ConnectionState
import com.example.model.ServerProfile
import com.example.model.TrafficStats
import com.example.model.VmessLinkParser
import com.example.service.V2RayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as V2RayApplication
    private val profileDao = app.database.serverProfileDao()
    private val logDao = app.database.appLogDao()
    private val prefsRepo = app.preferencesRepository
    val automationManager = app.automationManager

    // Server Profiles from Room
    val allProfiles: StateFlow<List<ServerProfile>> = profileDao.getAllProfilesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activeProfile: StateFlow<ServerProfile?> = profileDao.getActiveProfileFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // VPN Service State & Traffic
    val connectionState: StateFlow<ConnectionState> = com.example.core.V2RayCoreManager.currentConnectionState
    val trafficStats: StateFlow<TrafficStats> = V2RayVpnService.serviceTrafficStats

    // Automation Settings
    val automationSettings: StateFlow<AutomationSettings> = prefsRepo.automationSettings

    // Logs
    val recentLogs: StateFlow<List<AppLog>> = logDao.getRecentLogsFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // UI state for messages and active dialogs
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _isPingingAll = MutableStateFlow(false)
    val isPingingAll: StateFlow<Boolean> = _isPingingAll.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _hotspotIp = MutableStateFlow("192.168.43.1")
    val hotspotIp: StateFlow<String> = _hotspotIp.asStateFlow()

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(false)
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    init {
        checkRootAndHotspot()
        refreshPermissionsState()
    }

    fun refreshPermissionsState() {
        val context = getApplication<Application>()
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        _isIgnoringBatteryOptimizations.value = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        _hasLocationPermission.value = fine || coarse
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            showMessage("Battery optimizations are already unrestricted for v2rayDP")
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    showMessage("Battery settings: ${e.message}")
                }
            }
        }
    }

    private fun checkRootAndHotspot() {
        viewModelScope.launch {
            _isRootAvailable.value = HotspotSharingManager.checkRootAvailable()
            _hotspotIp.value = HotspotSharingManager.getHotspotGatewayIp()
        }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    fun showMessage(msg: String) {
        _userMessage.value = msg
    }

    fun startVpn(profileId: Long? = null) {
        viewModelScope.launch {
            // Clear manual override when user explicitly starts VPN
            prefsRepo.clearManualOverride()
            val id = profileId ?: activeProfile.value?.id
            V2RayVpnService.start(getApplication(), id, isAutomated = false)
        }
    }

    fun stopVpn(reason: String = "User pressed stop in app") {
        viewModelScope.launch {
            try {
                // Pause automation on this network until network actually switches
                val netKey = automationManager.getCurrentNetworkKey()
                prefsRepo.setManualOverride(reason, netKey)
                prefsRepo.clearStoppedByPolicy()
                V2RayVpnService.stop(getApplication(), reason, isAutomated = false)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error stopping VPN: ${e.message}")
            }
        }
    }

    fun selectProfile(profile: ServerProfile) {
        viewModelScope.launch {
            profileDao.setSelected(profile.id)
            if (connectionState.value.isRunning) {
                // Smoothly switch service with the newly selected profile without stopSelf / flicker
                V2RayVpnService.start(getApplication(), profile.id, isAutomated = false)
            }
        }
    }

    fun saveProfile(profile: ServerProfile) {
        viewModelScope.launch {
            if (profile.id == 0L) {
                val newId = profileDao.insertProfile(profile.copy(isSelected = allProfiles.value.isEmpty()))
                if (allProfiles.value.isEmpty()) {
                    profileDao.setSelected(newId)
                }
                showMessage("Server profile '${profile.name}' added")
            } else {
                profileDao.updateProfile(profile)
                showMessage("Server profile updated")
            }
        }
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch {
            val wasSelected = profile.isSelected
            profileDao.deleteProfile(profile)
            if (wasSelected) {
                val remaining = profileDao.getAllProfiles()
                if (remaining.isNotEmpty()) {
                    val next = remaining.first()
                    profileDao.setSelected(next.id)
                    if (connectionState.value.isRunning) {
                        V2RayVpnService.start(getApplication(), next.id, isAutomated = false)
                    }
                }
            }
        }
    }

    fun restoreProfile(profile: ServerProfile) {
        viewModelScope.launch {
            val newId = profileDao.insertProfile(profile)
            if (profile.isSelected) {
                profileDao.setSelected(newId)
                if (connectionState.value.isRunning) {
                    V2RayVpnService.start(getApplication(), newId, isAutomated = false)
                }
            }
        }
    }

    fun importVmessLink(link: String) {
        importMultipleLinks(link)
    }

    fun importMultipleLinks(linksText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val allLinks = linksText.lines().flatMap { VmessLinkParser.splitLinks(it) }
            if (allLinks.isEmpty()) {
                showMessage("No valid links found in input")
                return@launch
            }

            val existing = profileDao.getAllProfiles()
            var importedCount = 0
            var duplicateCount = 0
            val toInsert = mutableListOf<ServerProfile>()
            var hadSelected = existing.any { it.isSelected }

            for (link in allLinks) {
                val result = VmessLinkParser.parse(link)
                if (result.isSuccess) {
                    val parsed = result.getOrThrow()
                    val isDuplicate = existing.any {
                        it.protocol.equals(parsed.protocol, ignoreCase = true) &&
                        it.address.equals(parsed.address, ignoreCase = true) &&
                        it.port == parsed.port &&
                        it.uuid.equals(parsed.uuid, ignoreCase = true)
                    } || toInsert.any {
                        it.protocol.equals(parsed.protocol, ignoreCase = true) &&
                        it.address.equals(parsed.address, ignoreCase = true) &&
                        it.port == parsed.port &&
                        it.uuid.equals(parsed.uuid, ignoreCase = true)
                    }

                    if (isDuplicate) {
                        duplicateCount++
                    } else {
                        val willSelect = !hadSelected && toInsert.isEmpty() && existing.isEmpty()
                        if (willSelect) {
                            hadSelected = true
                        }
                        toInsert.add(parsed.copy(isSelected = willSelect))
                        importedCount++
                    }
                }
            }

            if (toInsert.isNotEmpty()) {
                val insertedIds = profileDao.insertProfiles(toInsert)
                val selectIndex = toInsert.indexOfFirst { it.isSelected }
                if (selectIndex != -1 && selectIndex < insertedIds.size) {
                    profileDao.setSelected(insertedIds[selectIndex])
                }
            }

            val summary = buildString {
                if (importedCount > 0) {
                    append("Imported $importedCount profile${if (importedCount > 1) "s" else ""}")
                    if (duplicateCount > 0) {
                        append(" ($duplicateCount duplicate${if (duplicateCount > 1) "s" else ""} skipped)")
                    }
                } else if (duplicateCount > 0) {
                    append("0 imported ($duplicateCount duplicate${if (duplicateCount > 1) "s" else ""} skipped)")
                } else {
                    append("No valid vless:// or vmess:// links found")
                }
            }
            showMessage(summary)
        }
    }

    fun importFromFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val content = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                importMultipleLinks(content)
            } catch (e: Exception) {
                showMessage("Failed to read file: ${e.message}")
            }
        }
    }

    private var isTestingSingleProfile = false

    fun testProfileLatency(profile: ServerProfile) {
        if (isTestingSingleProfile) return
        viewModelScope.launch(Dispatchers.IO) {
            isTestingSingleProfile = true
            try {
                profileDao.updateLatency(profile.id, 0, System.currentTimeMillis())
                val latency = PingTester.testLatency(profile)
                profileDao.updateLatency(profile.id, latency, System.currentTimeMillis())
            } finally {
                isTestingSingleProfile = false
            }
        }
    }

    fun testAllLatencies() {
        if (_isPingingAll.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isPingingAll.value = true
            val profiles = profileDao.getAllProfiles()
            if (profiles.isEmpty()) {
                _isPingingAll.value = false
                return@launch
            }
            val semaphore = Semaphore(3)
            val timestamp = System.currentTimeMillis()

            coroutineScope {
                profiles.forEach { p ->
                    launch {
                        semaphore.withPermit {
                            profileDao.updateLatency(p.id, 0, timestamp)
                            val latency = PingTester.testLatency(p)
                            profileDao.updateLatency(p.id, latency, timestamp)
                        }
                    }
                }
            }
            _isPingingAll.value = false
        }
    }

    fun updateAutomationSettings(settings: AutomationSettings) {
        prefsRepo.updateAutomationSettings(settings)
    }

    fun clearManualOverride() {
        prefsRepo.clearManualOverride()
        showMessage("Manual override cleared. Automation resumed.")
        viewModelScope.launch {
            automationManager.evaluateNetworkState()
        }
    }

    fun addTargetSsid(ssid: String) {
        val clean = automationManager.cleanSsidString(ssid) ?: ssid.trim()
        if (clean.isNotEmpty()) {
            val current = automationSettings.value
            val updated = current.copy(
                targetWifiSsids = current.targetWifiSsids + clean,
                specificSsid = clean,
                matchAnyWifiNetwork = false,
                autoStartOnWifi = true
            )
            updateAutomationSettings(updated)
            prefsRepo.clearManualOverride()
            showMessage("Added Wi-Fi: $clean")
            viewModelScope.launch {
                automationManager.evaluateNetworkState()
            }
        }
    }

    fun removeTargetSsid(ssid: String) {
        val current = automationSettings.value
        val updated = current.copy(targetWifiSsids = current.targetWifiSsids - ssid)
        updateAutomationSettings(updated)
    }

    fun clearLogs() {
        viewModelScope.launch {
            logDao.clearLogs()
            showMessage("Logs cleared")
        }
    }

    fun applyRootHotspot() {
        viewModelScope.launch {
            val port = automationSettings.value.hotspotProxyPort
            val res = HotspotSharingManager.applyRootTetheringRules(port)
            res.onSuccess { msg ->
                val updated = automationSettings.value.copy(isRootHotspotRoutingEnabled = true)
                updateAutomationSettings(updated)
                showMessage(msg)
            }.onFailure { err ->
                showMessage("Root error: ${err.message}")
            }
        }
    }

    fun clearRootHotspot() {
        viewModelScope.launch {
            val res = HotspotSharingManager.clearRootTetheringRules()
            res.onSuccess { msg ->
                val updated = automationSettings.value.copy(isRootHotspotRoutingEnabled = false)
                updateAutomationSettings(updated)
                showMessage(msg)
            }
        }
    }
}
