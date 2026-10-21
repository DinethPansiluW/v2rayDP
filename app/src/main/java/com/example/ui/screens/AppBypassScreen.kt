package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.V2RayApplication
import com.example.model.AppBypassMode
import com.example.model.ConnectionState
import com.example.service.V2RayVpnService
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.appSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val name: String,
    val packageName: String,
    val isSystemApp: Boolean
)

enum class AppFilterTab {
    ALL,
    USER,
    SYSTEM,
    SELECTED
}

private val appIconCache = LruCache<String, Bitmap>(150)

private fun Drawable.toBitmap(width: Int = 96, height: Int = 96): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    return bitmap
}

@Composable
fun AppBypassScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val app = V2RayApplication.get()
    val prefs = app.preferencesRepository
    val vpnState by V2RayVpnService.serviceState.collectAsState()
    val isVpnConnected = vpnState is ConnectionState.Connected
    val reconnectNeededGlobal by V2RayVpnService.reconnectNeeded.collectAsState()

    var bypassEnabled by remember { mutableStateOf(prefs.appBypassEnabled) }
    var bypassMode by remember { mutableStateOf(prefs.appBypassMode) }
    var selectedPackages by remember { mutableStateOf(prefs.appBypassSelected) }
    var bypassNewApps by remember { mutableStateOf(prefs.bypassNewApps) }

    var allApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(AppFilterTab.ALL) }
    var localChangeMadeWhileConnected by remember { mutableStateOf(false) }

    // Reconcile new apps and load installed apps on Dispatchers.Default
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            try {
                val pm = context.packageManager
                val changed = prefs.reconcileNewApps(pm)
                if (changed) {
                    selectedPackages = prefs.appBypassSelected
                }

                val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(launchIntent, 0)
                val ownPackage = context.packageName

                val list = resolveInfos.mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == ownPackage) return@mapNotNull null
                    val appInfo = try {
                        pm.getApplicationInfo(pkg, 0)
                    } catch (_: Exception) {
                        null
                    }
                    val isSystem = if (appInfo != null) (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 else false
                    val label = try {
                        ri.loadLabel(pm)?.toString() ?: (appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg)
                    } catch (_: Exception) {
                        pkg
                    }
                    InstalledAppInfo(
                        name = label,
                        packageName = pkg,
                        isSystemApp = isSystem
                    )
                }.distinctBy { it.packageName }
                    .sortedBy { it.name.lowercase() }

                withContext(Dispatchers.Main) {
                    allApps = list
                    isLoading = false
                    if (list.isEmpty()) {
                        loadErrorMessage = "No launchable apps found or package visibility is restricted."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    loadErrorMessage = "Failed to query installed apps: ${e.message}"
                }
            }
        }
    }

    fun markModified() {
        if (isVpnConnected) {
            localChangeMadeWhileConnected = true
        }
    }

    fun toggleMasterSwitch(enabled: Boolean) {
        bypassEnabled = enabled
        prefs.appBypassEnabled = enabled
        // On first enabling the feature, take snapshot of currently installed apps
        if (enabled && prefs.knownPackages.isEmpty()) {
            val installed = allApps.map { it.packageName }.toSet()
            if (installed.isNotEmpty()) {
                prefs.knownPackages = installed
            }
        }
        markModified()
    }

    fun setMode(mode: AppBypassMode) {
        bypassMode = mode
        prefs.appBypassMode = mode
        markModified()
    }

    fun toggleBypassNewApps(enabled: Boolean) {
        bypassNewApps = enabled
        prefs.bypassNewApps = enabled
    }

    fun updateSelection(newSet: Set<String>) {
        selectedPackages = newSet
        prefs.appBypassSelected = newSet
        markModified()
    }

    val filteredApps = remember(allApps, searchQuery, currentTab, selectedPackages) {
        allApps.filter { appItem ->
            val matchesTab = when (currentTab) {
                AppFilterTab.ALL -> true
                AppFilterTab.USER -> !appItem.isSystemApp
                AppFilterTab.SYSTEM -> appItem.isSystemApp
                AppFilterTab.SELECTED -> selectedPackages.contains(appItem.packageName)
            }
            val matchesSearch = searchQuery.isBlank() ||
                    appItem.name.contains(searchQuery, ignoreCase = true) ||
                    appItem.packageName.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesSearch
        }
    }

    val showReconnectBanner = isVpnConnected && (reconnectNeededGlobal || localChangeMadeWhileConnected)

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
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.testTag("app_bypass_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = "App Bypass",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "Per-app tunneling & proxy configuration",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // Reconnect to Apply Banner (shown when VPN is connected and changes are made)
        if (showReconnectBanner) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("banner_reconnect_to_apply"),
                colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = StatusWarning,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Reconnect to apply",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Routing changes will take effect after restarting tunnel",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                    Button(
                        onClick = {
                            localChangeMadeWhileConnected = false
                            V2RayVpnService.restart(context, reason = "Apply bypass routing changes")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("button_apply_now")
                    ) {
                        Text("Apply now", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Master Switch Card: "Enable app bypass"
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Enable app bypass",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (bypassEnabled) "Custom app routing is active" else "Feature is OFF (All traffic uses VPN)",
                                fontSize = 12.sp,
                                color = if (bypassEnabled) AccentCyan else TextSecondary
                            )
                        }
                        Switch(
                            checked = bypassEnabled,
                            onCheckedChange = { toggleMasterSwitch(it) },
                            colors = appSwitchColors(),
                            modifier = Modifier.testTag("switch_master_app_bypass")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Info text: Bypassed apps do not use the VPN and are not affected by Blocking.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Bypassed apps do not use the VPN and are not affected by Blocking.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Mode Selector: Two Radio Rows with one-line explanations
            if (bypassEnabled) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "ROUTING MODE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // Mode A: Bypass only these apps
                            ModeRadioRow(
                                title = "Bypass only these apps",
                                description = "Selected apps skip the VPN, everything else uses it.",
                                selected = bypassMode == AppBypassMode.BYPASS_SELECTED,
                                testTag = "mode_bypass_selected",
                                onClick = { setMode(AppBypassMode.BYPASS_SELECTED) }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Mode B: Bypass all except these apps
                            ModeRadioRow(
                                title = "Bypass all except these apps",
                                description = "Only selected apps use the VPN, everything else bypasses it.",
                                selected = bypassMode == AppBypassMode.BYPASS_ALL_EXCEPT_SELECTED,
                                testTag = "mode_bypass_all_except_selected",
                                onClick = { setMode(AppBypassMode.BYPASS_ALL_EXCEPT_SELECTED) }
                            )

                            // Warning if Bypass all except selected and nothing selected
                            if (bypassMode == AppBypassMode.BYPASS_ALL_EXCEPT_SELECTED && selectedPackages.isEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StatusWarning.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = StatusWarning,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Select at least one app",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StatusWarning
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Switch: "Bypass newly installed apps" with dynamic subtitle
                item {
                    val subtitle = when (bypassMode) {
                        AppBypassMode.BYPASS_SELECTED -> {
                            if (bypassNewApps) "ON: A newly installed app is automatically added to the bypass list (skips VPN)."
                            else "OFF: A newly installed app uses the VPN."
                        }
                        AppBypassMode.BYPASS_ALL_EXCEPT_SELECTED -> {
                            if (bypassNewApps) "ON: A new app is NOT added to the allowed list (so it bypasses)."
                            else "OFF: A new app is automatically added to the allowed list (so it uses the VPN)."
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Bypass newly installed apps",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = bypassNewApps,
                                onCheckedChange = { toggleBypassNewApps(it) },
                                colors = appSwitchColors(),
                                modifier = Modifier.testTag("switch_bypass_new_apps")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            // Search Bar & Filter Controls
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_search_field"),
                    placeholder = { Text("Search apps by name or package…", color = TextMuted) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = AccentCyan)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Chips: All / User apps / System apps / Selected
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = currentTab == AppFilterTab.ALL,
                            onClick = { currentTab = AppFilterTab.ALL },
                            label = { Text("All", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = AccentCyan,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            )
                        )
                        FilterChip(
                            selected = currentTab == AppFilterTab.USER,
                            onClick = { currentTab = AppFilterTab.USER },
                            label = { Text("User apps", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = AccentCyan,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            )
                        )
                        FilterChip(
                            selected = currentTab == AppFilterTab.SYSTEM,
                            onClick = { currentTab = AppFilterTab.SYSTEM },
                            label = { Text("System apps", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = AccentCyan,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            )
                        )
                        FilterChip(
                            selected = currentTab == AppFilterTab.SELECTED,
                            onClick = { currentTab = AppFilterTab.SELECTED },
                            label = { Text("Selected (${selectedPackages.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = AccentCyan,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Select All / Deselect All Buttons + Counter ("12 of 87 selected")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val visiblePackages = filteredApps.map { it.packageName }.toSet()
                                updateSelection(selectedPackages + visiblePackages)
                            },
                            enabled = bypassEnabled && filteredApps.isNotEmpty(),
                            modifier = Modifier.testTag("button_select_all"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Select all", fontSize = 12.sp, color = TextPrimary)
                        }

                        OutlinedButton(
                            onClick = {
                                val visiblePackages = filteredApps.map { it.packageName }.toSet()
                                updateSelection(selectedPackages - visiblePackages)
                            },
                            enabled = bypassEnabled && selectedPackages.isNotEmpty(),
                            modifier = Modifier.testTag("button_deselect_all"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Deselect all", fontSize = 12.sp, color = TextPrimary)
                        }
                    }

                    // Counter "12 of 87 selected"
                    Text(
                        text = "${selectedPackages.size} of ${allApps.size} selected",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentCyan
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Loading state
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentCyan)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Loading installed applications…", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
            } else if (loadErrorMessage != null) {
                // Empty / permission edge cases
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Unable to read full application list",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = loadErrorMessage ?: "Unknown reason",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else if (filteredApps.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No applications match '$searchQuery'" else "No applications in this category",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                // Lazy Column of installed apps with LruCache lazy icons
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { appItem ->
                    AppItemRow(
                        context = context,
                        appInfo = appItem,
                        isChecked = selectedPackages.contains(appItem.packageName),
                        enabled = bypassEnabled,
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                selectedPackages + appItem.packageName
                            } else {
                                selectedPackages - appItem.packageName
                            }
                            updateSelection(updated)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AccentCyan.copy(alpha = 0.12f) else DarkSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, AccentCyan) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (selected) AccentCyan else TextMuted,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AccentCyan)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) AccentCyan else TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun AppItemRow(
    context: Context,
    appInfo: InstalledAppInfo,
    isChecked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // Lazily load icon with LruCache on Dispatchers.Default for visible rows only
    val iconBitmap by produceState<Bitmap?>(initialValue = appIconCache.get(appInfo.packageName), key1 = appInfo.packageName) {
        if (value == null) {
            val bmp = withContext(Dispatchers.Default) {
                try {
                    val cached = appIconCache.get(appInfo.packageName)
                    if (cached != null) {
                        cached
                    } else {
                        val drawable = context.packageManager.getApplicationIcon(appInfo.packageName)
                        val b = drawable.toBitmap(96, 96)
                        appIconCache.put(appInfo.packageName, b)
                        b
                    }
                } catch (_: Exception) {
                    null
                }
            }
            value = bmp
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!isChecked) }
            .testTag("app_item_${appInfo.packageName}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked && enabled) DarkSurfaceVariant else DarkSurface
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isChecked && enabled) AccentCyan.copy(alpha = 0.5f) else DarkSurfaceBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceBorder),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap!!.asImageBitmap(),
                        contentDescription = appInfo.name,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = appInfo.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (enabled) TextPrimary else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (appInfo.isSystemApp) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SYSTEM",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            modifier = Modifier
                                .background(DarkSurfaceVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = appInfo.packageName,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Checkbox(
                checked = isChecked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentCyan,
                    uncheckedColor = TextMuted,
                    checkmarkColor = DarkBg
                ),
                modifier = Modifier.testTag("checkbox_${appInfo.packageName}")
            )
        }
    }
}
