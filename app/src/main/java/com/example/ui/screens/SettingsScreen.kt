package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
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
import com.example.V2RayApplication
import com.example.receiver.VpnActionReceiver
import com.example.ui.MainViewModel
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.appSwitchColors
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusError
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = V2RayApplication.get()
    val prefs = app.preferencesRepository

    var primaryDns by remember { mutableStateOf(prefs.primaryDns) }
    var secondaryDns by remember { mutableStateOf(prefs.secondaryDns) }
    var bypassLan by remember { mutableStateOf(prefs.isBypassLan) }

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
                text = "Settings",
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
            // Samsung Modes & Routines Integration Section
            Text(
                text = "SAMSUNG MODES & ROUTINES INTEGRATION",
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
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Samsung One UI Setup Guide",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "To trigger Start/Stop service automatically or via Bixby/One UI Routines:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBg, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("Method 1: One UI App Actions & Shortcuts", fontWeight = FontWeight.Bold, color = AccentCyan, fontSize = 12.sp)
                            Text("1. Open Samsung Settings -> Modes and Routines.", color = TextSecondary, fontSize = 11.sp)
                            Text("2. Create a Routine -> 'Then' -> Apps -> 'Open an app or do an app action'.", color = TextSecondary, fontSize = 11.sp)
                            Text("3. Select v2rayDP -> 'Start service' or 'Stop service'.", color = TextSecondary, fontSize = 11.sp)

                            Spacer(modifier = Modifier.height(8.dp))

                            Text("Method 2: Quick Settings Tile", fontWeight = FontWeight.Bold, color = AccentCyan, fontSize = 12.sp)
                            Text("Edit Quick Settings buttons -> drag the 'v2rayDP' tile to your shade for 1-tap toggling.", color = TextSecondary, fontSize = 11.sp)

                            Spacer(modifier = Modifier.height(8.dp))

                            Text("Method 3: Intent Broadcasts", fontWeight = FontWeight.Bold, color = AccentCyan, fontSize = 12.sp)
                            Text("Actions: com.example.v2raydp.ACTION_START_VPN and ACTION_STOP_VPN", color = TextMuted, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Test Samsung Routine Intents directly:", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val intent = Intent(context, VpnActionReceiver::class.java).apply {
                                    action = VpnActionReceiver.ACTION_START_VPN
                                }
                                context.sendBroadcast(intent)
                                viewModel.showMessage("Broadcast ACTION_START_VPN sent")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusConnected),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Start Intent", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                val intent = Intent(context, VpnActionReceiver::class.java).apply {
                                    action = VpnActionReceiver.ACTION_STOP_VPN
                                }
                                context.sendBroadcast(intent)
                                viewModel.showMessage("Broadcast ACTION_STOP_VPN sent")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Stop Intent", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // DNS & Routing Settings
            Text(
                text = "DNS & NETWORK ROUTING",
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
                            Text("Bypass LAN Subnets", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Directly routes private local IP ranges (192.168.x, 10.x, 172.x)", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = bypassLan,
                            onCheckedChange = {
                                bypassLan = it
                                prefs.isBypassLan = it
                            },
                            colors = appSwitchColors()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = primaryDns,
                        onValueChange = {
                            primaryDns = it
                            prefs.primaryDns = it
                        },
                        label = { Text("Primary DNS Server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = secondaryDns,
                        onValueChange = {
                            secondaryDns = it
                            prefs.secondaryDns = it
                        },
                        label = { Text("Secondary DNS Server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Battery Optimization & Samsung Background Execution Guide
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Background Execution & Battery Notice", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "For uninterrupted automation on Samsung devices:\n• Go to Settings -> Apps -> v2rayDP -> Battery -> Choose 'Unrestricted'.\n• Turn off 'Put unused apps to sleep' for v2rayDP.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About Us Section
            AboutSection()

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
