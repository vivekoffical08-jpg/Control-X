package com.controlx.platform

data class PermissionSnapshot(
    val microphoneGranted: Boolean,
    val notificationsGranted: Boolean,
    val overlayGranted: Boolean,
    val writeSettingsGranted: Boolean,
    val accessibilityGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val digitalAssistantGranted: Boolean = false,
    val deviceAdminActive: Boolean = false,
)
