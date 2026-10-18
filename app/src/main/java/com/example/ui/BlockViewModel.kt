package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.block.BlockEngine
import com.example.core.block.BlockListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BlockViewModel(application: Application) : AndroidViewModel(application) {

    private val blockEngine = BlockEngine.getInstance(application)
    private val listManager = BlockListManager(application)

    val isEnabled: StateFlow<Boolean> = blockEngine.enabled
    val blockedCount: StateFlow<Long> = blockEngine.sessionBlockedCount

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _customRules = MutableStateFlow<List<String>>(emptyList())
    val customRules: StateFlow<List<String>> = _customRules.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshCustomRules()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun toggleBlocking(enabled: Boolean) {
        if (!enabled) {
            blockEngine.setEnabled(false)
            return
        }

        if (listManager.isCombinedListPresent()) {
            blockEngine.setEnabled(true)
        } else {
            // First time enable: automatically download the lists
            updateLists(initialEnable = true)
        }
    }

    fun updateLists(initialEnable: Boolean = false) {
        if (_isDownloading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isDownloading.value = true
            val result = listManager.downloadAndMergeAll()
            _isDownloading.value = false

            result.onSuccess { count ->
                blockEngine.reload()
                blockEngine.setEnabled(true)
                _message.value = "Lists updated ($count domains)"
            }.onFailure {
                if (initialEnable) {
                    blockEngine.setEnabled(false)
                }
                _message.value = "Could not download the list. Check your internet and try again."
            }
        }
    }

    fun addCustomRule(domain: String) {
        if (domain.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val added = listManager.addCustomRule(domain)
            if (added) {
                refreshCustomRules()
                blockEngine.reload()
            } else {
                _message.value = "Invalid or duplicate domain: $domain"
            }
        }
    }

    fun removeCustomRule(domain: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = listManager.removeCustomRule(domain)
            if (removed) {
                refreshCustomRules()
                blockEngine.reload()
            }
        }
    }

    fun refreshCustomRules() {
        viewModelScope.launch(Dispatchers.IO) {
            _customRules.value = listManager.getCustomRules()
        }
    }
}
