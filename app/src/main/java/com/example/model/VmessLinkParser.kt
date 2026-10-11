package com.example.model

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object VmessLinkParser {

    /**
     * Parses either a vmess:// or vless:// link into a ServerProfile.
     */
    fun parse(rawInput: String): Result<ServerProfile> {
        return runCatching {
            val link = rawInput.trim()
            when {
                link.startsWith("vless://", ignoreCase = true) -> parseVlessLink(link)
                link.startsWith("vmess://", ignoreCase = true) -> parseVmessLink(link)
                else -> throw IllegalArgumentException("Link must start with vmess:// or vless://")
            }
        }
    }

    private fun parseVlessLink(link: String): ServerProfile {
        // Format: vless://uuid@address:port?param=val#remarks
        val withoutScheme = link.substring(8).trim()
        val fragmentIndex = withoutScheme.indexOf('#')
        val remarks = if (fragmentIndex != -1) {
            URLDecoder.decode(withoutScheme.substring(fragmentIndex + 1), "UTF-8").trim()
        } else {
            "VLESS Node"
        }

        val mainPart = if (fragmentIndex != -1) withoutScheme.substring(0, fragmentIndex) else withoutScheme
        val queryIndex = mainPart.indexOf('?')
        val authorityPart = if (queryIndex != -1) mainPart.substring(0, queryIndex) else mainPart
        val queryPart = if (queryIndex != -1) mainPart.substring(queryIndex + 1) else ""

        val atIndex = authorityPart.indexOf('@')
        if (atIndex == -1) throw IllegalArgumentException("Invalid VLESS link: missing '@' delimiter")

        val uuid = authorityPart.substring(0, atIndex).trim()
        val hostPort = authorityPart.substring(atIndex + 1).trim()
        val colonIndex = hostPort.lastIndexOf(':')
        if (colonIndex == -1) throw IllegalArgumentException("Invalid VLESS link: missing host:port")

        val address = hostPort.substring(0, colonIndex).trim()
        val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: 443

        // Parse query params
        val queryMap = mutableMapOf<String, String>()
        if (queryPart.isNotEmpty()) {
            queryPart.split("&").forEach { param ->
                val eq = param.indexOf('=')
                if (eq != -1) {
                    val key = param.substring(0, eq).trim().lowercase()
                    val value = URLDecoder.decode(param.substring(eq + 1).trim(), "UTF-8")
                    queryMap[key] = value
                }
            }
        }

        val network = queryMap["type"] ?: queryMap["net"] ?: "ws"
        val security = queryMap["security"] ?: "tls"
        val path = queryMap["path"] ?: "/"
        val host = queryMap["host"] ?: ""
        val sni = queryMap["sni"] ?: queryMap["peer"] ?: host.ifEmpty { address }
        val alpn = queryMap["alpn"] ?: ""
        val headerType = queryMap["headertype"] ?: "none"

        return ServerProfile(
            name = remarks.ifEmpty { "VLESS Node" },
            protocol = "vless",
            address = address,
            port = port,
            uuid = uuid,
            alterId = 0,
            security = "none",
            network = network.lowercase(),
            headerType = headerType,
            host = host,
            path = path,
            tls = if (security.isNotEmpty() && security != "none") "tls" else "none",
            sni = sni,
            alpn = alpn
        )
    }

    private fun parseVmessLink(link: String): ServerProfile {
        val content = link.substring(8).trim()
        return if (content.contains("@") && content.contains(":")) {
            parseUriFormat(content)
        } else {
            parseJsonBase64Format(content)
        }
    }

    private fun parseJsonBase64Format(base64Content: String): ServerProfile {
        val decodedJson = decodeFlexibleBase64(base64Content)
        val json = JSONObject(decodedJson)

        val name = json.optString("ps", "VMess Server").trim().ifEmpty { "VMess Server" }
        val address = json.optString("add", "").trim()
        val port = json.optInt("port", 443)
        val uuid = json.optString("id", "").trim()
        val alterId = json.optInt("aid", 0)
        val security = json.optString("scy", "auto").trim().ifEmpty { "auto" }
        val network = json.optString("net", "tcp").trim().lowercase().ifEmpty { "tcp" }
        val headerType = json.optString("type", "none").trim().lowercase()
        val host = json.optString("host", "").trim()
        val path = json.optString("path", "/").trim().ifEmpty { "/" }
        val tls = json.optString("tls", "").trim().lowercase()
        val sni = json.optString("sni", host).trim().ifEmpty { host }
        val alpn = json.optString("alpn", "").trim()

        if (address.isEmpty()) throw IllegalArgumentException("Server address 'add' is missing")
        if (uuid.isEmpty()) throw IllegalArgumentException("Server UUID 'id' is missing")
        if (port <= 0 || port > 65535) throw IllegalArgumentException("Invalid port: $port")

        return ServerProfile(
            name = name,
            protocol = "vmess",
            address = address,
            port = port,
            uuid = uuid,
            alterId = alterId,
            security = security,
            network = network,
            headerType = headerType,
            host = host,
            path = path,
            tls = if (tls.isNotEmpty() && tls != "none") "tls" else "none",
            sni = sni,
            alpn = alpn
        )
    }

    private fun parseUriFormat(uriContent: String): ServerProfile {
        val uri = Uri.parse("vmess://$uriContent")
        val userInfo = uri.userInfo ?: ""
        val parts = userInfo.split(":")
        val security = if (parts.size > 1) parts[0] else "auto"
        val uuid = if (parts.size > 1) parts[1] else parts[0]

        val address = uri.host ?: throw IllegalArgumentException("Missing host")
        val port = if (uri.port > 0) uri.port else 443
        val remarks = uri.getQueryParameter("remarks")?.let { URLDecoder.decode(it, "UTF-8") } ?: "VMess Server"
        val network = uri.getQueryParameter("obfs") ?: uri.getQueryParameter("net") ?: "tcp"
        val path = uri.getQueryParameter("path") ?: "/"
        val host = uri.getQueryParameter("obfsParam") ?: uri.getQueryParameter("host") ?: ""
        val tls = if (uri.getQueryParameter("tls") == "1" || uri.getQueryParameter("tls") == "tls") "tls" else "none"
        val sni = uri.getQueryParameter("peer") ?: uri.getQueryParameter("sni") ?: host

        return ServerProfile(
            name = remarks,
            protocol = "vmess",
            address = address,
            port = port,
            uuid = uuid,
            alterId = 0,
            security = security,
            network = network.lowercase(),
            headerType = "none",
            host = host,
            path = path,
            tls = tls,
            sni = sni,
            alpn = ""
        )
    }

    /**
     * Converts a ServerProfile into the appropriate vmess:// or vless:// link.
     */
    fun toShareLink(profile: ServerProfile): String {
        return if (profile.isVless) {
            toVlessLink(profile)
        } else {
            toVmessLink(profile)
        }
    }

    fun toVlessLink(profile: ServerProfile): String {
        val encodedPath = URLEncoder.encode(profile.path, "UTF-8")
        val encodedName = URLEncoder.encode(profile.name, "UTF-8")
        val sec = if (profile.isTlsEnabled) "tls" else "none"
        val sniPart = if (profile.sni.isNotEmpty()) "&sni=${profile.sni}" else ""
        val hostPart = if (profile.host.isNotEmpty()) "&host=${profile.host}" else ""
        val alpnPart = if (profile.alpn.isNotEmpty()) "&alpn=${profile.alpn}" else ""

        return "vless://${profile.uuid}@${profile.address}:${profile.port}?type=${profile.network}&security=$sec&path=$encodedPath$hostPart$sniPart$alpnPart#$encodedName"
    }

    fun toVmessLink(profile: ServerProfile): String {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", profile.name)
            put("add", profile.address)
            put("port", profile.port.toString())
            put("id", profile.uuid)
            put("aid", profile.alterId.toString())
            put("scy", profile.security)
            put("net", profile.network)
            put("type", profile.headerType)
            put("host", profile.host)
            put("path", profile.path)
            put("tls", profile.tls)
            put("sni", profile.sni)
            put("alpn", profile.alpn)
        }
        val encoded = Base64.encodeToString(
            json.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        return "vmess://$encoded"
    }

    /**
     * Generates a complete, compliant V2Ray/Xray JSON configuration for this profile (supports both VMess and VLESS).
     */
    fun toV2RayConfigJson(
        profile: ServerProfile,
        socksPort: Int = 10808,
        httpPort: Int = 10809
    ): String {
        val root = JSONObject()

        // 1. Log configuration
        root.put("log", JSONObject().apply { put("loglevel", "warning") })

        // 2. Inbounds
        val inboundsArray = JSONArray()
        inboundsArray.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", socksPort)
            put("listen", "0.0.0.0")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
            })
        })
        inboundsArray.put(JSONObject().apply {
            put("tag", "http-in")
            put("port", httpPort)
            put("listen", "0.0.0.0")
            put("protocol", "http")
            put("settings", JSONObject())
        })
        root.put("inbounds", inboundsArray)

        // 3. Outbounds
        val outboundsArray = JSONArray()

        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", if (profile.isVless) "vless" else "vmess")

            val serverObj = JSONObject().apply {
                put("address", profile.address)
                put("port", profile.port)
                val userObj = JSONObject().apply {
                    put("id", profile.uuid)
                    if (profile.isVless) {
                        put("encryption", "none")
                    } else {
                        put("alterId", profile.alterId)
                        put("security", profile.security)
                    }
                    put("level", 0)
                }
                put("users", JSONArray().put(userObj))
            }
            put("settings", JSONObject().put("vnext", JSONArray().put(serverObj)))

            // StreamSettings
            val streamSettings = JSONObject().apply {
                put("network", profile.network)
                put("security", if (profile.isTlsEnabled) "tls" else "none")

                if (profile.isTlsEnabled) {
                    val tlsSettings = JSONObject().apply {
                        put("serverName", profile.sni.ifEmpty { profile.address })
                        put("allowInsecure", true)
                        if (profile.alpn.isNotEmpty()) {
                            val alpnList = JSONArray()
                            profile.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                                alpnList.put(it)
                            }
                            put("alpn", alpnList)
                        }
                    }
                    put("tlsSettings", tlsSettings)
                }

                if (profile.network == "ws") {
                    val wsSettings = JSONObject().apply {
                        put("path", profile.path.ifEmpty { "/" })
                        if (profile.host.isNotEmpty()) {
                            put("headers", JSONObject().put("Host", profile.host))
                        }
                    }
                    put("wsSettings", wsSettings)
                }
            }
            put("streamSettings", streamSettings)
        }
        outboundsArray.put(proxyOutbound)

        // Direct outbound
        outboundsArray.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        })

        root.put("outbounds", outboundsArray)

        // 4. DNS
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8").put("localhost"))
        })

        return root.toString(2)
    }

    private fun decodeFlexibleBase64(input: String): String {
        var sanitized = input.trim()
            .replace("-", "+")
            .replace("_", "/")
            .replace("\n", "")
            .replace("\r", "")

        val remainder = sanitized.length % 4
        if (remainder > 0) {
            sanitized += "=".repeat(4 - remainder)
        }

        val bytes = Base64.decode(sanitized, Base64.DEFAULT)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
