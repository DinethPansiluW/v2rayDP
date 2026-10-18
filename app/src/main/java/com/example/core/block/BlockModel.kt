package com.example.core.block

data class BlockStats(
    val sessionCount: Long = 0L,
    val totalCount: Long = 0L,
    val recentBlocked: List<BlockedEntry> = emptyList(),
    val totalRuleCount: Int = 0,
    val isLoading: Boolean = false,
    val warningThresholdExceeded: Boolean = false
)

data class BlockedEntry(
    val host: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rule: String = "Domain blocklist"
)

data class BlocklistSource(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val format: String
)

data class PresetListStatus(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val entryCount: Int,
    val lastUpdated: Long,
    val isUpdating: Boolean = false
)
