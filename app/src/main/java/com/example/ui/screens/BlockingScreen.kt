package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.block.BlockedEntry
import com.example.core.block.PresetListStatus
import com.example.ui.BlockViewModel
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnecting
import com.example.ui.theme.StatusError
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.appSwitchColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BlockingScreen(
    viewModel: BlockViewModel,
    onNavigateBack: () -> Unit
) {
    val isBlockingEnabled by viewModel.isBlockingEnabled.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val customBlocked by viewModel.customBlockedRules.collectAsStateWithLifecycle()
    val customAllowed by viewModel.customAllowedRules.collectAsStateWithLifecycle()
    val blockDoh by viewModel.blockDohProviders.collectAsStateWithLifecycle()
    val autoUpdateWeekly by viewModel.autoUpdateWeekly.collectAsStateWithLifecycle()
    val isVpnConnected by viewModel.isVpnConnected.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedCustomTab by remember { mutableIntStateOf(0) } // 0: Blocked, 1: Allowed
    var newRuleInput by remember { mutableStateOf("") }
    var testDomainInput by remember { mutableStateOf("") }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissStatusMessage()
        }
    }

    // SAF Launchers for Import and Export
    val importBlockedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importCustomRules(it, isBlock = true) }
    }

    val exportBlockedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportCustomRules(it, isBlock = true) }
    }

    val importAllowedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importCustomRules(it, isBlock = false) }
    }

    val exportAllowedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportCustomRules(it, isBlock = false) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("blocking_back_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Domain Blocking",
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Master Switch Card
                MasterBlockCard(
                    enabled = isBlockingEnabled,
                    isVpnConnected = isVpnConnected,
                    isLoading = stats.isLoading,
                    totalRules = stats.totalRuleCount,
                    warningThreshold = stats.warningThresholdExceeded,
                    onToggle = { viewModel.toggleBlocking(it) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Stats Row
                StatsSummaryCard(
                    sessionCount = stats.sessionCount,
                    totalCount = stats.totalCount,
                    onReset = { viewModel.resetStats() }
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Built-in Presets Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BLOCKLIST PRESETS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )
                    TextButton(
                        onClick = { viewModel.updateAllPresets() },
                        modifier = Modifier.testTag("update_all_presets_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Update All", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                presets.forEach { preset ->
                    PresetListItem(
                        preset = preset,
                        onToggle = { enabled -> viewModel.togglePreset(preset.id, enabled) },
                        onUpdate = { viewModel.updatePreset(preset.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Custom Rules Section
                Text(
                    text = "CUSTOM RULES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        TabRow(
                            selectedTabIndex = selectedCustomTab,
                            containerColor = Color.Transparent,
                            contentColor = AccentCyan,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedCustomTab]),
                                    color = AccentCyan
                                )
                            },
                            divider = { HorizontalDivider(color = DarkSurfaceBorder) }
                        ) {
                            Tab(
                                selected = selectedCustomTab == 0,
                                onClick = { selectedCustomTab = 0 },
                                text = {
                                    Text(
                                        "Blocked (${customBlocked.size})",
                                        fontWeight = if (selectedCustomTab == 0) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedCustomTab == 0) TextPrimary else TextSecondary
                                    )
                                }
                            )
                            Tab(
                                selected = selectedCustomTab == 1,
                                onClick = { selectedCustomTab = 1 },
                                text = {
                                    Text(
                                        "Allowed (${customAllowed.size})",
                                        fontWeight = if (selectedCustomTab == 1) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedCustomTab == 1) TextPrimary else TextSecondary
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Add Rule Field
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newRuleInput,
                                onValueChange = { newRuleInput = it },
                                placeholder = {
                                    Text(
                                        if (selectedCustomTab == 0) "e.g. ads.example.com or *.ads.com" else "e.g. safe.example.com",
                                        fontSize = 12.sp
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("custom_rule_input"),
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
                                    val trimmed = newRuleInput.trim()
                                    if (trimmed.isNotEmpty()) {
                                        viewModel.addCustomRule(trimmed, isBlock = selectedCustomTab == 0)
                                        newRuleInput = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentCyan,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("add_custom_rule_button")
                            ) {
                                Text("Add", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Rules list
                        val currentList = if (selectedCustomTab == 0) customBlocked else customAllowed
                        if (currentList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (selectedCustomTab == 0) "No custom blocked domains added" else "No custom allowed domains added",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                currentList.take(20).forEach { rule ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = rule,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                viewModel.removeCustomRule(rule, isBlock = selectedCustomTab == 0)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = StatusError.copy(alpha = 0.8f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = DarkSurfaceBorder.copy(alpha = 0.5f))
                                }
                                if (currentList.size > 20) {
                                    Text(
                                        text = "+ ${currentList.size - 20} more rules active",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Import / Export Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    if (selectedCustomTab == 0) {
                                        importBlockedLauncher.launch(arrayOf("text/plain"))
                                    } else {
                                        importAllowedLauncher.launch(arrayOf("text/plain"))
                                    }
                                }
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import", color = AccentCyan, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    if (selectedCustomTab == 0) {
                                        exportBlockedLauncher.launch("custom_blocked_rules.txt")
                                    } else {
                                        exportAllowedLauncher.launch("custom_allowed_rules.txt")
                                    }
                                }
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Export", color = AccentCyan, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Test a Domain Section
                Text(
                    text = "TEST A DOMAIN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = testDomainInput,
                                onValueChange = {
                                    testDomainInput = it
                                    if (testResult != null) viewModel.clearTestResult()
                                },
                                placeholder = { Text("e.g. ads.google.com or doubleclick.net", fontSize = 12.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_domain_input"),
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
                                    if (testDomainInput.isNotBlank()) {
                                        viewModel.testDomain(testDomainInput)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentCyan,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("test_domain_button")
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (testResult != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val isBlocked = testResult!!.startsWith("Blocked", ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isBlocked) StatusError.copy(alpha = 0.15f) else StatusConnected.copy(alpha = 0.15f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isBlocked) StatusError.copy(alpha = 0.4f) else StatusConnected.copy(alpha = 0.4f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (isBlocked) StatusError else StatusConnected,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = testResult!!,
                                        color = if (isBlocked) StatusError else StatusConnected,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Recent Blocked Section
                Text(
                    text = "RECENT BLOCKED REQUESTS (${stats.recentBlocked.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        if (stats.recentBlocked.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (!isBlockingEnabled) "Blocking is turned OFF" else if (!isVpnConnected) "Connect the VPN to start intercepting domains" else "No blocked connections recorded yet",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                stats.recentBlocked.take(15).forEach { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.host,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = entry.rule,
                                                    color = StatusError,
                                                    fontSize = 11.sp
                                                )
                                                Text(
                                                    text = " • ${timeFormat.format(Date(entry.timestamp))}",
                                                    color = TextMuted,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        TextButton(
                                            onClick = { viewModel.allowDomain(entry.host) },
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text("Allow", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    HorizontalDivider(color = DarkSurfaceBorder.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Advanced Settings (DoH & Auto-update)
                Text(
                    text = "ADVANCED OPTIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // DoH blocking toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Block common DNS-over-HTTPS/TLS providers",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Prevents third-party apps with private encrypted DNS from bypassing domain blocking. Also blocks port 853.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = blockDoh,
                                onCheckedChange = { viewModel.toggleBlockDoh(it) },
                                colors = appSwitchColors()
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = DarkSurfaceBorder)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Weekly Auto-update toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Auto-update weekly",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Runs in background once every 7 days only when device is connected to unmetered Wi-Fi and charging.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = autoUpdateWeekly,
                                onCheckedChange = { viewModel.toggleAutoUpdateWeekly(it) },
                                colors = appSwitchColors()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Notice Footer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Blocking works on domain names. Apps that connect directly to an IP address or use their own encrypted DNS may not be blocked.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun MasterBlockCard(
    enabled: Boolean,
    isVpnConnected: Boolean,
    isLoading: Boolean,
    totalRules: Int,
    warningThreshold: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (enabled) AccentCyan.copy(alpha = 0.5f) else DarkSurfaceBorder,
                RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                if (enabled) AccentCyan.copy(alpha = 0.15f) else DarkSurface,
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (enabled) AccentCyan else DarkSurfaceBorder,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (enabled) AccentCyan else TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Enable Domain Blocking",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Works while the VPN is connected",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = appSwitchColors(),
                    modifier = Modifier.testTag("master_blocking_switch")
                )
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading blocklists into memory…", color = AccentCyan, fontSize = 11.sp)
                }
            }

            if (!isVpnConnected && enabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusConnecting.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Connect the VPN to apply blocking",
                        color = StatusConnecting,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (warningThreshold) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "High rule count ($totalRules domains). May impact memory.",
                        color = StatusError,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsSummaryCard(
    sessionCount: Long,
    totalCount: Long,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BLOCKED THIS SESSION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sessionCount.toString(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(DarkSurfaceBorder)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = "BLOCKED TOTAL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = totalCount.toString(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            IconButton(
                onClick = onReset,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("reset_stats_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Stats",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PresetListItem(
    preset: PresetListStatus,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val lastUpdatedText = if (preset.lastUpdated > 0) {
        "Updated ${dateFormat.format(Date(preset.lastUpdated))}"
    } else {
        "Not downloaded yet"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 14.sp
                )
                Text(
                    text = preset.description,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(DarkSurfaceVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${preset.entryCount} domains",
                            fontSize = 10.sp,
                            color = AccentCyan,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = lastUpdatedText,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onUpdate,
                    enabled = !preset.isUpdating,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("update_preset_${preset.id}")
                ) {
                    if (preset.isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AccentCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Update list",
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = preset.enabled,
                    onCheckedChange = onToggle,
                    colors = appSwitchColors(),
                    modifier = Modifier.testTag("toggle_preset_${preset.id}")
                )
            }
        }
    }
}
