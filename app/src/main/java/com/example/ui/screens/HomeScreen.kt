package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.ConnectionState
import com.example.model.ServerProfile
import com.example.model.VmessLinkParser
import com.example.ui.MainViewModel
import com.example.ui.components.LatencyBadge
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusDisconnected
import com.example.ui.theme.StatusError
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class DrawerItemData(
    val title: String,
    val icon: ImageVector,
    val testTag: String,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToQr: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    onNavigateToHotspot: () -> Unit,
    onNavigateToBlocking: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onEditProfile: (ServerProfile) -> Unit,
    onAddManualServer: (String) -> Unit, // "vless" or "vmess"
    onPrepareVpn: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestBackgroundRun: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val profiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val isPingingAll by viewModel.isPingingAll.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val isRunning = connectionState.isRunning
    var showAddMenu by remember { mutableStateOf(false) }
    var jsonConfigToView by remember { mutableStateOf<String?>(null) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // File import picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFromFile(it) }
    }

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
                .padding(16.dp)
        ) {
            // Top App Bar with hamburger icon, app icon + title, Test all icon, QR icon, "+" menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Left: Hamburger + App Icon & Title
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

                // Top Right: Test All, QR Scanner, "+" Add menu
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Test all latencies icon button
                    IconButton(
                        onClick = { viewModel.testAllLatencies() },
                        enabled = !isPingingAll && profiles.isNotEmpty(),
                        modifier = Modifier.testTag("test_all_latencies_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Test all latencies",
                            tint = if (isPingingAll) AccentCyan.copy(alpha = 0.5f) else AccentCyan
                        )
                    }

                    // QR Scanner icon
                    IconButton(
                        onClick = onNavigateToQr,
                        modifier = Modifier.testTag("top_bar_qr_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code", tint = AccentCyan)
                    }

                    // Add / Import icon dropdown ("+" menu)
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
                                text = { Text("Import from clipboard", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showAddMenu = false
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!clip.isNullOrEmpty()) {
                                        viewModel.importMultipleLinks(clip)
                                    } else {
                                        viewModel.showMessage("Clipboard is empty")
                                    }
                                },
                                modifier = Modifier.testTag("menu_import_clipboard")
                            )
                            DropdownMenuItem(
                                text = { Text("Scan QR code", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToQr()
                                },
                                modifier = Modifier.testTag("menu_scan_qr")
                            )
                            DropdownMenuItem(
                                text = { Text("Import from file", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showAddMenu = false
                                    filePickerLauncher.launch(arrayOf("text/*", "*/*"))
                                },
                                modifier = Modifier.testTag("menu_import_file")
                            )
                            HorizontalDivider(color = DarkSurfaceBorder, thickness = 0.8.dp)
                            DropdownMenuItem(
                                text = { Text("Add manually: VLESS", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showAddMenu = false
                                    onAddManualServer("vless")
                                },
                                modifier = Modifier.testTag("menu_add_vless")
                            )
                            DropdownMenuItem(
                                text = { Text("Add manually: VMess", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showAddMenu = false
                                    onAddManualServer("vmess")
                                },
                                modifier = Modifier.testTag("menu_add_vmess")
                            )
                        }
                    }
                }
            }

            // Status Row: Connected / Disconnected Badge
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
                    Text(
                        text = "${profiles.size} profile${if (profiles.size != 1) "s" else ""}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            // Profile List: LazyColumn filling the remaining space (weight = 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (profiles.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = "No profiles yet",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Import or add a server profile to get started",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clip.isNullOrEmpty()) {
                                            viewModel.importMultipleLinks(clip)
                                        } else {
                                            viewModel.showMessage("Clipboard is empty")
                                        }
                                    },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                                    modifier = Modifier.testTag("empty_import_clipboard")
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import from clipboard", fontSize = 12.sp, color = TextPrimary)
                                }
                                Button(
                                    onClick = { onAddManualServer("vless") },
                                    modifier = Modifier.testTag("empty_add_manually")
                                ) {
                                    Text("Add manually", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("profiles_lazy_column"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            ProfileRow(
                                profile = profile,
                                isSelected = profile.isSelected,
                                onSelect = {
                                    viewModel.selectProfile(profile)
                                },
                                onTestPing = { viewModel.testProfileLatency(profile) },
                                onEdit = { onEditProfile(profile) },
                                onCopyLink = {
                                    val link = VmessLinkParser.toShareLink(profile)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("${profile.protocol.uppercase()} Link", link))
                                    viewModel.showMessage("Copied ${profile.protocol.uppercase()} link")
                                },
                                onExportJson = {
                                    val json = VmessLinkParser.toV2RayConfigJson(profile)
                                    jsonConfigToView = json
                                },
                                onDelete = {
                                    val deleted = profile
                                    viewModel.deleteProfile(deleted)
                                    coroutineScope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Deleted '${deleted.name}'",
                                            actionLabel = "Undo",
                                            duration = SnackbarDuration.Short
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.restoreProfile(deleted)
                                        }
                                    }
                                }
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Start / Stop Button pinned at the bottom
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

    // Exported V2Ray JSON Dialog
    if (jsonConfigToView != null) {
        AlertDialog(
            onDismissRequest = { jsonConfigToView = null },
            title = { Text("V2Ray Core JSON Config", color = TextPrimary) },
            text = {
                Column {
                    Text("Official V2Ray/Xray configuration:", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBg, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = jsonConfigToView!!,
                            color = AccentCyan,
                            fontSize = 10.sp,
                            maxLines = 14
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("V2Ray JSON", jsonConfigToView))
                        viewModel.showMessage("Copied V2Ray JSON to clipboard")
                        jsonConfigToView = null
                    }
                ) {
                    Text("Copy JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { jsonConfigToView = null }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTestPing: () -> Unit,
    onEdit: () -> Unit,
    onCopyLink: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) AccentCyan else DarkSurfaceBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onSelect)
            .testTag("profile_item_${profile.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Radio/Check Icon + Profile info
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint = if (isSelected) AccentCyan else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProtocolPill(
                                label = profile.protocol.uppercase(),
                                isHighlight = profile.isVless
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = profile.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) AccentCyan else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = profile.displaySubtitle,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Latency Badge + More Menu
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LatencyBadge(
                        latencyMs = profile.pingLatencyMs,
                        modifier = Modifier.clickable { onTestPing() }
                    )

                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(36.dp).testTag("more_menu_${profile.id}")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            modifier = Modifier.background(DarkSurfaceVariant)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showMoreMenu = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy link", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showMoreMenu = false
                                    onCopyLink()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export V2Ray JSON", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showMoreMenu = false
                                    onExportJson()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Test latency", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null, tint = AccentCyan) },
                                onClick = {
                                    showMoreMenu = false
                                    onTestPing()
                                }
                            )
                            HorizontalDivider(color = DarkSurfaceBorder, thickness = 0.8.dp)
                            DropdownMenuItem(
                                text = { Text("Delete", color = Color(0xFFEF4444)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444)) },
                                onClick = {
                                    showMoreMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolPill(label: String, isHighlight: Boolean = false) {
    Box(
        modifier = Modifier
            .background(
                if (isHighlight) AccentCyan.copy(alpha = 0.15f) else DarkBg,
                RoundedCornerShape(4.dp)
            )
            .border(
                1.dp,
                if (isHighlight) AccentCyan.copy(alpha = 0.5f) else DarkSurfaceBorder,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isHighlight) AccentCyan else TextSecondary
        )
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
