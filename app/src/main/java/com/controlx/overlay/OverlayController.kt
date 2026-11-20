package com.controlx.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.controlx.security.ExecutionGuard

class OverlayController(
    context: Context,
    private val executionGuard: ExecutionGuard = ExecutionGuard.shared,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var bubble: TextView? = null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext) || bubble != null) {
            return false
        }

        val view = TextView(appContext).apply {
            text = "X"
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { executionGuard.stop() }
        }
        val params = WindowManager.LayoutParams(
            64,
            64,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 160
        }

        windowManager.addView(view, params)
        bubble = view
        return true
    }

    fun hide() {
        bubble?.let {
            windowManager.removeView(it)
            bubble = null
        }
    }
}
