package com.example

import com.example.model.VmessLinkParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VlessRealityTest {

    @Test
    fun parseVlessReality_tcpVision_extractsAllParameters() {
        val link = "vless://27f5251a-7b3b-4c47-b52e-c5332e185f29@my-reality-server.com:443" +
                "?security=reality&encryption=none&pbk=c4t3-vQp8j3Z_examplePublicKey1234567890abc" +
                "&sid=6ba85179e30d4fc2&fp=chrome&sni=itunes.apple.com&type=tcp" +
                "&flow=xtls-rprx-vision&spx=%2F#My%20Reality%20Node"

        val profile = VmessLinkParser.parse(link).getOrThrow()
        assertEquals("My Reality Node", profile.name)
        assertEquals("vless", profile.protocol)
        assertEquals("my-reality-server.com", profile.address)
        assertEquals(443, profile.port)
        assertEquals("27f5251a-7b3b-4c47-b52e-c5332e185f29", profile.uuid)
        assertEquals("tcp", profile.network)
        assertEquals("reality", profile.tls)
        assertEquals("c4t3-vQp8j3Z_examplePublicKey1234567890abc", profile.publicKey)
        assertEquals("6ba85179e30d4fc2", profile.shortId)
        assertEquals("chrome", profile.fingerprint)
        assertEquals("itunes.apple.com", profile.sni)
        assertEquals("xtls-rprx-vision", profile.flow)

        val jsonString = VmessLinkParser.vlessUriToXrayJson(link)
        val json = JSONObject(jsonString)
        val outbounds = json.getJSONArray("outbounds")
        val proxyOutbound = outbounds.getJSONObject(0)
        assertEquals("vless", proxyOutbound.getString("protocol"))

        val streamSettings = proxyOutbound.getJSONObject("streamSettings")
        assertEquals("tcp", streamSettings.getString("network"))
        assertEquals("reality", streamSettings.getString("security"))

        val realitySettings = streamSettings.getJSONObject("realitySettings")
        assertEquals("itunes.apple.com", realitySettings.getString("serverName"))
        assertEquals("c4t3-vQp8j3Z_examplePublicKey1234567890abc", realitySettings.getString("publicKey"))
        assertEquals("6ba85179e30d4fc2", realitySettings.getString("shortId"))
        assertEquals("chrome", realitySettings.getString("fingerprint"))

        val userObj = proxyOutbound.getJSONObject("settings")
            .getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals("xtls-rprx-vision", userObj.getString("flow"))
    }

    @Test
    fun parseVlessReality_grpcTransport_extractsServiceNameAndGuardsFlow() {
        val link = "vless://27f5251a-7b3b-4c47-b52e-c5332e185f29@grpc-server.com:8443" +
                "?security=reality&pbk=c4t3-vQp8j3Z_examplePublicKey1234567890abc" +
                "&sid=1a2b3c&fp=firefox&sni=speed.cloudflare.com&type=grpc" +
                "&serviceName=my-grpc-tunnel&mode=multi&flow=xtls-rprx-vision#gRPC%20Node"

        val profile = VmessLinkParser.parse(link).getOrThrow()
        assertEquals("gRPC Node", profile.name)
        assertEquals("grpc", profile.network)
        assertEquals("reality", profile.tls)
        assertEquals("my-grpc-tunnel", profile.serviceName)
        assertEquals("multi", profile.grpcMode)
        // XTLS-Vision flow should be cleared for non-tcp transports to protect Xray
        assertEquals("", profile.flow)

        val jsonString = VmessLinkParser.vlessUriToXrayJson(link)
        val json = JSONObject(jsonString)
        val proxyOutbound = json.getJSONArray("outbounds").getJSONObject(0)
        val streamSettings = proxyOutbound.getJSONObject("streamSettings")
        assertEquals("grpc", streamSettings.getString("network"))
        assertEquals("reality", streamSettings.getString("security"))

        val grpcSettings = streamSettings.getJSONObject("grpcSettings")
        assertEquals("my-grpc-tunnel", grpcSettings.getString("serviceName"))
        assertTrue(grpcSettings.getBoolean("multiMode"))

        val userObj = proxyOutbound.getJSONObject("settings")
            .getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertFalse("flow must not be present for gRPC transport", userObj.has("flow"))
    }

    @Test
    fun parseVlessTls_sniSpoofing_extractsCleanHostPort() {
        val userConfig = "vless://17c4f486-a139-404f-ac4d-144d335332c7@172.237.88.76:443/?encryption=none&security=tls&fp=chrome&sni=web.youtube.com&type=tcp&headerType=none#Nilmi-yt"
        val profile = VmessLinkParser.parse(userConfig).getOrThrow()

        assertEquals("Nilmi-yt", profile.name)
        assertEquals("vless", profile.protocol)
        assertEquals("172.237.88.76", profile.address)
        assertEquals(443, profile.port)
        assertEquals("17c4f486-a139-404f-ac4d-144d335332c7", profile.uuid)
        assertEquals("tcp", profile.network)
        assertEquals("tls", profile.tls)
        assertEquals("web.youtube.com", profile.sni)
        assertEquals("chrome", profile.fingerprint)
        assertEquals("none", profile.headerType)
    }

    @Test
    fun xrayJson_containsCaptivePortalBypassRouting() {
        val config = "vless://17c4f486-a139-404f-ac4d-144d335332c7@172.237.88.76:443/?encryption=none&security=tls&fp=chrome&sni=web.youtube.com&type=tcp&headerType=none#Nilmi-yt"
        val jsonStr = VmessLinkParser.vlessUriToXrayJson(config)
        val json = JSONObject(jsonStr)

        val routing = json.getJSONObject("routing")
        assertEquals("IPIfNonMatch", routing.getString("domainStrategy"))

        val rules = routing.getJSONArray("rules")
        val captiveRule = rules.getJSONObject(0)
        assertEquals("direct", captiveRule.getString("outboundTag"))

        val domains = captiveRule.getJSONArray("domain")
        val domainList = (0 until domains.length()).map { domains.getString(it) }
        assertTrue(domainList.contains("domain:connectivitycheck.gstatic.com"))
        assertTrue(domainList.contains("domain:clients3.google.com"))
        assertTrue(domainList.contains("domain:captive.apple.com"))
    }
}
