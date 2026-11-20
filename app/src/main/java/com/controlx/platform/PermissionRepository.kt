package com.controlx.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.os.PowerManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import com.controlx.accessibility.ControlAccessibilityService

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
            accessibilityGranted = isAccessibilityServiceEnabled(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
            digitalAssistantGranted = isDigitalAssistantSelected(),
        )
    }

    private fun isDigitalAssistantSelected(): Boolean {
        val resolver = context.contentResolver
        val selectedValues = listOf(
            runCatching {
                Settings.Secure.getString(resolver, "voice_interaction_service")
            }.getOrNull(),
            runCatching {
                Settings.Secure.getString(resolver, "assistant")
            }.getOrNull(),
        )
        val serviceName = "${context.packageName}/${ControlXAssistantComponentName()}"
        return selectedValues.any { value ->
            value.orEmpty().contains(context.packageName) ||
                value.orEmpty().contains(serviceName)
        }
    }

    private fun ControlXAssistantComponentName(): String =
        com.controlx.assistant.ControlXVoiceService::class.java.name

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        )
        val expectedName = "${context.packageName}/${ControlAccessibilityService::class.java.name}"
        return enabledServices.any { enabled ->
            enabled.id == expectedName ||
                enabled.resolveInfo.serviceInfo.name == ControlAccessibilityService::class.java.name
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
