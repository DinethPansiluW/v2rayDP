package com.example.core.block

import android.content.Context
import android.util.Log
import com.example.V2RayApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BlockEngine private constructor(private val appContext: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _sessionBlockedCount = MutableStateFlow(0L)
    val sessionBlockedCount: StateFlow<Long> = _sessionBlockedCount.asStateFlow()

    private val sessionCount = AtomicLong(0L)
    private val totalCount = AtomicLong(0L)
    private var lastFlushedTotal = 0L
    private var lastFlushTimestamp = 0L

    private val isLoaded = AtomicBoolean(false)
    private val isLoading = AtomicBoolean(false)

    @Volatile
    private var blockedDomains: Set<String> = emptySet()

    init {
        val prefs = V2RayApplication.get().preferencesRepository
        _enabled.value = prefs.isBlockingEnabled
        totalCount.set(prefs.blockedTotalCount)
        lastFlushedTotal = prefs.blockedTotalCount
        if (_enabled.value) {
            loadIfNeeded()
        }
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        val prefs = V2RayApplication.get().preferencesRepository
        prefs.isBlockingEnabled = enabled
        if (enabled && !isLoaded.get()) {
            loadIfNeeded()
        }
    }

    /**
     * Lock-free, O(label count) query safe from multiple proxy threads.
     */
    fun isBlocked(host: String, port: Int = 0): Boolean {
        if (!_enabled.value) return false

        val clean = host.lowercase().trim().removeSuffix(".")
        if (clean.isEmpty()) return false

        val set = blockedDomains
        if (set.isEmpty()) return false

        // 1. Direct host match
        if (set.contains(clean)) {
            recordBlocked()
            return true
        }

        // 2. Parent suffix matching (example.com blocks a.b.example.com)
        var dotIndex = clean.indexOf('.')
        while (dotIndex != -1 && dotIndex < clean.length - 1) {
            val parentSuffix = clean.substring(dotIndex + 1)
            if (set.contains(parentSuffix)) {
                recordBlocked()
                return true
            }
            dotIndex = clean.indexOf('.', dotIndex + 1)
        }

        return false
    }

    private fun recordBlocked() {
        val count = sessionCount.incrementAndGet()
        _sessionBlockedCount.value = count
        totalCount.incrementAndGet()

        val now = System.currentTimeMillis()
        if (now - lastFlushTimestamp >= 60_000L) {
            flushCounters()
        }
    }

    fun flushCounters() {
        val currentTotal = totalCount.get()
        lastFlushTimestamp = System.currentTimeMillis()
        if (currentTotal != lastFlushedTotal) {
            lastFlushedTotal = currentTotal
            engineScope.launch {
                val prefs = V2RayApplication.get().preferencesRepository
                prefs.blockedTotalCount = currentTotal
            }
        }
    }

    fun loadIfNeeded() {
        if (isLoaded.get()) return
        reload()
    }

    fun reload() {
        if (isLoading.getAndSet(true)) return

        engineScope.launch {
            try {
                val dir = File(appContext.filesDir, "blocklists")
                if (!dir.exists()) dir.mkdirs()

                val newSet = HashSet<String>(128_000)

                // 1. Combined blocklist
                val combinedFile = File(dir, "combined.txt")
                if (combinedFile.exists()) {
                    combinedFile.forEachLine { line ->
                        val trimmed = line.trim().lowercase()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            newSet.add(trimmed)
                        }
                    }
                }

                // 2. Custom block rules
                val customFile = File(dir, "custom_block.txt")
                if (customFile.exists()) {
                    customFile.forEachLine { line ->
                        var domain = line.trim().lowercase().removeSuffix(".")
                        if (domain.startsWith("*.")) domain = domain.substring(2)
                        if (domain.isNotEmpty() && !domain.startsWith("#")) {
                            newSet.add(domain)
                        }
                    }
                }

                blockedDomains = newSet
                isLoaded.set(true)
                Log.i(TAG, "BlockEngine rules loaded: ${newSet.size} blocked domains")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load blocklists: ${e.message}", e)
            } finally {
                isLoading.set(false)
            }
        }
    }

    companion object {
        private const val TAG = "BlockEngine"

        @Volatile
        private var instance: BlockEngine? = null

        fun getInstance(context: Context? = null): BlockEngine {
            return instance ?: synchronized(this) {
                instance ?: BlockEngine(context ?: V2RayApplication.get().applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
