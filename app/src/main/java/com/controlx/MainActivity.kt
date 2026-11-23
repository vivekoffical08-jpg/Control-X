package com.controlx

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.domain.FastPathResult
import com.controlx.overlay.OverlayController
import com.controlx.platform.PermissionRepository
import com.controlx.platform.PermissionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import com.controlx.platform.GroqKeyStore
import com.controlx.platform.CommandApiClient
import android.app.admin.DevicePolicyManager

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
        ),
    )
    private var overlayController: OverlayController? = null
    private val groqKeyStore by lazy { GroqKeyStore(this) }
    private val commandApiClient by lazy { CommandApiClient(this) }
    private var startupCrash by mutableStateOf(
        (application as? ControlXApplication)
            ?.let { ControlXApplication.readLastCrash(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            onPermissionsChanged = {
                                refreshPermissions()
                            },
                            onLaunchOverlay = {
                                if (permissions.microphoneGranted &&
                                    permissions.overlayGranted
                                ) {
                                    runCatching {
                                        com.controlx.overlay.OverlayControllerService.start(this)
                                    }
                                }
                            },
                            onClearCrash = {
                                startupCrash = null
                                ControlXApplication.clearLastCrash(application)
                            },
                            onLoadGroqKey = {
                                groqKeyStore.load().orEmpty()
                            },
                            onSaveGroqKey = { key ->
                                groqKeyStore.save(key)
                            },
                            onClearGroqKey = {
                                groqKeyStore.clear()
                            },
                            onLoadApiConfiguration = {
                                commandApiClient.getConfiguration()
                            },
                            onSaveApiConfiguration = { baseUrl, apiToken ->
                                commandApiClient.configure(baseUrl, apiToken)
                            },
                            onClearApiConfiguration = {
                                commandApiClient.clearConfiguration()
                            },
                            onLoadInventoryConfiguration = {
                                commandApiClient.inventoryUploadEnabled()
                            },
                            onSetInventoryUpload = { enabled ->
                                commandApiClient.setInventoryUploadEnabled(enabled)
                                enabled
                            },
                            onScanInventory = {
                                commandApiClient.scanInstalledApps().size
                            },
                            onSyncInventory = {
                                commandApiClient.syncAppInventory()
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
            com.controlx.overlay.OverlayControllerService.start(this)
        }
    }

    private fun getOverlayController(): OverlayController =
        overlayController ?: OverlayController(this).also { overlayController = it }

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
    onLoadGroqKey: () -> String,
    onSaveGroqKey: (String) -> Boolean,
    onClearGroqKey: () -> Boolean,
    onLoadApiConfiguration: () -> CommandApiClient.ApiConfiguration,
    onSaveApiConfiguration: (String, String) -> Boolean,
    onClearApiConfiguration: () -> Boolean,
    onLoadInventoryConfiguration: () -> Boolean,
    onSetInventoryUpload: (Boolean) -> Boolean,
    onScanInventory: () -> Int,
    onSyncInventory: () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnosticCommand by mutableStateOf("")
    var diagnosticResult by mutableStateOf("Enter a Fast-Path command.")
    var groqKey by mutableStateOf("")
    var groqStatus by mutableStateOf("Not configured")
    var apiBaseUrl by mutableStateOf("")
    var apiToken by mutableStateOf("")
    var apiStatus by mutableStateOf("Not configured")
    var inventoryUploadEnabled by mutableStateOf(false)
    var inventoryStatus by mutableStateOf("Local scan not run")
    LaunchedEffect(Unit) {
        onPermissionsChanged()
        val loaded = withContext(Dispatchers.IO) { onLoadGroqKey() }
        groqKey = loaded
        groqStatus = if (loaded.isBlank()) "Not configured" else "Configured"
        val apiConfiguration = withContext(Dispatchers.IO) {
            onLoadApiConfiguration()
        }
        apiBaseUrl = apiConfiguration.baseUrl
        apiToken = apiConfiguration.apiToken
        apiStatus = if (apiConfiguration.baseUrl.isBlank()) {
            "Not configured"
        } else {
            "Configured"
        }
        inventoryUploadEnabled = withContext(Dispatchers.IO) {
            onLoadInventoryConfiguration()
        }
        val scanned = withContext(Dispatchers.IO) { onScanInventory() }
        inventoryStatus = "$scanned apps found locally"
        if (inventoryUploadEnabled) {
            val synced = withContext(Dispatchers.IO) { onSyncInventory() }
            if (synced) inventoryStatus = "$scanned apps inventory uploaded"
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { onPermissionsChanged() },
    )
    val runtimePermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val enabledForOverlay =
        permissions.microphoneGranted && permissions.overlayGranted

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
            text = "Android agent control center",
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

        ControlCard(title = "Device permissions", subtitle = "Required for voice and system control") {
            PermissionRow("Microphone", permissions.microphoneGranted) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            PermissionRow("Notifications", permissions.notificationsGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    )
                }
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
                        Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            PermissionRow("Overlay", permissions.overlayGranted) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            PermissionRow("Accessibility", permissions.accessibilityGranted) {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
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
            PermissionRow("Device Admin", permissions.deviceAdminActive) {
                val component = android.content.ComponentName(
                    context,
                    com.controlx.admin.ControlXDeviceAdminReceiver::class.java,
                )
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Control X uses Device Admin only to provide optional screen locking.",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            Text(
                "Power-hold activation: open Digital Assistant / Default apps / Assist settings, " +
                    "then select Control X. Android and OEM menus vary, and 3–4 second power-hold " +
                    "support is not guaranteed. A normal app cannot intercept the power key.",
                color = Color(0xFF94A3B8),
            )
        }

        ControlCard(
            title = "Groq AI",
            subtitle = "Encrypted locally on this phone",
            status = groqStatus,
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = groqKey,
                onValueChange = { groqKey = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("API key") },
                placeholder = { Text("Paste Groq API key") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = groqKey.isNotBlank(),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val saved = onSaveGroqKey(groqKey)
                            withContext(Dispatchers.Main) {
                                groqStatus = if (saved) "Configured" else "Save failed"
                            }
                        }
                    },
                ) { Text("SAVE KEY") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            if (onClearGroqKey()) {
                                withContext(Dispatchers.Main) {
                                    groqKey = ""
                                    groqStatus = "Not configured"
                                }
                            }
                        }
                    },
                ) { Text("CLEAR") }
            }
        }

        ControlCard(
            title = "Command database",
            subtitle = "MySQL through a protected backend",
            status = apiStatus,
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiBaseUrl,
                onValueChange = { apiBaseUrl = it },
                singleLine = true,
                label = { Text("Backend URL") },
                placeholder = { Text("https://api.example.com") },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiToken,
                onValueChange = { apiToken = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("API token") },
                placeholder = { Text("Paste backend token") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val saved = onSaveApiConfiguration(apiBaseUrl, apiToken)
                            withContext(Dispatchers.Main) {
                                apiStatus = if (saved) "Configured" else "Invalid settings"
                            }
                        }
                    },
                ) { Text("SAVE DATABASE") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            if (onClearApiConfiguration()) {
                                withContext(Dispatchers.Main) {
                                    apiBaseUrl = ""
                                    apiToken = ""
                                    apiStatus = "Not configured"
                                }
                            }
                        }
                    },
                ) { Text("CLEAR") }
            }
        }

        ControlCard(
            title = "Device app inventory",
            subtitle = "The app list is scanned locally after launch",
            status = inventoryStatus,
        ) {
            Text(
                "Package names and app labels are sent to the configured backend only when upload consent is enabled.",
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
                                    else if (updated) "$count apps local; upload not configured"
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

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            enabled = enabledForOverlay,
            onClick = onLaunchOverlay,
        ) {
            Text("LAUNCH CONTROL X")
        }

        ControlCard(title = "Fast-Path test", subtitle = "Try a command without voice") {
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
                                    "No Fast-Path match."
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
                    Text(
                        text = status.uppercase(),
                        color = if (status == "Configured") Color(0xFF4ADE80)
                        else Color(0xFFFCA5A5),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            content()
        }
    }
}
