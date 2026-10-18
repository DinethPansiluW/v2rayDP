package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.V2RayApplication
import com.example.core.block.BlockEngine
import com.example.core.block.BlockListManager
import com.example.core.block.BlockStats
import com.example.core.block.BlockUpdateWorker
import com.example.core.block.PresetListStatus
import com.example.model.ConnectionState
import com.example.service.V2RayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BlockViewModel(application: Application) : AndroidViewModel(application) {

    private val blockEngine = BlockEngine.getInstance(application)
    private val listManager = BlockListManager(application)
    private val prefs = V2RayApplication.get().preferencesRepository

    val isBlockingEnabled: StateFlow<Boolean> = blockEngine.enabled
    val stats: StateFlow<BlockStats> = blockEngine.stats

    private val _presets = MutableStateFlow<List<PresetListStatus>>(emptyList())
    val presets: StateFlow<List<PresetListStatus>> = _presets.asStateFlow()

    private val _customBlockedRules = MutableStateFlow<List<String>>(emptyList())
    val customBlockedRules: StateFlow<List<String>> = _customBlockedRules.asStateFlow()

    private val _customAllowedRules = MutableStateFlow<List<String>>(emptyList())
    val customAllowedRules: StateFlow<List<String>> = _customAllowedRules.asStateFlow()

    private val _blockDohProviders = MutableStateFlow(prefs.isBlockDohProviders)
    val blockDohProviders: StateFlow<Boolean> = _blockDohProviders.asStateFlow()

    private val _autoUpdateWeekly = MutableStateFlow(prefs.isAutoUpdateWeekly)
    val autoUpdateWeekly: StateFlow<Boolean> = _autoUpdateWeekly.asStateFlow()

    val isVpnConnected: StateFlow<Boolean> = V2RayVpnService.serviceState
        .map { it is ConnectionState.Connected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        refreshPresets()
        refreshCustomRules()
    }

    fun dismissStatusMessage() {
        _statusMessage.value = null
    }

    fun showMessage(msg: String) {
        _statusMessage.value = msg
    }

    fun toggleBlocking(enabled: Boolean) {
        blockEngine.setEnabled(enabled)
    }

    fun refreshPresets() {
        val sources = listManager.loadSourcesFromAssets()
        val list = sources.map { src ->
            PresetListStatus(
                id = src.id,
                name = src.name,
                description = src.description,
                enabled = prefs.getPresetEnabled(src.id, true),
                entryCount = prefs.getPresetEntryCount(src.id),
                lastUpdated = prefs.getPresetLastUpdated(src.id)
            )
        }
        _presets.value = list
    }

    fun refreshCustomRules() {
        viewModelScope.launch(Dispatchers.IO) {
            _customBlockedRules.value = listManager.getCustomRules(isBlock = true)
            _customAllowedRules.value = listManager.getCustomRules(isBlock = false)
        }
    }

    fun togglePreset(id: String, enabled: Boolean) {
        prefs.setPresetEnabled(id, enabled)
        refreshPresets()
        blockEngine.reload()
    }

    fun updatePreset(id: String) {
        val sources = listManager.loadSourcesFromAssets()
        val source = sources.find { it.id == id } ?: return

        // Set updating indicator
        _presets.value = _presets.value.map {
            if (it.id == id) it.copy(isUpdating = true) else it
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = listManager.downloadPreset(source)
            result.onSuccess { count ->
                showMessage("${source.name} updated: $count entries")
            }.onFailure { err ->
                showMessage("Failed to update ${source.name}: ${err.message}")
            }
            refreshPresets()
        }
    }

    fun updateAllPresets() {
        _presets.value = _presets.value.map { it.copy(isUpdating = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val results = listManager.downloadAll()
            val successCount = results.values.count { it.isSuccess }
            showMessage("Updated $successCount lists successfully")
            refreshPresets()
        }
    }

    fun addCustomRule(rule: String, isBlock: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val added = listManager.addCustomRule(rule, isBlock)
            if (added) {
                showMessage("Added rule: $rule")
                refreshCustomRules()
            } else {
                showMessage("Invalid or duplicate rule: $rule")
            }
        }
    }

    fun removeCustomRule(rule: String, isBlock: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = listManager.removeCustomRule(rule, isBlock)
            if (removed) {
                showMessage("Removed rule: $rule")
                refreshCustomRules()
            }
        }
    }

    fun allowDomain(domain: String) {
        addCustomRule(domain, isBlock = false)
    }

    fun testDomain(domain: String) {
        val result = blockEngine.testDomain(domain)
        _testResult.value = result
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun resetStats() {
        blockEngine.resetStats()
        showMessage("Blocking statistics reset")
    }

    fun toggleBlockDoh(enabled: Boolean) {
        _blockDohProviders.value = enabled
        prefs.isBlockDohProviders = enabled
        blockEngine.reload()
    }

    fun toggleAutoUpdateWeekly(enabled: Boolean) {
        _autoUpdateWeekly.value = enabled
        prefs.isAutoUpdateWeekly = enabled
        if (enabled) {
            BlockUpdateWorker.schedule(getApplication())
            showMessage("Auto-update scheduled weekly (on Wi-Fi & charging)")
        } else {
            BlockUpdateWorker.cancel(getApplication())
            showMessage("Weekly auto-update disabled")
        }
    }

    fun importCustomRules(uri: Uri, isBlock: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = listManager.importRules(uri, isBlock)
            result.onSuccess { count ->
                showMessage("Imported $count rules")
                refreshCustomRules()
            }.onFailure { err ->
                showMessage("Import failed: ${err.message}")
            }
        }
    }

    fun exportCustomRules(uri: Uri, isBlock: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = listManager.exportRules(uri, isBlock)
            result.onSuccess { count ->
                showMessage("Exported $count rules successfully")
            }.onFailure { err ->
                showMessage("Export failed: ${err.message}")
            }
        }
    }
}
