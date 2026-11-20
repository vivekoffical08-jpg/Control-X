package com.controlx

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.controlx.platform.PermissionRepository
import com.controlx.platform.PermissionSnapshot

class MainActivity : ComponentActivity() {
    private var permissions by mutableStateOf(
        PermissionSnapshot(
            microphoneGranted = false,
            notificationsGranted = false,
            overlayGranted = false,
            writeSettingsGranted = false,
        ),
    )

    override fun onResume() {
        super.onResume()
        permissions = PermissionRepository(this).snapshot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionDashboard(
                        permissions = permissions,
                        onPermissionsChanged = { permissions = PermissionRepository(this).snapshot() },
                    )
                }
            }
        }
    }

}

@Composable
private fun PermissionDashboard(
    permissions: PermissionSnapshot,
    onPermissionsChanged: () -> Unit,
) {
    val context = LocalContext.current
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
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Control X", style = MaterialTheme.typography.headlineMedium)
        Text("Safe native Android foundation")
        Text("Microphone: ${status(permissions.microphoneGranted)}")
        Text("Notifications: ${status(permissions.notificationsGranted)}")
        Text("Overlay: ${status(permissions.overlayGranted)}")
        Text("Write settings: ${status(permissions.writeSettingsGranted)}")
        Button(onClick = { permissionLauncher.launch(runtimePermissions) }) {
            Text("Request microphone and notifications")
        }
        Button(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        ) {
            Text("Open overlay settings")
        }
        Button(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        ) {
            Text("Open write-settings access")
        }
    }
}

private fun status(granted: Boolean): String = if (granted) "Ready" else "Not granted"
