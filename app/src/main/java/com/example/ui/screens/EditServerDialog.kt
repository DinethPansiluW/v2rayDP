package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.model.ServerProfile
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.UUID

@Composable
fun EditServerDialog(
    initialProfile: ServerProfile?,
    initialProtocol: String = "vless",
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit
) {
    var protocol by remember { mutableStateOf(initialProfile?.protocol ?: initialProtocol) }
    var name by remember { mutableStateOf(initialProfile?.name ?: if (protocol == "vless") "VLESS Node" else "VMess Node") }
    var address by remember { mutableStateOf(initialProfile?.address ?: "") }
    var port by remember { mutableStateOf(initialProfile?.port?.toString() ?: "443") }
    var uuid by remember { mutableStateOf(initialProfile?.uuid ?: UUID.randomUUID().toString()) }
    var alterId by remember { mutableStateOf(initialProfile?.alterId?.toString() ?: "0") }
    var network by remember { mutableStateOf(initialProfile?.network ?: "ws") }
    var path by remember { mutableStateOf(initialProfile?.path ?: "/") }
    var host by remember { mutableStateOf(initialProfile?.host ?: "") }
    var isTls by remember { mutableStateOf(initialProfile?.isTlsEnabled ?: true) }
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = if (initialProfile == null) "Add ${protocol.uppercase()} Server" else "Edit ${protocol.uppercase()} Server",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = androidx.compose.ui.graphics.Color(0xFFEF4444),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Protocol selector (VLESS vs VMess)
                Text("Protocol Engine:", fontSize = 11.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransportPill(
                        label = "VLESS (Modern)",
                        isSelected = protocol.equals("vless", ignoreCase = true),
                        onClick = { protocol = "vless" },
                        modifier = Modifier.weight(1f)
                    )
                    TransportPill(
                        label = "VMess",
                        isSelected = protocol.equals("vmess", ignoreCase = true),
                        onClick = { protocol = "vmess" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                CustomTextField(value = name, onValueChange = { name = it }, label = "Server Remarks / Name")
                Spacer(modifier = Modifier.height(8.dp))

                CustomTextField(value = address, onValueChange = { address = it }, label = "Server Address (IP / Domain)")
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    CustomTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = "Port",
                        modifier = Modifier.weight(1f)
                    )
                    if (protocol == "vmess") {
                        Spacer(modifier = Modifier.width(8.dp))
                        CustomTextField(
                            value = alterId,
                            onValueChange = { alterId = it },
                            label = "AlterId (AEAD = 0)",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // UUID with generate button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CustomTextField(
                        value = uuid,
                        onValueChange = { uuid = it },
                        label = "User UUID",
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { uuid = UUID.randomUUID().toString() },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Generate UUID", tint = AccentCyan)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Transport Network selector (TCP / WS)
                Text("Transport Network:", fontSize = 11.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransportPill(
                        label = "WebSocket (ws)",
                        isSelected = network == "ws",
                        onClick = { network = "ws" },
                        modifier = Modifier.weight(1f)
                    )
                    TransportPill(
                        label = "TCP",
                        isSelected = network == "tcp",
                        onClick = { network = "tcp" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (network == "ws") {
                    CustomTextField(value = path, onValueChange = { path = it }, label = "WebSocket Path")
                    Spacer(modifier = Modifier.height(8.dp))
                    CustomTextField(value = host, onValueChange = { host = it }, label = "Host Header")
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // TLS Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable TLS (HTTPS/WSS)", color = TextPrimary, fontSize = 14.sp)
                    Switch(
                        checked = isTls,
                        onCheckedChange = { isTls = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                    )
                }

                if (isTls) {
                    CustomTextField(value = sni, onValueChange = { sni = it }, label = "SNI Server Name")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portNum = port.toIntOrNull()
                    val aidNum = alterId.toIntOrNull() ?: 0
                    if (address.trim().isEmpty()) {
                        errorMessage = "Address cannot be empty"
                        return@Button
                    }
                    if (portNum == null || portNum !in 1..65535) {
                        errorMessage = "Port must be between 1 and 65535"
                        return@Button
                    }
                    if (uuid.trim().isEmpty()) {
                        errorMessage = "UUID cannot be empty"
                        return@Button
                    }

                    val updated = (initialProfile ?: ServerProfile(
                        name = name.trim().ifEmpty { "${protocol.uppercase()} Node" },
                        protocol = protocol,
                        address = address.trim(),
                        port = portNum,
                        uuid = uuid.trim()
                    )).copy(
                        name = name.trim().ifEmpty { "${protocol.uppercase()} Node" },
                        protocol = protocol,
                        address = address.trim(),
                        port = portNum,
                        uuid = uuid.trim(),
                        alterId = if (protocol == "vless") 0 else aidNum,
                        network = network,
                        path = path.trim().ifEmpty { "/" },
                        host = host.trim(),
                        tls = if (isTls) "tls" else "none",
                        sni = sni.trim()
                    )
                    onSave(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = androidx.compose.ui.graphics.Color.Black),
                modifier = Modifier.testTag("save_server_button")
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun CustomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = DarkSurfaceBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = AccentCyan,
            unfocusedLabelColor = TextSecondary
        )
    )
}

@Composable
private fun TransportPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) AccentCyan else DarkBg,
            contentColor = if (isSelected) androidx.compose.ui.graphics.Color.Black else TextSecondary
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
