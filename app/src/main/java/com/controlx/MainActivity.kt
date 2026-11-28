package com.controlx

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.domain.FastPathResult
import com.controlx.overlay.OverlayController
import com.controlx.overlay.OverlayControllerService
import com.controlx.platform.CommandApiClient
import com.controlx.platform.ContactCatalogManager
import com.controlx.platform.PermissionRepository
import com.controlx.platform.PermissionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_LAUNCH_OVERLAY = "com.controlx.action.LAUNCH_OVERLAY"
    }

    private var permissions by mutableStateOf(
        PermissionSnapshot(
            microphoneGranted = false,
            notificationsGranted = false,
            overlayGranted = false,
            writeSettingsGranted = false,
            accessibilityGranted = false,
            batteryOptimizationIgnored = false,
            digitalAssistantGranted = false,
            deviceAdminActive = false,
            contactsGranted = false,
        ),
    )
    private var overlayController: OverlayController? = null
    private val commandApiClient by lazy { CommandApiClient(this) }
    private var startupCrash by mutableStateOf(
        (application as? ControlXApplication)
            ?.let { ControlXApplication.readLastCrash(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ControlAccessibilityService.syncAutoDisableOnPayment(this)
        try {
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFF7DD3FC),
                        secondary = Color(0xFFA78BFA),
                        background = Color(0xFF090C12),
                        surface = Color(0xFF111827),
                        error = Color(0xFFF87171),
                    ),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        PermissionDashboard(
                            permissions = permissions,
                            startupCrash = startupCrash,
                            onPermissionsChanged = { refreshPermissions() },
                            onLaunchOverlay = {
                                if (permissions.microphoneGranted && permissions.overlayGranted) {
                                    runCatching { OverlayControllerService.start(this) }
                                }
                            },
                            onClearCrash = {
                                startupCrash = null
                                ControlXApplication.clearLastCrash(application)
                            },
                            onLoadServerUrl = { commandApiClient.getEffectiveBaseUrl() },
                            onConnectBackend = { serverUrl ->
                                val configured = commandApiClient.configureServerUrl(serverUrl)
                                if (!configured) return@PermissionDashboard "Invalid server URL"
                                if (!commandApiClient.isConfigured()) {
                                    return@PermissionDashboard "Server URL needed"
                                }
                                val registered = runCatching {
                                    commandApiClient.ensureDeviceToken() != null
                                }.getOrDefault(false)
                                if (registered) "Connected" else "Connection failed"
                            },
                            onResetBackend = { commandApiClient.clearConfiguration() },
                            onHasDeviceSession = { commandApiClient.hasDeviceToken() },
                            onAutoEnsureSession = {
                                if (!commandApiClient.isConfigured()) {
                                    "Server URL needed"
                                } else {
                                    val ok = runCatching {
                                        commandApiClient.ensureDeviceToken() != null
                                    }.getOrDefault(false)
                                    if (ok) "Connected" else "Offline / unreachable"
                                }
                            },
                            onLoadAutoDisablePayment = {
                                commandApiClient.autoDisableAccessibilityOnPayment()
                            },
                            onToggleAutoDisablePayment = { enabled ->
                                commandApiClient.setAutoDisableAccessibilityOnPayment(enabled)
                                ControlAccessibilityService.setAutoDisableOnPayment(enabled)
                                enabled
                            },
                            onDisableAccessibilityNow = {
                                val disabled = ControlAccessibilityService.disableForPaymentSafety()
                                refreshPermissions()
                                disabled
                            },
                            onLoadInventoryConfiguration = {
                                commandApiClient.inventoryUploadEnabled()
                            },
                            onSetInventoryUpload = { enabled ->
                                commandApiClient.setInventoryUploadEnabled(enabled)
                                enabled
                            },
                            onScanInventory = { commandApiClient.scanInstalledApps().size },
                            onSyncInventory = { commandApiClient.syncAppInventory() },
                            onLoadFloatingBubble = {
                                commandApiClient.floatingBubbleEnabled()
                            },
                            onToggleFloatingBubble = { enabled ->
                                commandApiClient.setFloatingBubbleEnabled(enabled)
                                if (enabled) {
                                    OverlayControllerService.enableBubble(this)
                                } else {
                                    OverlayControllerService.disableBubble(this)
                                }
                                enabled
                            },
                        )
                    }
                }
            }
        } catch (error: Exception) {
            startupCrash = "Compose startup failure: ${error.javaClass.name}: ${error.message}"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { handleLaunchIntent(intent) }
    }

    private fun handleLaunchIntent(intent: Intent) {
        if (intent.action != ACTION_LAUNCH_OVERLAY) return
        val snapshot = PermissionRepository(this).snapshot()
        if (snapshot.microphoneGranted && snapshot.overlayGranted) {
            OverlayControllerService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        intent?.let(::handleLaunchIntent)
    }

    private fun refreshPermissions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = runCatching {
                PermissionRepository(this@MainActivity).snapshot()
            }.getOrNull()
            if (snapshot != null) {
                ContactCatalogManager.refresh(this@MainActivity)
                withContext(Dispatchers.Main) {
                    permissions = snapshot
                }
            }
        }
    }

    override fun onDestroy() {
        overlayController?.destroy()
        super.onDestroy()
    }
}

