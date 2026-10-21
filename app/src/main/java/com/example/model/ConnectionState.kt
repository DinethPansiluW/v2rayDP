package com.example.model

sealed class ConnectionState {
    data class Disconnected(
        val reason: String = "Idle"
    ) : ConnectionState()

    data class Connecting(
        val profileName: String,
        val step: String = "Initializing proxy engine…"
    ) : ConnectionState()

    data class Connected(
        val profile: ServerProfile,
        val connectedAt: Long = System.currentTimeMillis(),
        val localProxyPort: Int = 10808
    ) : ConnectionState()

    data class Stopping(
        val reason: String = "Stopping…"
    ) : ConnectionState()

    data class Error(
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : ConnectionState()

    val isRunning: Boolean
        get() = this is Connected || this is Connecting

    val isBusy: Boolean
        get() = this is Connecting || this is Stopping
}

data class TrafficStats(
    val uploadSpeedBps: Long = 0,
    val downloadSpeedBps: Long = 0,
    val sessionTxBytes: Long = 0,
    val sessionRxBytes: Long = 0,
    val connectedDurationSeconds: Long = 0
) {
    val formattedUploadSpeed: String
        get() = formatSpeed(uploadSpeedBps)

    val formattedDownloadSpeed: String
        get() = formatSpeed(downloadSpeedBps)

    val formattedTxTotal: String
        get() = formatBytes(sessionTxBytes)

    val formattedRxTotal: String
        get() = formatBytes(sessionRxBytes)

    val formattedDuration: String
        get() {
            val hours = connectedDurationSeconds / 3600
            val minutes = (connectedDurationSeconds % 3600) / 60
            val seconds = connectedDurationSeconds % 60
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            return when {
                bytesPerSec >= 1_000_000 -> String.format("%.2f MB/s", bytesPerSec / 1_000_000.0)
                bytesPerSec >= 1_000 -> String.format("%.1f KB/s", bytesPerSec / 1_000.0)
                else -> "$bytesPerSec B/s"
            }
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
                bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
                bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
                else -> "$bytes B"
            }
        }
    }
}
