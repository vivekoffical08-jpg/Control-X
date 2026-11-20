package com.controlx.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class ControlXSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(bundle: Bundle?): VoiceInteractionSession =
        ControlXVoiceSession(this)
}

class ControlXVoiceSession(context: android.content.Context) :
    VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        runCatching {
            val intent = android.content.Intent(
                context,
                com.controlx.MainActivity::class.java,
            ).apply {
                action = com.controlx.MainActivity.ACTION_LAUNCH_OVERLAY
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        hide()
    }
}
