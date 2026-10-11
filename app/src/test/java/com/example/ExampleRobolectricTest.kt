package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ServerProfile
import com.example.model.VmessLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("v2rayDP", appName)
  }

  @Test
  fun `vless link parser and config generation`() {
    val vlessLink = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@104.16.132.229:443?type=ws&security=tls&path=%2Fvless-stream&host=vless.example.com&sni=vless.example.com#Cloudflare%20VLESS%20Node"
    val parseResult = VmessLinkParser.parse(vlessLink)
    assertTrue(parseResult.isSuccess)

    val profile = parseResult.getOrThrow()
    assertEquals("Cloudflare VLESS Node", profile.name)
    assertEquals("vless", profile.protocol)
    assertEquals("104.16.132.229", profile.address)
    assertEquals(443, profile.port)
    assertEquals("d342d11e-d424-4583-b36e-524ab1f0afa4", profile.uuid)
    assertEquals("ws", profile.network)
    assertEquals("tls", profile.tls)
    assertEquals("vless.example.com", profile.host)
    assertEquals("/vless-stream", profile.path)

    val shareLink = VmessLinkParser.toShareLink(profile)
    assertTrue(shareLink.startsWith("vless://"))

    val jsonConfig = VmessLinkParser.toV2RayConfigJson(profile)
    assertNotNull(jsonConfig)
    assertTrue(jsonConfig.contains("\"protocol\": \"vless\""))
    assertTrue(jsonConfig.contains("104.16.132.229"))
  }

  @Test
  fun `vmess link parser round trip and config generation`() {
    val profile = ServerProfile(
        name = "Cloudflare VMess Edge",
        protocol = "vmess",
        address = "104.16.132.229",
        port = 443,
        uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
        alterId = 0,
        security = "auto",
        network = "ws",
        headerType = "none",
        host = "edge-proxy.v2raydp.internal",
        path = "/v2ray-stream",
        tls = "tls",
        sni = "edge-proxy.v2raydp.internal"
    )

    val link = VmessLinkParser.toVmessLink(profile)
    assertTrue(link.startsWith("vmess://"))

    val parseResult = VmessLinkParser.parse(link)
    assertTrue(parseResult.isSuccess)
    val parsed = parseResult.getOrThrow()
    assertEquals("Cloudflare VMess Edge", parsed.name)
    assertEquals("104.16.132.229", parsed.address)
    assertEquals(443, parsed.port)
    assertEquals("d342d11e-d424-4583-b36e-524ab1f0afa4", parsed.uuid)

    val jsonConfig = VmessLinkParser.toV2RayConfigJson(parsed)
    assertNotNull(jsonConfig)
    assertTrue(jsonConfig.contains("socks-in"))
    assertTrue(jsonConfig.contains("http-in"))
    assertTrue(jsonConfig.contains("104.16.132.229"))
  }
}
