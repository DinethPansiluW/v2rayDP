package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_profiles")
data class ServerProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: String = "vmess", // vmess or vless
    val address: String,
    val port: Int,
    val uuid: String,
    val alterId: Int = 0,
    val security: String = "auto", // auto, none, aes-128-gcm, chacha20-poly1305
    val network: String = "ws",    // tcp, ws, grpc, h2
    val headerType: String = "none",
    val host: String = "",
    val path: String = "/",
    val tls: String = "tls",       // none, tls, reality
    val sni: String = "",
    val alpn: String = "",
    val isSelected: Boolean = false,
    val pingLatencyMs: Int = -1,
    val lastPingTimestamp: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isVless: Boolean
        get() = protocol.equals("vless", ignoreCase = true)

    val isTlsEnabled: Boolean
        get() = tls.equals("tls", ignoreCase = true) || tls.equals("reality", ignoreCase = true)

    val displaySubtitle: String
        get() = "${protocol.uppercase()} • $address:$port • ${network.uppercase()} • ${if (isTlsEnabled) "TLS" else "PLAIN"}"
}