@Composable
private fun PermissionDashboard(
    permissions: PermissionSnapshot,
    startupCrash: String?,
    onPermissionsChanged: () -> Unit,
    onLaunchOverlay: () -> Unit,
    onClearCrash: () -> Unit,
    onLoadServerUrl: () -> String,
    onConnectBackend: (String) -> String,
    onResetBackend: () -> Boolean,
    onHasDeviceSession: () -> Boolean,
    onAutoEnsureSession: () -> String,
    onLoadAutoDisablePayment: () -> Boolean,
    onToggleAutoDisablePayment: (Boolean) -> Boolean,
    onDisableAccessibilityNow: () -> Boolean,
    onLoadInventoryConfiguration: () -> Boolean,
    onSetInventoryUpload: (Boolean) -> Boolean,
    onScanInventory: () -> Int,
    onSyncInventory: () -> Boolean,
    onLoadFloatingBubble: () -> Boolean,
    onToggleFloatingBubble: (Boolean) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnosticCommand by mutableStateOf("")
    var diagnosticResult by mutableStateOf("Enter a Fast-Path command.")
    var serverUrl by mutableStateOf("")
    var backendStatus by mutableStateOf("Checking...")
    var autoDisablePayment by mutableStateOf(true)
    var accessibilityActionMessage by mutableStateOf<String?>(null)
    var inventoryUploadEnabled by mutableStateOf(false)
    var inventoryStatus by mutableStateOf("Local scan not run")
    var floatingBubbleEnabled by mutableStateOf(false)

    LaunchedEffect(Unit) {
        onPermissionsChanged()
        serverUrl = withContext(Dispatchers.IO) { onLoadServerUrl() }
        autoDisablePayment = withContext(Dispatchers.IO) { onLoadAutoDisablePayment() }
        floatingBubbleEnabled = withContext(Dispatchers.IO) { onLoadFloatingBubble() }
        val alreadyRegistered = withContext(Dispatchers.IO) { onHasDeviceSession() }
        backendStatus = if (serverUrl.isBlank()) {
            "Server URL needed"
        } else if (alreadyRegistered) {
            "Connected"
        } else {
            withContext(Dispatchers.IO) { onAutoEnsureSession() }
        }
        inventoryUploadEnabled = withContext(Dispatchers.IO) { onLoadInventoryConfiguration() }
        val scanned = withContext(Dispatchers.IO) { onScanInventory() }
        inventoryStatus = "$scanned apps found locally"
        if (inventoryUploadEnabled && serverUrl.isNotBlank()) {
            val synced = withContext(Dispatchers.IO) { onSyncInventory() }
            if (synced) inventoryStatus = "$scanned apps inventory uploaded"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { onPermissionsChanged() },
    )
    val enabledForOverlay = permissions.microphoneGranted && permissions.overlayGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090C12))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "CONTROL X",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF8FAFC),
        )
        Text(
            text = "Dynamic voice & OS assistant",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF94A3B8),
        )

        if (!startupCrash.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("CRASH RECOVERY", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(startupCrash, color = Color(0xFFFEE2E2))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(
                                    ClipData.newPlainText("Control X crash log", startupCrash),
                                )
                        }) { Text("COPY LOG") }
                        Button(onClick = onClearCrash) { Text("CLEAR") }
                    }
                }
            }
        }

        ControlCard(
            title = "Core assistant permissions",
            subtitle = "Voice, intents, apps, calls & AI work without Accessibility",
        ) {
            PermissionRow("Microphone", permissions.microphoneGranted) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            PermissionRow("Overlay", permissions.overlayGranted) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            PermissionRow("Notifications", permissions.notificationsGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            PermissionRow("Contacts (for calls & SMS drafts)", permissions.contactsGranted) {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            }
            PermissionRow("Digital assistant", permissions.digitalAssistantGranted) {
                val routes = listOf(
                    Settings.ACTION_VOICE_INPUT_SETTINGS,
                    "com.android.settings.action.VOICE_INPUT_SETTINGS",
                    Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                )
                val opened = routes.firstNotNullOfOrNull { action ->
                    runCatching {
                        context.startActivity(
                            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    }.getOrNull()
                }
                if (opened != true) {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            PermissionRow("Write settings", permissions.writeSettingsGranted) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            PermissionRow("Battery exemption", permissions.batteryOptimizationIgnored) {
                val direct = runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
                if (!direct) {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            PermissionRow("Device Admin (lock without Accessibility)", permissions.deviceAdminActive) {
                val component = android.content.ComponentName(
                    context,
                    com.controlx.admin.ControlXDeviceAdminReceiver::class.java,
                )
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Control X uses Device Admin only to lock the screen without Accessibility.",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            Text(
                "Power-hold activation depends on your OEM Default Assistant menu. You can also toggle the floating CX bubble below, tap Ask Control X in the notification shade, or pin the app shortcut.",
                color = Color(0xFF94A3B8),
            )
        }

        ControlCard(
            title = "Optional screen automation",
            subtitle = "Google Pay & UPI compatibility",
            status = if (permissions.accessibilityGranted) "Active" else "Off (Payment safe)",
            positiveStatus = !permissions.accessibilityGranted,
        ) {
            Text(
                "Leave OFF for daily use so sideloaded builds do not trigger Google Pay or banking blocks. Enable only when you want in-app tapping or scrolling.",
                color = Color(0xFF94A3B8),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (permissions.accessibilityGranted) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val stopped = onDisableAccessibilityNow()
                            accessibilityActionMessage = if (stopped) {
                                "Accessibility turned off. Restart phone once if a payment app cached a warning."
                            } else {
                                "Opening Accessibility settings."
                            }
                            if (!stopped) {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) { Text("DISABLE NOW") }
                } else {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ) { Text("ENABLE OPTIONAL") }
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val updated = onToggleAutoDisablePayment(!autoDisablePayment)
                            withContext(Dispatchers.Main) {
                                autoDisablePayment = updated
                            }
                        }
                    },
                ) {
                    Text(if (autoDisablePayment) "AUTO-OFF: ON" else "AUTO-OFF: OFF")
                }
            }
            accessibilityActionMessage?.let { msg ->
                Text(msg, color = Color(0xFF7DD3FC))
            }
        }

        ControlCard(
            title = "Cloud AI & device sync",
            subtitle = "NVIDIA Nemotron runs on backend — no user API key needed",
            status = backendStatus,
            positiveStatus = backendStatus == "Connected",
        ) {
            Text(
                "All AI keys stay hidden on the backend server. This phone registers dynamically with its own per-device session token.",
                color = Color(0xFF94A3B8),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                label = { Text("Backend server URL") },
                placeholder = { Text("https://your-controlx-server.example.com") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = serverUrl.isNotBlank(),
                    onClick = {
                        backendStatus = "Connecting..."
                        scope.launch(Dispatchers.IO) {
                            val result = onConnectBackend(serverUrl)
                            withContext(Dispatchers.Main) {
                                backendStatus = result
                            }
                        }
                    },
                ) { Text("CONNECT DEVICE") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            if (onResetBackend()) {
                                val reloadedUrl = onLoadServerUrl()
                                withContext(Dispatchers.Main) {
                                    serverUrl = reloadedUrl
                                    backendStatus = if (reloadedUrl.isBlank()) {
                                        "Server URL needed"
                                    } else {
                                        "Session reset"
                                    }
                                }
                            }
                        }
                    },
                ) { Text("RESET") }
            }
        }

        ControlCard(
            title = "Device app inventory",
            subtitle = "The app list is scanned locally after launch",
            status = inventoryStatus,
            positiveStatus = inventoryStatus.contains("uploaded") || inventoryStatus.contains("local"),
        ) {
            Text(
                "Package names and app labels stay local unless you enable backend upload consent.",
                color = Color(0xFF94A3B8),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val updated = onSetInventoryUpload(true)
                            val count = onScanInventory()
                            val synced = onSyncInventory()
                            withContext(Dispatchers.Main) {
                                inventoryUploadEnabled = updated
                                inventoryStatus =
                                    if (synced) "$count apps inventory uploaded"
                                    else if (updated) "$count apps local; connect backend first"
                                    else "Upload consent failed"
                            }
                        }
                    },
                ) { Text(if (inventoryUploadEnabled) "UPLOAD ON" else "ENABLE UPLOAD") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val disabled = onSetInventoryUpload(false)
                            val count = onScanInventory()
                            withContext(Dispatchers.Main) {
                                inventoryUploadEnabled = false
                                inventoryStatus =
                                    if (disabled) "$count apps local only" else "Could not update"
                            }
                        }
                    },
                ) { Text("LOCAL ONLY") }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = inventoryUploadEnabled,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val synced = onSyncInventory()
                        withContext(Dispatchers.Main) {
                            inventoryStatus =
                                if (synced) "Inventory uploaded" else "Inventory sync failed"
                        }
                    }
                },
            ) { Text("SYNC NOW") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.weight(1.35f),
                enabled = enabledForOverlay,
                onClick = onLaunchOverlay,
            ) {
                Text("LAUNCH CONTROL X")
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = enabledForOverlay,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val updated = onToggleFloatingBubble(!floatingBubbleEnabled)
                        withContext(Dispatchers.Main) {
                            floatingBubbleEnabled = updated
                        }
                    }
                },
            ) {
                Text(if (floatingBubbleEnabled) "BUBBLE: ON" else "BUBBLE: OFF")
            }
        }

        ControlCard(title = "Fast-Path test", subtitle = "Try an offline command without voice") {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = diagnosticCommand,
                onValueChange = { diagnosticCommand = it },
                singleLine = true,
                label = { Text("Command") },
                placeholder = { Text("turn on flashlight") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val command = diagnosticCommand
                        scope.launch(Dispatchers.Default) {
                            val result = com.controlx.router.FastPathRouter(context).route(command)
                            val message = when (result) {
                                is FastPathResult.Executed ->
                                    "Executed: ${result.message} (${result.latencyMs} ms)"
                                FastPathResult.Unhandled ->
                                    "No local Fast-Path match (use overlay for backend dynamic AI)."
                                is FastPathResult.Error ->
                                    "Error: ${result.reason}"
                            }
                            withContext(Dispatchers.Main) { diagnosticResult = message }
                        }
                    },
                ) { Text("TEST") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { diagnosticCommand = "turn on flashlight" },
                ) { Text("FLASHLIGHT") }
            }
            Text(diagnosticResult, color = Color(0xFFCBD5E1))
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onResolve: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                if (granted) "Ready" else "Needs setup",
                color = if (granted) Color(0xFF4ADE80) else Color(0xFFFCA5A5),
            )
        }
        if (!granted) Button(onClick = onResolve) { Text("SET UP") }
    }
}

@Composable
private fun ControlCard(
    title: String,
    subtitle: String,
    status: String? = null,
    positiveStatus: Boolean? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Color(0xFF94A3B8))
                }
                if (status != null) {
                    val isPositive = positiveStatus
                        ?: (status == "Configured" || status == "Connected")
                    Text(
                        text = status.uppercase(),
                        color = if (isPositive) Color(0xFF4ADE80) else Color(0xFFFCA5A5),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            content()
        }
    }
}
