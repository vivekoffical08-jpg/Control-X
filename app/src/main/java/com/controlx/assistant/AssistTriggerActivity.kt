package com.controlx.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.controlx.MainActivity
import com.controlx.overlay.OverlayControllerService
import com.controlx.platform.PermissionRepository

class AssistTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = PermissionRepository(this).snapshot()
        val launchedDirectly = if (permissions.microphoneGranted && permissions.overlayGranted) {
            runCatching {
                OverlayControllerService.start(this)
                true
            }.getOrDefault(false)
        } else {
            false
        }

        if (!launchedDirectly) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_LAUNCH_OVERLAY
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
