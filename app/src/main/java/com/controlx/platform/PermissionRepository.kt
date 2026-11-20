package com.controlx.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

class PermissionRepository(
    private val context: Context,
) {
    fun snapshot(): PermissionSnapshot {
        val microphoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

        return PermissionSnapshot(
            microphoneGranted = microphoneGranted,
            notificationsGranted = notificationsGranted,
            overlayGranted = Settings.canDrawOverlays(context),
            writeSettingsGranted = Settings.System.canWrite(context),
        )
    }
}
