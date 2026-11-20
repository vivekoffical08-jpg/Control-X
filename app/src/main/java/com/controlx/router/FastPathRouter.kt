package com.controlx.router

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateUtils
import com.controlx.domain.ActionId
import com.controlx.domain.ActionRequest
import com.controlx.domain.FastPathResult
import com.controlx.domain.RequestSource
import com.controlx.domain.VolumeDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FastPathRouter(private val context: Context) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    suspend fun route(command: String): FastPathResult = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val normalized = command.trim().lowercase()
        val result = when {
            normalized.matches(Regex("""^(go )?home$""")) ->
                launch(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
            normalized.matches(Regex("""^(go )?back$""")) ->
                FastPathResult.Error("Back requires an active AccessibilityService.")
            normalized.matches(Regex("""^(open )?recents$""")) ->
                FastPathResult.Error("Recents requires an active AccessibilityService.")
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
            normalized.contains("set alarm") || normalized.contains("alarm at") ->
                setAlarm(normalized)
            normalized.contains("set timer") || normalized.contains("timer for") ->
                setTimer(normalized)
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
        adjustVolume(stream, direction)
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
