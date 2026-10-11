package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ServerProfile
import com.example.model.VmessLinkParser
import com.example.ui.MainViewModel
import com.example.ui.components.LatencyBadge
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ServerProfilesScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (ServerProfile) -> Unit
) {
    val profiles by viewModel.allProfiles.collectAsState()
    val isPingingAll by viewModel.isPingingAll.collectAsState()
    val context = LocalContext.current

    var profileToDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var jsonConfigToView by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Server Profiles",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }

                Row {
                    OutlinedButton(
                        onClick = { viewModel.testAllLatencies() },
                        enabled = !isPingingAll,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("ping_all_button"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPingingAll) "Testing…" else "Ping All",
                            fontSize = 12.sp,
                            color = AccentCyan
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddProfile,
                containerColor = AccentCyan,
                contentColor = Color.Black,
                modifier = Modifier.testTag("add_server_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Server")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Quick Import from Clipboard Button
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!clip.isNullOrEmpty() && (clip.startsWith("vmess://", ignoreCase = true) || clip.startsWith("vless://", ignoreCase = true))) {
                        viewModel.importVmessLink(clip)
                    } else {
                        viewModel.showMessage("No vless:// or vmess:// link found in clipboard")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("clipboard_import_button"),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = DarkSurface)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import VLESS / VMess from Clipboard", color = TextPrimary, fontSize = 13.sp)
            }

            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No server profiles configured", color = TextSecondary, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onAddProfile) {
                            Text("Add First Profile")
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ServerProfileItem(
                            profile = profile,
                            isSelected = profile.isSelected,
                            onSelect = { viewModel.selectProfile(profile) },
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
                            onDelete = { profileToDelete = profile }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (profileToDelete != null) {
        val p = profileToDelete!!
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete Profile", color = TextPrimary) },
            text = { Text("Are you sure you want to delete '${p.name}'?", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProfile(p)
                        profileToDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // View Exported JSON dialog
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
private fun ServerProfileItem(
    profile: ServerProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTestPing: () -> Unit,
    onEdit: () -> Unit,
    onCopyLink: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) AccentCyan else DarkSurfaceBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onSelect)
            .testTag("server_item_${profile.id}"),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) AccentCyan else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = profile.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) AccentCyan else TextPrimary
                        )
                        Text(
                            text = "${profile.address}:${profile.port}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(DarkSurfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Server", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = AccentCyan) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy vmess:// Link", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AccentCyan) },
                            onClick = { showMenu = false; onCopyLink() }
                        )
                        DropdownMenuItem(
                            text = { Text("Export V2Ray JSON", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, tint = AccentCyan) },
                            onClick = { showMenu = false; onExportJson() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = Color(0xFFEF4444)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444)) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges row: Transport, TLS, Ping
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProtocolPill(label = profile.protocol.uppercase(), isHighlight = profile.isVless)
                    ProtocolPill(label = profile.network.uppercase())
                    if (profile.isTlsEnabled) {
                        ProtocolPill(label = "TLS", isHighlight = true)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    LatencyBadge(latencyMs = profile.pingLatencyMs)
                    IconButton(onClick = onTestPing, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Speed, contentDescription = "Ping", tint = TextSecondary, modifier = Modifier.size(16.dp))
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
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (isHighlight) AccentCyan.copy(alpha = 0.4f) else DarkSurfaceBorder,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isHighlight) AccentCyan else TextSecondary
        )
    }
}
