package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnecting
import com.example.ui.theme.StatusError
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun HotspotScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.automationSettings.collectAsState()
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val hotspotIp by viewModel.hotspotIp.collectAsState()
    val context = LocalContext.current

    var portInput by remember { mutableStateOf(settings.hotspotProxyPort.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("back_button")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "VPN Hotspot Sharing",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Master Hotspot Proxy Switch
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WifiTethering, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Allow Hotspot Clients", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                        }
                        Text(
                            text = "Binds SOCKS5/HTTP proxy to 0.0.0.0 for tethered devices",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = settings.isHotspotSharingEnabled,
                        onCheckedChange = {
                            viewModel.updateAutomationSettings(settings.copy(isHotspotSharingEnabled = it))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Discovered Hotspot Gateway & Ports
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HOTSPOT CONNECTION ENDPOINTS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Hotspot Gateway IP", fontSize = 12.sp, color = TextSecondary)
                            Text(hotspotIp, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        }
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Hotspot IP", hotspotIp))
                                viewModel.showMessage("Copied IP $hotspotIp")
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy IP", fontSize = 11.sp, color = AccentCyan)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            label = { Text("Proxy Port (SOCKS5: $portInput, HTTP: ${portInput.toIntOrNull()?.plus(1) ?: 10809})", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val p = portInput.toIntOrNull() ?: 10808
                                viewModel.updateAutomationSettings(settings.copy(hotspotProxyPort = p))
                                viewModel.showMessage("Hotspot port updated to $p")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = androidx.compose.ui.graphics.Color.Black)
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Non-Root Client Configuration Instructions
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Standard Android (Non-Root) Instructions", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Android OS restriction: Tethered Wi-Fi hotspot clients bypass the Android VpnService tunnel by default at the Linux kernel level. To route client traffic through this VPN:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBg, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("1. Connect your laptop, PC, or phone to this phone's Wi-Fi Hotspot.", color = TextPrimary, fontSize = 12.sp)
                            Text("2. Open Wi-Fi settings on that device -> Advanced -> Proxy: Manual.", color = TextPrimary, fontSize = 12.sp)
                            Text("3. Proxy Host: $hotspotIp", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("4. Proxy Port: ${settings.hotspotProxyPort + 1} (HTTP) or ${settings.hotspotProxyPort} (SOCKS5)", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("5. Save. All web and app traffic on that device now traverses your VMess VPN!", color = StatusConnected, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Root Mode Transparent Routing
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = if (isRootAvailable) StatusConnected else StatusError, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Root Transparent Redirection", fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Text(
                            text = if (isRootAvailable) "Root Available" else "Non-Root Device",
                            fontSize = 11.sp,
                            color = if (isRootAvailable) StatusConnected else TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Transparent kernel redirection uses iptables to redirect tethered interfaces (ap0, wlan1) without requiring client manual proxy settings.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isRootAvailable) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.applyRootHotspot() },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusConnected),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Apply iptables Rules", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearRootHotspot() },
                                modifier = Modifier.weight(1f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder)
                            ) {
                                Text("Flush Rules", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkBg, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "Device is not rooted. Use the Non-Root manual proxy method above for 100% reliable traffic sharing.",
                                color = StatusConnecting,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
