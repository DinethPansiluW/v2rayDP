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
    val flow: String = "",         // e.g. xtls-rprx-vision
    val publicKey: String = "",    // pbk for Reality
    val shortId: String = "",      // sid for Reality
    val fingerprint: String = "",  // fp: chrome, firefox, safari, ios, random
    val spiderX: String = "",      // spx for Reality (e.g. / or custom)
    val serviceName: String = "",  // for gRPC transport
    val grpcMode: String = "gun",  // gun, multi
    val isSelected: Boolean = false,
    val pingLatencyMs: Int = -1,
    val lastPingTimestamp: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isVless: Boolean
        get() = protocol.equals("vless", ignoreCase = true)

    val isReality: Boolean
        get() = tls.equals("reality", ignoreCase = true) || publicKey.isNotEmpty()

    val isTlsEnabled: Boolean
        get() = tls.equals("tls", ignoreCase = true) || isReality

    val isGrpc: Boolean
        get() = network.equals("grpc", ignoreCase = true)

    val displaySubtitle: String
        get() = "${protocol.uppercase()} • $address:$port • ${network.uppercase()}${if (isReality) " • REALITY" else if (isTlsEnabled) " • TLS" else " • PLAIN"}${if (flow.isNotEmpty()) " • $flow" else ""}"
}
