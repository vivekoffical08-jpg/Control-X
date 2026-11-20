package com.controlx

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.domain.FastPathResult
import com.controlx.overlay.OverlayController
import com.controlx.platform.PermissionRepository
import com.controlx.platform.PermissionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_LAUNCH_OVERLAY = "com.controlx.action.LAUNCH_OVERLAY"
    }

    private val repository by lazy { PermissionRepository(this) }
    private var permissions by mutableStateOf(repository.snapshot())
    private lateinit var overlayController: OverlayController
    private var startupCrash by mutableStateOf(
        (application as? ControlXApplication)
            ?.let { ControlXApplication.readLastCrash(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayController = OverlayController(this)
        handleLaunchIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionDashboard(
                        permissions = permissions,
                        startupCrash = startupCrash,
                        onPermissionsChanged = { permissions = repository.snapshot() },
                        onLaunchOverlay = {
                            if (permissions.microphoneGranted &&
                                permissions.overlayGranted
                            ) {
                                overlayController.show()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        if (intent.action != ACTION_LAUNCH_OVERLAY) return
        val snapshot = PermissionRepository(this).snapshot()
        if (snapshot.microphoneGranted && snapshot.overlayGranted) {
            overlayController.show()
        }
    }

    override fun onResume() {
        super.onResume()
        permissions = repository.snapshot()
    }

    override fun onDestroy() {
        if (::overlayController.isInitialized) overlayController.destroy()
        super.onDestroy()
    }
}

@Composable
private fun PermissionDashboard(
    permissions: PermissionSnapshot,
    startupCrash: String?,
    onPermissionsChanged: () -> Unit,
    onLaunchOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnosticCommand by mutableStateOf("")
    var diagnosticResult by mutableStateOf("Enter a Fast-Path command.")
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { onPermissionsChanged() },
    )
    val runtimePermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Control X", style = MaterialTheme.typography.headlineMedium)
        Text("Fast-Path System Diagnostics")
        if (!startupCrash.isNullOrBlank()) {
            Text(
                text = "Last startup crash:\n$startupCrash",
                style = MaterialTheme.typography.bodySmall,
            )
        }

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
        PermissionRow("Accessibility", permissions.accessibilityGranted) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        PermissionRow("Battery exemption", permissions.batteryOptimizationIgnored) {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = permissions.microphoneGranted && permissions.overlayGranted,
            onClick = onLaunchOverlay,
        ) {
            Text("[ LAUNCH CONTROL X OVERLAY ]")
        }

        Text("FAST-PATH TEST")
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = diagnosticCommand,
            onValueChange = { diagnosticCommand = it },
            singleLine = true,
            placeholder = { Text("turn on flashlight") },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val command = diagnosticCommand
                    scope.launch(Dispatchers.Default) {
                        val result = com.controlx.router.FastPathRouter(context)
                            .route(command)
                        val message = when (result) {
                            is FastPathResult.Executed ->
                                "✓ ${result.message} (${result.latencyMs} ms)"
                            FastPathResult.Unhandled ->
                                "No Fast-Path match."
                            is FastPathResult.Error ->
                                "✕ ${result.reason}"
                        }
                        scope.launch(Dispatchers.Main) {
                            diagnosticResult = message
                        }
                    }
                },
            ) {
                Text("TEST")
            }
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    diagnosticCommand = "turn on flashlight"
                },
            ) {
                Text("FLASHLIGHT")
            }
        }
        Text(diagnosticResult)
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
    ) {
        Text(
            text = "$label: ${if (granted) "READY" else "NOT GRANTED"}",
            modifier = Modifier.weight(1f),
        )
        if (!granted) {
            Button(onClick = onResolve) {
                Text("RESOLVE")
            }
        }
    }
}
