package com.controlx.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.controlx.MainActivity
import com.controlx.overlay.OverlayControllerService
import com.controlx.platform.PermissionRepository

class ControlXSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(bundle: Bundle?): VoiceInteractionSession =
        ControlXVoiceSession(this)
}

class ControlXVoiceSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    private var lastLaunchElapsedMs: Long = 0L

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        launchAssistant()
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        launchAssistant()
    }

    override fun onHide() {
        super.onHide()
        lastLaunchElapsedMs = 0L
    }

    private fun launchAssistant() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchElapsedMs < LAUNCH_DEBOUNCE_MS) {
            hide()
            return
        }
        lastLaunchElapsedMs = now

        val permissions = PermissionRepository(context).snapshot()
        val launchedDirectly = if (permissions.microphoneGranted && permissions.overlayGranted) {
            runCatching {
                OverlayControllerService.start(context)
                true
            }.getOrDefault(false)
        } else {
            false
        }

        if (!launchedDirectly) {
            runCatching {
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_LAUNCH_OVERLAY
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
        hide()
    }

    private companion object {
        private const val LAUNCH_DEBOUNCE_MS = 600L
    }
}
