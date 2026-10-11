package com.example

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.model.ServerProfile
import com.example.receiver.VpnActionReceiver
import com.example.service.V2RayVpnService
import com.example.ui.MainViewModel
import com.example.ui.screens.AutomationScreen
import com.example.ui.screens.EditServerDialog
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.HotspotScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.QrCodeScreen
import com.example.ui.screens.ServerProfilesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.DarkBg
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Activity result launcher for Android VpnService user preparation/consent
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpn()
        } else {
            viewModel.showMessage("VPN permission was declined")
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionsState()
        viewModel.automationManager.forceEvaluateNow()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissionsState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionsState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                val userMessage by viewModel.userMessage.collectAsState()

                var showEditDialog by remember { mutableStateOf(false) }
                var profileToEdit by remember { mutableStateOf<ServerProfile?>(null) }
                var manualProtocol by remember { mutableStateOf("vless") }

                LaunchedEffect(userMessage) {
                    userMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.dismissUserMessage()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkBg,
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateToServers = { navController.navigate("servers") },
                                onNavigateToQr = { navController.navigate("qr") },
                                onNavigateToAutomation = { navController.navigate("automation") },
                                onNavigateToHotspot = { navController.navigate("hotspot") },
                                onNavigateToLogs = { navController.navigate("logs") },
                                onAddManualServer = { proto ->
                                    profileToEdit = null
                                    manualProtocol = proto
                                    showEditDialog = true
                                },
                                onPrepareVpn = { prepareAndStartVpn() },
                                onRequestLocationPermission = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onRequestBackgroundRun = {
                                    viewModel.requestIgnoreBatteryOptimizations(this@MainActivity)
                                }
                            )
                        }

                        composable("servers") {
                            ServerProfilesScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onAddProfile = {
                                    profileToEdit = null
                                    manualProtocol = "vless"
                                    showEditDialog = true
                                },
                                onEditProfile = { p ->
                                    profileToEdit = p
                                    manualProtocol = p.protocol
                                    showEditDialog = true
                                }
                            )
                        }

                        composable("qr") {
                            QrCodeScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable("automation") {
                            AutomationScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onRequestLocationPermission = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onRequestBackgroundRun = {
                                    viewModel.requestIgnoreBatteryOptimizations(this@MainActivity)
                                }
                            )
                        }

                        composable("hotspot") {
                            HotspotScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable("logs") {
                            LogsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }

                    if (showEditDialog) {
                        EditServerDialog(
                            initialProfile = profileToEdit,
                            initialProtocol = manualProtocol,
                            onDismiss = { showEditDialog = false },
                            onSave = { updated ->
                                viewModel.saveProfile(updated)
                                showEditDialog = false
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val dataUri = intent.data

        if (dataUri != null && (dataUri.scheme == "vmess" || dataUri.scheme == "vless")) {
            viewModel.importVmessLink(dataUri.toString())
        }

        when (action) {
            VpnActionReceiver.ACTION_START_VPN,
            "shortcut_start_vpn" -> {
                prepareAndStartVpn()
            }
            VpnActionReceiver.ACTION_STOP_VPN,
            "shortcut_stop_vpn" -> {
                viewModel.stopVpn("Shortcut / Routine Stop")
            }
            VpnActionReceiver.ACTION_SWITCH_PROFILE -> {
                val profId = intent.getLongExtra("profile_id", -1L)
                if (profId > 0) {
                    val profile = viewModel.allProfiles.value.find { it.id == profId }
                    if (profile != null) {
                        viewModel.selectProfile(profile)
                    }
                }
            }
        }
    }

    private fun prepareAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            // Need user confirmation dialog
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            // Already prepared, start directly
            viewModel.startVpn()
        }
    }
}
