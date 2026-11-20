package com.controlx.assistant

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import com.controlx.MainActivity

class AssistTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_LAUNCH_OVERLAY
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        finish()
    }
}
