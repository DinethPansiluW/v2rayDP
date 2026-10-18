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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ConnectionState
import com.example.model.ServerProfile
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch
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

private data class DrawerItemData(
    val title: String,
    val icon: ImageVector,
    val testTag: String,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToServers: () -> Unit,
    onNavigateToQr: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    onNavigateToHotspot: () -> Unit,
    onNavigateToBlocking: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onAddManualServer: (String) -> Unit, // "vless" or "vmess"
    onPrepareVpn: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestBackgroundRun: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()

    val context = LocalContext.current
    val isRunning = connectionState.isRunning
    var showAddMenu by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = DarkSurface,
                drawerContentColor = TextPrimary,
                modifier = Modifier.width(300.dp)
            ) {
                AppDrawerHeader(
                    connectionState = connectionState,
                    activeProfile = activeProfile
                )
                HorizontalDivider(color = DarkSurfaceBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                val drawerItems = remember(
                    onNavigateToAutomation,
                    onNavigateToHotspot,
                    onNavigateToBlocking,
                    onNavigateToLogs
                ) {
                    listOf(
                        DrawerItemData("Automation & Rules", Icons.Default.Autorenew, "drawer_item_automation") {
                            coroutineScope.launch { drawerState.close() }
                            onNavigateToAutomation()
                        },
                        DrawerItemData("VPN Hotspot Sharing", Icons.Default.WifiTethering, "drawer_item_hotspot") {
                            coroutineScope.launch { drawerState.close() }
                            onNavigateToHotspot()
                        },
                        DrawerItemData("Add Blocking", Icons.Default.Shield, "drawer_item_blocking") {
                            coroutineScope.launch { drawerState.close() }
                            onNavigateToBlocking()
                        },
                        DrawerItemData("Logs & Diagnostics", Icons.Default.Info, "drawer_item_logs") {
                            coroutineScope.launch { drawerState.close() }
                            onNavigateToLogs()
                        }
                    )
                }

                drawerItems.forEach { item ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = AccentCyan
                            )
                        },
                        label = {
                            Text(
                                text = item.title,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        },
                        selected = false,
                        onClick = item.onClick,
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent,
                            selectedContainerColor = DarkSurfaceVariant
                        ),
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .testTag(item.testTag)
                    )
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Top App Bar with hamburger icon, app icon + title on top-left, v2rayNG-style quick action icons on top-right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Left Hamburger + App Icon & Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                drawerState.open()
                            }
                        },
                        modifier = Modifier.testTag("hamburger_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Navigation Menu",
                            tint = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box(
                        modifier = Modifier
                            .size(38.dp)
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

            // Connection State Pill Banner (Only Connected or Disconnected, no orange)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (connectionState) {
                    is ConnectionState.Connected -> StatusBadge("Connected", StatusConnected)
                    else -> StatusBadge("Disconnected", StatusDisconnected)
                }

                if (activeProfile != null) {
                    LatencyBadge(
                        latencyMs = activeProfile!!.pingLatencyMs,
                        modifier = Modifier.clickable {
                            viewModel.testProfileLatency(activeProfile!!)
                        }
                    )
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
            val buttonBgColor = if (isRunning) StatusError else StatusConnected
            val buttonText = if (isRunning) "Stop Service" else "Start Service"

            Button(
                onClick = {
                    if (isRunning) {
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
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = buttonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun AppDrawerHeader(
    connectionState: ConnectionState,
    activeProfile: ServerProfile?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(DarkSurface, CircleShape)
                    .border(1.5.dp, AccentCyan, CircleShape)
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "v2rayDP App Logo",
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
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

        Spacer(modifier = Modifier.height(16.dp))

        // Live connection status: only Connected or Disconnected
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isConnected = connectionState is ConnectionState.Connected
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (isConnected) StatusConnected else StatusDisconnected,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isConnected) "Connected" else "Disconnected",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isConnected) StatusConnected else StatusDisconnected
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Active profile name
        Text(
            text = activeProfile?.name ?: "No Profile Selected",
            fontSize = 12.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
