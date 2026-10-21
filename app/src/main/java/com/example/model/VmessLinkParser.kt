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
     * Sanitizes raw link input by trimming, stripping zero-width/BOM chars,
     * removing surrounding quotes, backticks, < >, unwrapping markdown links,
     * removing stray brackets/parentheses not part of IPv6, and cutting at whitespace.
     */
    fun sanitizeLink(rawInput: String): String {
        var text = rawInput.trim()
        // Remove zero-width and BOM characters
        text = text.replace(Regex("[\\uFEFF\\u200B-\\u200D\\u2060\\u00A0]"), "")
        // Surrounding quotes, backticks, < >
        text = text.trim('\"', '\'', '`', '<', '>')

        // Unwrap markdown links: [text](url) -> keep text part
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)")) { it.groupValues[1] }

        // Remove stray [ ] ( ) that are not enclosing an IPv6 address
        val atIdx = text.indexOf('@')
        val queryIdx = text.indexOf('?', atIdx.coerceAtLeast(0))
        val hostPart = if (atIdx != -1) {
            if (queryIdx != -1) text.substring(atIdx + 1, queryIdx) else text.substring(atIdx + 1)
        } else ""

        val hasIpv6Host = hostPart.startsWith("[") && hostPart.contains("]")
        if (!hasIpv6Host) {
            text = text.replace("[", "").replace("]", "")
        } else {
            val closeBracket = text.indexOf(']', atIdx)
            val prefix = text.substring(0, atIdx).replace("[", "").replace("]", "")
            val ipv6 = text.substring(atIdx, closeBracket + 1)
            val suffix = text.substring(closeBracket + 1).replace("[", "").replace("]", "")
            text = prefix + ipv6 + suffix
        }
        text = text.replace("(", "").replace(")", "")

        // Cut at first whitespace or newline
        val firstWs = text.indexOfFirst { it.isWhitespace() }
        if (firstWs != -1) {
            text = text.substring(0, firstWs)
        }
        return text.trim()
    }

    /**
     * Splits multiple links on a single line and sanitizes each.
     */
    fun splitLinks(raw: String): List<String> {
        val pattern = Regex("(?:vmess|vless)://", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(raw).toList()
        if (matches.size <= 1) {
            val sanitized = sanitizeLink(raw)
            return if (sanitized.isNotBlank()) listOf(sanitized) else emptyList()
        }
        val result = mutableListOf<String>()
        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
            val segment = raw.substring(start, end)
            val sanitized = sanitizeLink(segment)
            if (sanitized.isNotBlank()) {
                result.add(sanitized)
            }
        }
        return result
    }

    /**
     * Parses either a vmess:// or vless:// link into a ServerProfile.
     */
    fun parse(rawInput: String): Result<ServerProfile> {
        return runCatching {
            val link = sanitizeLink(rawInput)
            when {
                link.startsWith("vless://", ignoreCase = true) -> parseVlessLink(link)
                link.startsWith("vmess://", ignoreCase = true) -> parseVmessLink(link)
                else -> throw IllegalArgumentException("Link must start with vmess:// or vless://")
            }
        }
    }

    private fun parseVlessLink(link: String): ServerProfile {
        // Standard VLESS URI format:
        // vless://[uuid]@[host]:[port]?type=[network]&security=[security]&sni=[sni]&fp=[fp]&pbk=[pbk]&sid=[sid]&flow=[flow]&serviceName=[grpcService]#remarks
        val withoutScheme = link.substring(8).trim()
        val fragmentIndex = withoutScheme.indexOf('#')
        val remarks = if (fragmentIndex != -1) {
            runCatching { URLDecoder.decode(withoutScheme.substring(fragmentIndex + 1), "UTF-8").trim() }
                .getOrDefault("VLESS Node")
        } else {
            "VLESS Node"
        }

        val mainPart = if (fragmentIndex != -1) withoutScheme.substring(0, fragmentIndex) else withoutScheme
        val queryIndex = mainPart.indexOf('?')
        val authorityPart = if (queryIndex != -1) mainPart.substring(0, queryIndex) else mainPart
        val queryPart = if (queryIndex != -1) mainPart.substring(queryIndex + 1) else ""

        val atIndex = authorityPart.lastIndexOf('@')
        if (atIndex == -1) throw IllegalArgumentException("Invalid VLESS URI: Missing '@' user delimiter")

        val uuid = authorityPart.substring(0, atIndex).trim()
        val rawHostPort = authorityPart.substring(atIndex + 1).trim()
        val hostPort = rawHostPort.removeSuffix("/").trim()

        // Robust parsing of address and port supporting IPv6 ([::1]:port), IPv4, and domain names
        val (address, port) = when {
            hostPort.startsWith("[") -> {
                val closeBracket = hostPort.indexOf(']')
                if (closeBracket == -1) throw IllegalArgumentException("Invalid IPv6 address in VLESS URI: Missing closing bracket ']'")
                val addr = hostPort.substring(1, closeBracket).trim()
                val portStr = hostPort.substring(closeBracket + 1).removePrefix(":").removeSuffix("/").trim()
                val p = portStr.toIntOrNull() ?: 443
                addr to p
            }
            hostPort.contains(":") -> {
                val colonIndex = hostPort.lastIndexOf(':')
                val addr = hostPort.substring(0, colonIndex).trim().removeSuffix("/")
                val portStr = hostPort.substring(colonIndex + 1).removePrefix(":").removeSuffix("/").trim()
                val p = portStr.toIntOrNull() ?: 443
                addr to p
            }
            else -> {
                hostPort.removeSuffix("/") to 443
            }
        }

        // Parse query parameters case-insensitively with URL decoding
        val queryMap = mutableMapOf<String, String>()
        if (queryPart.isNotEmpty()) {
            queryPart.split("&").forEach { param ->
                val eq = param.indexOf('=')
                if (eq != -1) {
                    val key = param.substring(0, eq).trim().lowercase()
                    val rawVal = param.substring(eq + 1).trim()
                    val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                    queryMap[key] = value
                }
            }
        }

        // Transport Network: tcp, ws, grpc, h2, http
        val networkRaw = queryMap["type"] ?: queryMap["net"] ?: "tcp"
        val network = networkRaw.lowercase().trim()

        // Security: reality, tls, none
        val securityRaw = queryMap["security"]?.lowercase()?.trim() ?: "none"
        val pbk = queryMap["pbk"] ?: queryMap["publickey"] ?: queryMap["public_key"] ?: ""
        val sid = queryMap["sid"] ?: queryMap["shortid"] ?: queryMap["short_id"] ?: ""
        val fp = queryMap["fp"] ?: queryMap["fingerprint"] ?: if (securityRaw == "reality" || pbk.isNotEmpty()) "chrome" else ""
        val spx = queryMap["spx"] ?: queryMap["spiderx"] ?: queryMap["spider_x"] ?: ""

        val host = queryMap["host"] ?: ""
        val path = queryMap["path"] ?: "/"
        val sni = queryMap["sni"] ?: queryMap["peer"] ?: queryMap["servername"] ?: queryMap["server_name"] ?: host.ifEmpty { address }
        val alpn = queryMap["alpn"] ?: ""
        val headerType = queryMap["headertype"] ?: queryMap["header_type"] ?: "none"

        // Flow: xtls-rprx-vision. Crucial: Xray rejects vision on gRPC/WebSocket transports!
        val rawFlow = queryMap["flow"] ?: ""
        val flow = if (network == "tcp" || network == "raw") rawFlow else ""

        // gRPC serviceName and mode
        val serviceName = queryMap["servicename"]
            ?: queryMap["service_name"]
            ?: queryMap["servicename"]
            ?: if (network == "grpc") path.removePrefix("/") else ""
        val grpcMode = queryMap["mode"] ?: "gun"

        // Determine effective TLS mode
        val tlsMode = when {
            securityRaw == "reality" || pbk.isNotEmpty() -> "reality"
            securityRaw == "tls" -> "tls"
            else -> "none"
        }

        return ServerProfile(
            name = remarks.ifEmpty { "VLESS Node" },
            protocol = "vless",
            address = address,
            port = port,
            uuid = uuid,
            alterId = 0,
            security = "none",
            network = network,
            headerType = headerType,
            host = host,
            path = path,
            tls = tlsMode,
            sni = sni,
            alpn = alpn,
            flow = flow,
            publicKey = pbk,
            shortId = sid,
            fingerprint = fp,
            spiderX = spx,
            serviceName = serviceName,
            grpcMode = grpcMode
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
        val sec = when {
            profile.isReality -> "reality"
            profile.isTlsEnabled -> "tls"
            else -> "none"
        }
        val sniPart = if (profile.sni.isNotEmpty()) "&sni=${profile.sni}" else ""
        val hostPart = if (profile.host.isNotEmpty()) "&host=${profile.host}" else ""
        val alpnPart = if (profile.alpn.isNotEmpty()) "&alpn=${profile.alpn}" else ""
        val flowPart = if (profile.flow.isNotEmpty()) "&flow=${profile.flow}" else ""
        val realityPart = if (profile.isReality) {
            "&pbk=${profile.publicKey}&sid=${profile.shortId}&fp=${profile.fingerprint.ifEmpty { "chrome" }}&spx=${URLEncoder.encode(profile.spiderX.ifEmpty { "/" }, "UTF-8")}"
        } else ""
        val grpcPart = if (profile.isGrpc) {
            "&serviceName=${URLEncoder.encode(profile.serviceName, "UTF-8")}&mode=${profile.grpcMode}"
        } else ""

        return "vless://${profile.uuid}@${profile.address}:${profile.port}?type=${profile.network}&security=$sec&path=$encodedPath$hostPart$sniPart$alpnPart$flowPart$realityPart$grpcPart#$encodedName"
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
                        // XTLS-Vision flow is ONLY supported on TCP / RAW transport with TLS or Reality!
                        // Including flow on gRPC or WebSocket will cause Xray-Core to crash or reject the config.
                        if (profile.flow.isNotEmpty() && (profile.network == "tcp" || profile.network == "raw")) {
                            put("flow", profile.flow)
                        }
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
                val isReality = profile.isReality
                put("security", when {
                    isReality -> "reality"
                    profile.isTlsEnabled -> "tls"
                    else -> "none"
                })

                if (isReality) {
                    val realitySettings = JSONObject().apply {
                        put("serverName", profile.sni.ifEmpty { profile.address })
                        put("fingerprint", profile.fingerprint.ifEmpty { "chrome" })
                        put("show", false)
                        put("publicKey", profile.publicKey)
                        put("shortId", profile.shortId)
                        put("spiderX", profile.spiderX.ifEmpty { "/" })
                    }
                    put("realitySettings", realitySettings)
                } else if (profile.isTlsEnabled) {
                    val tlsSettings = JSONObject().apply {
                        put("serverName", profile.sni.ifEmpty { profile.address })
                        put("allowInsecure", true)
                        if (profile.fingerprint.isNotEmpty()) {
                            put("fingerprint", profile.fingerprint)
                        }
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
                } else if (profile.network == "grpc") {
                    val grpcSettings = JSONObject().apply {
                        val sName = profile.serviceName.ifEmpty { profile.path.removePrefix("/") }
                        put("serviceName", sName)
                        put("multiMode", profile.grpcMode.equals("multi", ignoreCase = true))
                    }
                    put("grpcSettings", grpcSettings)
                } else if (profile.network == "tcp" && profile.headerType.equals("http", ignoreCase = true)) {
                    val tcpSettings = JSONObject().apply {
                        val header = JSONObject().apply {
                            put("type", "http")
                            val request = JSONObject().apply {
                                put("version", "1.1")
                                put("method", "GET")
                                put("path", JSONArray().put(profile.path.ifEmpty { "/" }))
                                if (profile.host.isNotEmpty()) {
                                    put("headers", JSONObject().put("Host", JSONArray().put(profile.host)))
                                }
                            }
                            put("request", request)
                        }
                        put("header", header)
                    }
                    put("tcpSettings", tcpSettings)
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

        // 4. Routing: bypass Android Captive Portal check & local networks directly
        val routingObj = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("domainMatcher", "hybrid")
            val rulesArray = JSONArray()

            // 4a. Captive portal validation bypass rule -> direct outbound
            rulesArray.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("domain", JSONArray().apply {
                    put("domain:connectivitycheck.gstatic.com")
                    put("domain:clients3.google.com")
                    put("domain:captive.apple.com")
                    put("domain:connectivitycheck.android.com")
                    put("domain:msftconnecttest.com")
                    put("domain:detectportal.firefox.com")
                    put("domain:gstatic.com")
                    put("domain:g.cn")
                    put("domain:google.cn")
                })
            })

            // 4b. Private LAN / RFC1918 IPs -> direct outbound
            rulesArray.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray().put("geoip:private"))
            })

            // 4c. All other Internet traffic -> proxy outbound
            rulesArray.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("network", "tcp,udp")
            })

            put("rules", rulesArray)
        }
        root.put("routing", routingObj)

        // 5. DNS
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8").put("localhost"))
        })

        return root.toString(2)
    }

    /**
     * Directly parses a modern VLESS URI and converts it into a valid Xray-Core JSON configuration string.
     * Handles VLESS-Reality, gRPC, and XTLS-Vision parameters safely.
     */
    fun vlessUriToXrayJson(
        uriString: String,
        socksPort: Int = 10808,
        httpPort: Int = 10809
    ): String {
        val profile = parseVlessLink(uriString.trim())
        return toV2RayConfigJson(profile, socksPort, httpPort)
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
