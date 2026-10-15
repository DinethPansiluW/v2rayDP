package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ConnectionState
import com.example.ui.MainViewModel
import com.example.ui.components.LatencyBadge
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnecting
import com.example.ui.theme.StatusDisconnected
import com.example.ui.theme.StatusError
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToServers: () -> Unit,
    onNavigateToQr: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    onNavigateToHotspot: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onAddManualServer: (String) -> Unit, // "vless" or "vmess"
    onPrepareVpn: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestBackgroundRun: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val trafficStats by viewModel.trafficStats.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val currentSsid by viewModel.automationManager.currentSsid.collectAsState()
    val isWifiConnected by viewModel.automationManager.isWifiConnected.collectAsState()
    val isCellularConnected by viewModel.automationManager.isCellularConnected.collectAsState()
    val automationSettings by viewModel.automationSettings.collectAsState()
    val lastAutomationStatus by viewModel.automationManager.lastAutomationStatus.collectAsState()
    val isBatteryIgnored by viewModel.isIgnoringBatteryOptimizations.collectAsState()
    val hasLocPerm by viewModel.hasLocationPermission.collectAsState()

    val context = LocalContext.current
    val isRunning = connectionState.isRunning
    var showAddMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Top App Bar with app icon in top-left, v2rayNG-style quick action icons on top-right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Left App Icon & Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(DarkSurfaceVariant, CircleShape)
                        .border(1.5.dp, AccentCyan, CircleShape)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "v2rayDP App Icon",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "v2rayDP",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "VLESS & VMess Proxy",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            // Top Right v2rayNG-Style Quick Action Icons
            Row(verticalAlignment = Alignment.CenterVertically) {
                // QR Scanner icon
                IconButton(
                    onClick = onNavigateToQr,
                    modifier = Modifier.testTag("top_bar_qr_button")
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code", tint = AccentCyan)
                }

                // Add / Import icon dropdown (like v2rayNG)
                Box {
                    IconButton(
                        onClick = { showAddMenu = true },
                        modifier = Modifier.testTag("top_bar_add_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add / Import", tint = TextPrimary)
                    }
                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false },
                        modifier = Modifier.background(DarkSurfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import from Clipboard", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, tint = AccentCyan) },
                            onClick = {
                                showAddMenu = false
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrEmpty() && (clip.startsWith("vmess://") || clip.startsWith("vless://"))) {
                                    viewModel.importVmessLink(clip)
                                } else {
                                    viewModel.showMessage("No vless:// or vmess:// link in clipboard")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Scan / Show QR Code", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = AccentCyan) },
                            onClick = { showAddMenu = false; onNavigateToQr() }
                        )
                    }
                }
            }
        }

        // Connection State Pill Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (val state = connectionState) {
                is ConnectionState.Connected -> StatusBadge("Connected to ${state.profile.name}", StatusConnected)
                is ConnectionState.Connecting -> StatusBadge(state.step, StatusConnecting)
                is ConnectionState.Error -> StatusBadge("Error: ${state.message}", StatusError)
                is ConnectionState.Disconnected -> StatusBadge("Disconnected", StatusDisconnected)
            }

            if (activeProfile != null) {
                LatencyBadge(latencyMs = activeProfile!!.pingLatencyMs)
            }
        }

        // Active Profile Card ("Switch Profile")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
                .clickable { onNavigateToServers() }
                .testTag("active_profile_card"),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CURRENT PROFILE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Switch Profile",
                            fontSize = 12.sp,
                            color = AccentCyan,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (activeProfile != null) {
                    val p = activeProfile!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(if (p.isVless) AccentCyan.copy(alpha = 0.2f) else Color(0xFF2563EB).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .border(1.dp, if (p.isVless) AccentCyan else Color(0xFF2563EB), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = p.protocol.uppercase(),
                                color = if (p.isVless) AccentCyan else Color(0xFF60A5FA),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = p.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = p.displaySubtitle,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LatencyBadge(latencyMs = p.pingLatencyMs)
                    }
                } else {
                    Text("No server profile selected", color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onNavigateToServers) {
                        Text("Add or Select Server")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Unified 1-Button Start / Stop Service Control
        val isConnecting = connectionState is ConnectionState.Connecting
        val buttonBgColor = when {
            isRunning -> StatusError
            isConnecting -> StatusConnecting
            else -> StatusConnected
        }
        val buttonText = when {
            isRunning -> "Stop Service"
            isConnecting -> "Connecting…"
            else -> "Start Service"
        }

        Button(
            onClick = {
                if (isRunning || isConnecting) {
                    viewModel.stopVpn("User tapped stop service")
                } else {
                    onPrepareVpn()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("vpn_toggle_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonBgColor,
                contentColor = Color.White
            )
        ) {
            if (isConnecting) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp
                )
            } else {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = buttonText,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Navigation Tiles
        Text(
            text = "FEATURES",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        QuickActionRow(
            icon = Icons.Default.Autorenew,
            title = "Automation & Rules",
            subtitle = "Wi-Fi SSID, radio off & mobile rules",
            onClick = onNavigateToAutomation
        )

        HorizontalDivider(color = DarkSurfaceBorder, thickness = 0.8.dp)

        QuickActionRow(
            icon = Icons.Default.WifiTethering,
            title = "VPN Hotspot Sharing",
            subtitle = "Share VPN tunnel via Wi-Fi hotspot",
            onClick = onNavigateToHotspot
        )

        HorizontalDivider(color = DarkSurfaceBorder, thickness = 0.8.dp)

        QuickActionRow(
            icon = Icons.Default.Info,
            title = "Logs & Diagnostics",
            subtitle = "Inspect live triggers and core events",
            onClick = onNavigateToLogs
        )
    }
}

@Composable
private fun QuickActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(DarkSurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}
