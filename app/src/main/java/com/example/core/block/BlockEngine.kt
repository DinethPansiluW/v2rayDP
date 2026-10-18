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
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BlockEngine private constructor(private val appContext: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _stats = MutableStateFlow(BlockStats())
    val stats: StateFlow<BlockStats> = _stats.asStateFlow()

    private val sessionCount = AtomicLong(0L)
    private val totalCount = AtomicLong(0L)
    private var lastFlushedTotal = 0L
    private var lastFlushTimestamp = 0L

    // In-memory ring buffer for last 100 blocked entries
    private val ringBuffer = ArrayDeque<BlockedEntry>(100)
    private val ringBufferLock = Any()

    // Rate-limiting: last recorded timestamp per host (limit to 1 entry per 10s)
    private val rateLimitMap = ConcurrentHashMap<String, Long>()

    private val isLoaded = AtomicBoolean(false)
    private val isLoading = AtomicBoolean(false)

    data class RuleSet(
        val blockedDomains: Set<String> = emptySet(),
        val allowedDomains: Set<String> = emptySet(),
        val ruleSources: Map<String, String> = emptyMap(),
        val totalRules: Int = 0,
        val blockDoh: Boolean = false
    )

    @Volatile
    private var activeRuleSet = RuleSet()

    init {
        val prefs = V2RayApplication.get().preferencesRepository
        _enabled.value = prefs.isBlockingEnabled
        totalCount.set(prefs.blockedTotalCount)
        lastFlushedTotal = prefs.blockedTotalCount
        updateStatsSnapshot()
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
     * Checks if host/port is blocked according to active rule set.
     */
    fun isBlocked(host: String, port: Int = 0): Boolean {
        if (!_enabled.value) return false

        val ruleSet = activeRuleSet

        // Check port 853 (DNS-over-TLS) if DoH/DoT blocking is enabled
        if (ruleSet.blockDoh && port == 853) {
            recordBlocked(host, "DNS-over-TLS (port 853)")
            return true
        }

        val clean = host.lowercase().trim().removeSuffix(".")
        if (clean.isEmpty()) return false

        // Check if IP literal: skip domain tree suffix matching
        val isIpLiteral = isIpAddress(clean)
        if (isIpLiteral) {
            // Check direct IP match
            if (ruleSet.allowedDomains.contains(clean)) return false
            if (ruleSet.blockedDomains.contains(clean)) {
                val ruleName = ruleSet.ruleSources[clean] ?: "IP blocklist"
                recordBlocked(clean, ruleName)
                return true
            }
            return false
        }

        // Allowlist check first - ALWAYS WINS
        if (isMatchedInSet(clean, ruleSet.allowedDomains)) {
            return false
        }

        // Blocklist check
        if (isMatchedInSet(clean, ruleSet.blockedDomains)) {
            val matchingRule = findMatchingRule(clean, ruleSet.blockedDomains, ruleSet.ruleSources)
            recordBlocked(clean, matchingRule ?: "Domain blocklist")
            return true
        }

        // DoH hostnames check
        if (ruleSet.blockDoh && isMatchedInSet(clean, DOH_HOSTNAMES)) {
            recordBlocked(clean, "DoH provider")
            return true
        }

        return false
    }

    private fun isMatchedInSet(host: String, domainSet: Set<String>): Boolean {
        if (domainSet.isEmpty()) return false
        if (domainSet.contains(host)) return true

        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex < host.length - 1) {
            val parentSuffix = host.substring(dotIndex + 1)
            if (domainSet.contains(parentSuffix)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

    private fun findMatchingRule(
        host: String,
        domainSet: Set<String>,
        sourceMap: Map<String, String>
    ): String? {
        if (domainSet.contains(host)) return sourceMap[host]
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex < host.length - 1) {
            val parentSuffix = host.substring(dotIndex + 1)
            if (domainSet.contains(parentSuffix)) return sourceMap[parentSuffix] ?: parentSuffix
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return null
    }

    fun testDomain(host: String): String? {
        val clean = host.lowercase().trim().removeSuffix(".")
        if (clean.isEmpty()) return "Invalid domain"

        val ruleSet = activeRuleSet

        if (isMatchedInSet(clean, ruleSet.allowedDomains)) {
            return "Allowed by custom allowlist"
        }

        if (isMatchedInSet(clean, ruleSet.blockedDomains)) {
            val rule = findMatchingRule(clean, ruleSet.blockedDomains, ruleSet.ruleSources) ?: "Blocklist"
            return "Blocked by $rule"
        }

        if (ruleSet.blockDoh && isMatchedInSet(clean, DOH_HOSTNAMES)) {
            return "Blocked by DoH/DoT provider rule"
        }

        return "Allowed (Not found in active blocklists)"
    }

    private fun recordBlocked(host: String, rule: String) {
        sessionCount.incrementAndGet()
        val total = totalCount.incrementAndGet()

        // Rate-limit ring buffer insertion to 1 entry per 10s per host
        val now = System.currentTimeMillis()
        val lastLogged = rateLimitMap[host] ?: 0L
        if (now - lastLogged >= 10_000L) {
            rateLimitMap[host] = now
            val entry = BlockedEntry(host = host, timestamp = now, rule = rule)
            synchronized(ringBufferLock) {
                if (ringBuffer.size >= 100) {
                    ringBuffer.pollFirst()
                }
                ringBuffer.addLast(entry)
            }
            updateStatsSnapshot()
        }

        // Periodic flush of total counter (at most every 60s)
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
        updateStatsSnapshot()
    }

    fun resetStats() {
        sessionCount.set(0L)
        totalCount.set(0L)
        lastFlushedTotal = 0L
        synchronized(ringBufferLock) {
            ringBuffer.clear()
        }
        rateLimitMap.clear()
        val prefs = V2RayApplication.get().preferencesRepository
        prefs.resetBlockedTotalCount()
        updateStatsSnapshot()
    }

    fun loadIfNeeded() {
        if (isLoaded.get()) return
        reload()
    }

    fun reload() {
        if (isLoading.getAndSet(true)) return
        _stats.value = _stats.value.copy(isLoading = true)

        engineScope.launch {
            try {
                val app = V2RayApplication.get()
                val prefs = app.preferencesRepository
                val dir = File(appContext.filesDir, "blocklists")
                if (!dir.exists()) dir.mkdirs()

                val blocked = HashSet<String>(128_000)
                val allowed = HashSet<String>(2048)
                val sources = HashMap<String, String>(128_000)

                // 1. Load custom allow rules
                val customAllowFile = File(dir, "custom_allow.txt")
                if (customAllowFile.exists()) {
                    customAllowFile.forEachLine { raw ->
                        val norm = normalizeRule(raw)
                        if (norm != null) allowed.add(norm)
                    }
                }

                // 2. Load custom block rules
                val customBlockFile = File(dir, "custom_block.txt")
                if (customBlockFile.exists()) {
                    customBlockFile.forEachLine { raw ->
                        val norm = normalizeRule(raw)
                        if (norm != null) {
                            blocked.add(norm)
                            sources[norm] = "Custom rule"
                        }
                    }
                }

                // 3. Load active preset lists
                val presetIds = listOf("ads", "trackers", "malware")
                for (id in presetIds) {
                    if (prefs.getPresetEnabled(id, true)) {
                        val listFile = File(dir, "$id.txt")
                        if (listFile.exists()) {
                            val presetName = id.replaceFirstChar { it.uppercase() }
                            listFile.forEachLine { line ->
                                val domain = parseDomainFromLine(line)
                                if (domain != null) {
                                    blocked.add(domain)
                                    if (!sources.containsKey(domain)) {
                                        sources[domain] = "$presetName list"
                                    }
                                }
                            }
                        }
                    }
                }

                val totalRules = blocked.size
                val blockDoh = prefs.isBlockDohProviders
                val newRuleSet = RuleSet(
                    blockedDomains = blocked,
                    allowedDomains = allowed,
                    ruleSources = sources,
                    totalRules = totalRules,
                    blockDoh = blockDoh
                )

                // Atomic reference swap
                activeRuleSet = newRuleSet
                isLoaded.set(true)

                Log.i(TAG, "BlockEngine rules loaded: $totalRules blocked domains, ${allowed.size} allowed domains")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load blocklists: ${e.message}", e)
            } finally {
                isLoading.set(false)
                updateStatsSnapshot()
            }
        }
    }

    private fun updateStatsSnapshot() {
        val recentList: List<BlockedEntry>
        synchronized(ringBufferLock) {
            recentList = ringBuffer.toList().reversed()
        }
        val ruleSet = activeRuleSet
        _stats.value = BlockStats(
            sessionCount = sessionCount.get(),
            totalCount = totalCount.get(),
            recentBlocked = recentList,
            totalRuleCount = ruleSet.totalRules,
            isLoading = isLoading.get(),
            warningThresholdExceeded = ruleSet.totalRules > 400_000
        )
    }

    private fun normalizeRule(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        var s = trimmed.removePrefix("*.")
        s = s.removeSuffix(".")
        return if (isValidDomainOrIp(s)) s else null
    }

    private fun parseDomainFromLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null

        val parts = trimmed.split(Regex("\\s+"))
        val candidate = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
            parts.isNotEmpty() -> parts[0]
            else -> return null
        }

        val domain = candidate.lowercase().trim().removeSuffix(".")
        if (domain == "localhost" || domain == "broadcasthost" || domain == "local") return null
        return if (isValidDomainOrIp(domain)) domain else null
    }

    private fun isValidDomainOrIp(s: String): Boolean {
        if (s.isEmpty() || s.length > 253) return false
        return !s.contains(" ") && !s.contains("/") && !s.contains("\\")
    }

    private fun isIpAddress(s: String): Boolean {
        return s.all { it.isDigit() || it == '.' } || s.contains(':')
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

        val DOH_HOSTNAMES = setOf(
            "cloudflare-dns.com",
            "1dot1dot1dot1.cloudflare-dns.com",
            "dns.google",
            "dns.google.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.adguard-dns.com",
            "dns.nextdns.io",
            "doh.cleanbrowsing.org",
            "dns.alidns.com",
            "doh.mullvad.net",
            "dns.tuna.tsinghua.edu.cn",
            "doh.dns.apple.com"
        )
    }
}
