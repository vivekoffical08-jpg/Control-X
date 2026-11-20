package com.controlx.router

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateUtils
import android.net.Uri
import android.content.IntentFilter
import android.os.BatteryManager
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.domain.ScrollDirection
import com.controlx.domain.ActionId
import com.controlx.domain.ActionRequest
import com.controlx.domain.FastPathResult
import com.controlx.domain.RequestSource
import com.controlx.domain.VolumeDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.controlx.security.SensitiveActionPolicy
import com.controlx.security.SensitiveActionDecision
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class FastPathRouter(private val context: Context) {
    private val sensitiveActionPolicy = SensitiveActionPolicy()
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    suspend fun route(command: String): FastPathResult = withContext(Dispatchers.Default) {
        when (val sensitive = sensitiveActionPolicy.evaluate(command)) {
            is SensitiveActionDecision.Blocked -> {
                return@withContext FastPathResult.Error(sensitive.reason)
            }
            SensitiveActionDecision.Allowed -> Unit
        }
        val started = System.nanoTime()
        val normalized = command.trim().lowercase()
        val result = when {
            normalized.matches(Regex("""^(close|exit)\b.*""")) ->
                if (ControlAccessibilityService.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
                    )
                ) FastPathResult.Executed("Returned Home.", 0)
                else launch(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
            normalized.matches(Regex("""^(go )?home$""")) ->
                if (ControlAccessibilityService.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
                    )
                ) FastPathResult.Executed("Home opened.", 0)
                else launch(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
            normalized.matches(Regex("""^(go )?back$""")) ->
                if (ControlAccessibilityService.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                    )
                ) FastPathResult.Executed("Back completed.", 0)
                else FastPathResult.Error("Enable Accessibility to use Back.")
            normalized.matches(Regex("""^(open )?recents$""")) ->
                if (ControlAccessibilityService.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS,
                    )
                ) FastPathResult.Executed("Recents opened.", 0)
                else FastPathResult.Error("Enable Accessibility to use Recents.")
            normalized.contains("quick settings") ->
                globalNavigation(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                    "Quick Settings",
                )
            normalized.contains("notifications") ||
                normalized.contains("notification panel") ->
                globalNavigation(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                    "Notifications",
                )
            normalized.matches(Regex("""^(search|google|look up|find)\b.*""")) ->
                searchInternet(normalized)
            normalized.matches(Regex("""^(lock|lock screen)$""")) ->
                lockScreen()
            normalized.contains("screenshot") ||
                normalized.contains("take screen") ->
                globalNavigation(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
                    "Screenshot captured",
                )
            normalized.contains("scroll up") ->
                coordinateScroll(ScrollDirection.UP, "Scrolled up.")
            normalized.contains("scroll down") ->
                coordinateScroll(ScrollDirection.DOWN, "Scrolled down.")
            normalized.contains("scroll left") ->
                coordinateScroll(ScrollDirection.LEFT, "Scrolled left.")
            normalized.contains("scroll right") ->
                coordinateScroll(ScrollDirection.RIGHT, "Scrolled right.")
            normalized.contains("flashlight") ||
                normalized.contains("torch") ->
                setTorch(normalized.contains("on") || normalized.contains("enable"))
            Regex("""(?i)\bopen\b\s+(.+)""").matches(normalized) ->
                openAppCommand(normalized)
            normalized.contains("wifi") ->
                launchSettings(Settings.Panel.ACTION_WIFI)
            normalized.contains("bluetooth") ->
                launchSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            normalized.contains("brightness") ->
                launchSettings(Settings.ACTION_DISPLAY_SETTINGS)
            normalized.contains("hotspot") ||
                normalized.contains("tether") ->
                launchSettings(Settings.ACTION_WIRELESS_SETTINGS)
            normalized.contains("do not disturb") ||
                normalized.contains("dnd") ->
                launchSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            normalized.contains("set alarm") || normalized.contains("alarm at") ->
                setAlarm(normalized)
            normalized.contains("set timer") || normalized.contains("timer for") ->
                setTimer(normalized)
            normalized.matches(Regex("""^(call|dial)\b.*""")) ->
                dialCommand(normalized)
            normalized.contains("battery") ->
                batteryStatus()
            normalized.contains("volume") ->
                adjustVolumeByCommand(normalized)
            else -> FastPathResult.Unhandled
        }

        if (result is FastPathResult.Executed) {
            FastPathResult.Executed(
                result.message,
                (System.nanoTime() - started) / 1_000_000,
            )
        } else {
            result
        }
    }

    fun launchApp(query: String): Boolean {
        val exactIntent = context.packageManager.getLaunchIntentForPackage(query)
        if (exactIntent != null) {
            context.startActivity(exactIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        val normalized = query.trim().lowercase()
        val resolved = context.packageManager.getInstalledApplications(0)
            .firstOrNull { application ->
                context.packageManager.getApplicationLabel(application)
                    .toString()
                    .trim()
                    .lowercase() == normalized
            } ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun setTorch(enabled: Boolean): FastPathResult {
        val cameraId = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return FastPathResult.Error("No torch-capable camera found.")
        return try {
            cameraManager.setTorchMode(cameraId, enabled)
            FastPathResult.Executed(
                if (enabled) "Flashlight on." else "Flashlight off.",
                0,
            )
        } catch (error: Exception) {
            FastPathResult.Error(error.message ?: "Unable to control flashlight.")
        }
    }

    fun adjustVolume(
        stream: Int,
        direction: VolumeDirection,
    ) {
        val adjustment = if (direction == VolumeDirection.UP) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }
        audioManager.adjustStreamVolume(
            stream,
            adjustment,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun adjustVolumeByCommand(normalized: String): FastPathResult {
        val stream = when {
            normalized.contains("ring") -> AudioManager.STREAM_RING
            normalized.contains("alarm") -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val direction = if (normalized.contains("down") || normalized.contains("lower")) {
            VolumeDirection.DOWN
        } else {
            VolumeDirection.UP
        }
        val percentMatch = Regex("""(?i)(?:set|to)\s*(\d{1,3})(?:\s*%|\s*percent)?""")
            .find(normalized)
        val percent = percentMatch?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null) {
            val bounded = percent.coerceIn(0, 100)
            val max = audioManager.getStreamMaxVolume(stream)
            val index = (bounded * max / 100.0).toInt()
            audioManager.setStreamVolume(
                stream,
                index,
                AudioManager.FLAG_SHOW_UI,
            )
        } else {
            adjustVolume(stream, direction)
        }
        return FastPathResult.Executed("Volume adjusted.", 0)
    }

    private fun openAppCommand(normalized: String): FastPathResult {
        val query = Regex("""(?i)^open\s+(.+)$""").find(normalized)?.groupValues?.get(1)
            ?: return FastPathResult.Unhandled
        return if (launchApp(query)) {
            FastPathResult.Executed("Opened $query.", 0)
        } else {
            FastPathResult.Error("No launchable app matched '$query'.")
        }
    }

    private fun searchInternet(normalized: String): FastPathResult {
        val query = Regex("""(?i)^(?:search|google|look up|find)(?:\s+(?:for|about|on))?\s+(.+)""")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return FastPathResult.Error("Say 'search for' followed by a topic.")

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Opened browser search for '$query'.")
    }

    private fun globalNavigation(action: Int, message: String): FastPathResult =
        if (ControlAccessibilityService.performGlobalAction(action)) {
            FastPathResult.Executed(message, 0)
        } else {
            FastPathResult.Error("Enable Control X Accessibility to use $message.")
        }

    private fun lockScreen(): FastPathResult {
        val policyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(
            context,
            com.controlx.admin.ControlXDeviceAdminReceiver::class.java,
        )
        if (policyManager.isAdminActive(component)) {
            return try {
                policyManager.lockNow()
                FastPathResult.Executed("Screen locked through Device Admin.", 0)
            } catch (error: Exception) {
                FastPathResult.Error(error.message ?: "Unable to lock the screen.")
            }
        }
        return globalNavigation(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
            "Screen locked",
        )
    }

    private fun coordinateScroll(direction: ScrollDirection, message: String): FastPathResult =
        if (ControlAccessibilityService.scroll(direction)) {
            FastPathResult.Executed(message, 0)
        } else {
            FastPathResult.Error("Enable Accessibility and keep a scrollable screen visible.")
        }

    private fun dialCommand(normalized: String): FastPathResult {
        val digits = Regex("""\b(?:\+?\d[\d\s-]{5,}\d)\b""")
            .find(normalized)
            ?.value
            ?.filter { it.isDigit() || it == '+' }
        val phoneUri = digits?.let {
            Uri.fromParts("tel", it, null)
        } ?: return FastPathResult.Error(
            "Say 'call' followed by a number. Contact-name dialing will require confirmation.",
        )
        return launch(
            Intent(Intent.ACTION_DIAL, phoneUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun batteryStatus(): FastPathResult {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return FastPathResult.Error("Battery status is unavailable.")
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val charging = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
            listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return if (percent >= 0) {
            FastPathResult.Executed(
                "Battery is $percent percent${if (charging) " and charging" else ""}.",
                0,
            )
        } else {
            FastPathResult.Error("Battery percentage is unavailable.")
        }
    }

    private fun launchSettings(action: String): FastPathResult =
        launch(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun launch(intent: Intent): FastPathResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            FastPathResult.Executed("Action dispatched.", 0)
        } catch (error: Exception) {
            FastPathResult.Error(error.message ?: "Unable to dispatch action.")
        }
    }

    private fun launch(intent: Intent, successMessage: String): FastPathResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            FastPathResult.Executed(successMessage, 0)
        } catch (error: Exception) {
            FastPathResult.Error(error.message ?: "Unable to dispatch action.")
        }
    }

    private fun setAlarm(command: String): FastPathResult {
        val time = Regex("""(?i)\b([01]?\d|2[0-3]):([0-5]\d)\b""")
            .find(command)
        val now = java.util.Calendar.getInstance()
        val hour: Int
        val minute: Int
        if (time != null) {
            hour = time.groupValues[1].toInt()
            minute = time.groupValues[2].toInt()
        } else {
            val words = Regex("""(?i)\b(\d{1,2})\s+(am|pm)\b""").find(command)
            if (words == null) return FastPathResult.Error("No valid alarm time found.")
            val parsedHour = words.groupValues[1].toInt()
            hour = parsedHour % 12 + if (words.groupValues[2].equals("pm", true)) 12 else 0
            minute = 0
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent)
    }

    private fun setTimer(command: String): FastPathResult {
        val minuteMatch = Regex("""(?i)\b(\d{1,4})\s*(minute|min)s?\b""").find(command)
        val secondMatch = Regex("""(?i)\b(\d{1,4})\s*(second|sec)s?\b""").find(command)
        val seconds = minuteMatch?.groupValues?.get(1)?.toLongOrNull()?.times(60)
            ?: secondMatch?.groupValues?.get(1)?.toLongOrNull()
            ?: return FastPathResult.Error("No valid timer duration found.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent)
    }
}
