package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.WifiLeavePolicy
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestBackgroundRun: () -> Unit
) {
    val settings by viewModel.automationSettings.collectAsState()
    val currentSsid by viewModel.automationManager.currentSsid.collectAsState()
    val isWifiConnected by viewModel.automationManager.isWifiConnected.collectAsState()
    val profiles by viewModel.allProfiles.collectAsState()
    val isBatteryIgnored by viewModel.isIgnoringBatteryOptimizations.collectAsState()
    val hasLocPerm by viewModel.hasLocationPermission.collectAsState()

    var newSsidInput by remember { mutableStateOf("") }
    var showPolicyMenu by remember { mutableStateOf(false) }
    var showWifiProfileMenu by remember { mutableStateOf(false) }
    var showMobileProfileMenu by remember { mutableStateOf(false) }

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
                text = "Automation & Rules",
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
            // Master Automation Switch
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
                        Text("Master Automation Engine", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                        Text("Intelligently controls VPN based on network states", color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = settings.isAutomationGloballyEnabled,
                        onCheckedChange = {
                            viewModel.updateAutomationSettings(settings.copy(isAutomationGloballyEnabled = it))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Background Run & Permissions Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("BACKGROUND RUN & PERMISSIONS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        IconButton(
                            onClick = { viewModel.refreshPermissionsState() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Run (Battery Optimization)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                if (isBatteryIgnored) "Unrestricted • Will not be killed by OS" else "Restricted • Android may kill background VPN",
                                fontSize = 11.sp,
                                color = if (isBatteryIgnored) StatusConnected else StatusConnecting
                            )
                        }
                        if (!isBatteryIgnored) {
                            Button(
                                onClick = onRequestBackgroundRun,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = androidx.compose.ui.graphics.Color.Black),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp).testTag("automation_enable_background_run_button")
                            ) {
                                Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .background(StatusConnected.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Enabled", fontSize = 11.sp, color = StatusConnected, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = DarkSurfaceBorder, thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wi-Fi SSID Permission (Location)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                if (hasLocPerm) "Granted • Can read current Wi-Fi name" else "Required by Android to detect Wi-Fi network SSID",
                                fontSize = 11.sp,
                                color = if (hasLocPerm) StatusConnected else TextSecondary
                            )
                        }
                        if (!hasLocPerm) {
                            Button(
                                onClick = onRequestLocationPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = AccentCyan),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp).testTag("automation_grant_location_button")
                            ) {
                                Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .background(StatusConnected.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Granted", fontSize = 11.sp, color = StatusConnected, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Diagnostic & Force Test Trigger
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("LIVE STATE & TEST", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        Button(
                            onClick = { viewModel.automationManager.forceEvaluateNow() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = androidx.compose.ui.graphics.Color.Black),
                            modifier = Modifier.height(30.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text("Test Rules Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val lastStatus by viewModel.automationManager.lastAutomationStatus.collectAsState()
                    val isRadioOn by viewModel.automationManager.isWifiRadioEnabled.collectAsState()

                    Text(
                        text = "• Wi-Fi Radio: ${if (isRadioOn) "ON (Enabled)" else "OFF (Disabled)"}",
                        fontSize = 12.sp,
                        color = if (isRadioOn) StatusConnected else StatusError
                    )
                    Text(
                        text = "• Wi-Fi Connection: ${if (isWifiConnected) "Connected (${currentSsid ?: "Wi-Fi"})" else "Disconnected"}",
                        fontSize = 12.sp,
                        color = if (isWifiConnected) StatusConnected else TextSecondary
                    )
                    Text(
                        text = "• Automation Status: $lastStatus",
                        fontSize = 12.sp,
                        color = AccentCyan
                    )
                }
            }

            // Manual Override Alert
            if (settings.manualOverrideSuppressed) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StatusConnecting.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = StatusConnecting.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusConnecting, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Manual Override Mode Active", fontWeight = FontWeight.Bold, color = StatusConnecting)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Automatic re-connect is temporarily suppressed because you stopped the VPN manually (${settings.manualOverrideReason}).",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.clearManualOverride() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = androidx.compose.ui.graphics.Color.Black),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Resume Automation Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Wi-Fi Automation Section
            Text(
                text = "WI-FI NETWORK AUTOMATION",
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
                Column(modifier = Modifier.padding(16.dp)) {
                    // A. Auto-start on selected Wi-Fi
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-start on approved Wi-Fi", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Connects VPN when connected to target SSID", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.autoStartOnWifi,
                            onCheckedChange = { viewModel.updateAutomationSettings(settings.copy(autoStartOnWifi = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Target SSIDs Management
                    Text("Configured Target SSIDs:", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))

                    if (settings.targetWifiSsids.isEmpty()) {
                        Text(
                            text = "All Wi-Fi networks allowed (empty list)",
                            fontSize = 12.sp,
                            color = AccentCyan
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            settings.targetWifiSsids.forEach { ssid ->
                                Box(
                                    modifier = Modifier
                                        .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(ssid, color = TextPrimary, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = TextSecondary,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clickable { viewModel.removeTargetSsid(ssid) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Input to add SSID or add current active SSID
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newSsidInput,
                            onValueChange = { newSsidInput = it },
                            placeholder = { Text("Enter Wi-Fi SSID", fontSize = 12.sp) },
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
                                viewModel.addTargetSsid(newSsidInput)
                                newSsidInput = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = androidx.compose.ui.graphics.Color.Black)
                        ) {
                            Text("Add")
                        }
                    }

                    if (isWifiConnected && currentSsid != null && !settings.targetWifiSsids.contains(currentSsid)) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = { viewModel.addTargetSsid(currentSsid!!) }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add current Wi-Fi ('$currentSsid')", color = AccentCyan, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Dedicated Profile for Wi-Fi
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Wi-Fi Server Profile", fontSize = 13.sp, color = TextPrimary)
                            val profName = profiles.find { it.id == settings.wifiProfileId }?.name ?: "Active Selected Profile"
                            Text(profName, fontSize = 12.sp, color = AccentCyan)
                        }
                        Box {
                            OutlinedButton(onClick = { showWifiProfileMenu = true }) {
                                Text("Choose Profile", fontSize = 11.sp)
                            }
                            DropdownMenu(
                                expanded = showWifiProfileMenu,
                                onDismissRequest = { showWifiProfileMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Active Selected Profile", color = TextPrimary) },
                                    onClick = {
                                        viewModel.updateAutomationSettings(settings.copy(wifiProfileId = null))
                                        showWifiProfileMenu = false
                                    }
                                )
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name, color = TextPrimary) },
                                        onClick = {
                                            viewModel.updateAutomationSettings(settings.copy(wifiProfileId = p.id))
                                            showWifiProfileMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // B. Disconnect Policy when leaving Wi-Fi
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("When leaving Wi-Fi:", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(settings.wifiLeavePolicy.displayName, fontSize = 12.sp, color = AccentCyan)
                        }
                        Box {
                            OutlinedButton(onClick = { showPolicyMenu = true }) {
                                Text("Change Policy", fontSize = 11.sp)
                            }
                            DropdownMenu(
                                expanded = showPolicyMenu,
                                onDismissRequest = { showPolicyMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                WifiLeavePolicy.values().forEach { policy ->
                                    DropdownMenuItem(
                                        text = { Text(policy.displayName, color = TextPrimary) },
                                        onClick = {
                                            viewModel.updateAutomationSettings(settings.copy(wifiLeavePolicy = policy))
                                            showPolicyMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // C. Auto-stop when Wi-Fi is turned OFF explicitly
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-stop when Wi-Fi turned OFF", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Releases VPN immediately if Wi-Fi radio is switched off", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.autoStopOnWifiOff,
                            onCheckedChange = { viewModel.updateAutomationSettings(settings.copy(autoStopOnWifiOff = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // D. Auto-reconnect when returning to approved Wi-Fi
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-reconnect upon return", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Restores tunnel when returning to configured Wi-Fi", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.autoReconnectOnWifiReturn,
                            onCheckedChange = { viewModel.updateAutomationSettings(settings.copy(autoReconnectOnWifiReturn = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Mobile Data Automation Section
            Text(
                text = "MOBILE DATA AUTOMATION",
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-start on Mobile Data", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Starts VPN when cellular internet is verified", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.autoStartOnMobileData,
                            onCheckedChange = { viewModel.updateAutomationSettings(settings.copy(autoStartOnMobileData = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-stop when Mobile Data lost", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Stops VPN when cellular internet is unavailable", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.autoStopOnMobileDataDisabled,
                            onCheckedChange = { viewModel.updateAutomationSettings(settings.copy(autoStopOnMobileDataDisabled = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Dedicated Mobile Profile
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Mobile Data Server Profile", fontSize = 13.sp, color = TextPrimary)
                            val profName = profiles.find { it.id == settings.mobileProfileId }?.name ?: "Active Selected Profile"
                            Text(profName, fontSize = 12.sp, color = AccentCyan)
                        }
                        Box {
                            OutlinedButton(onClick = { showMobileProfileMenu = true }) {
                                Text("Choose Profile", fontSize = 11.sp)
                            }
                            DropdownMenu(
                                expanded = showMobileProfileMenu,
                                onDismissRequest = { showMobileProfileMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Active Selected Profile", color = TextPrimary) },
                                    onClick = {
                                        viewModel.updateAutomationSettings(settings.copy(mobileProfileId = null))
                                        showMobileProfileMenu = false
                                    }
                                )
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name, color = TextPrimary) },
                                        onClick = {
                                            viewModel.updateAutomationSettings(settings.copy(mobileProfileId = p.id))
                                            showMobileProfileMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
