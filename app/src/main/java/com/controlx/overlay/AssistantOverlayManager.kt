package com.controlx.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.controlx.security.ExecutionGuard

class AssistantOverlayManager(
    context: Context,
    private val executionGuard: ExecutionGuard = ExecutionGuard.shared,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var transcriptView: TextView? = null
    private var latencyView: TextView? = null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext) || panel != null) return false

        val transcript = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        val latency = TextView(appContext).apply {
            setTextColor(Color.argb(255, 100, 220, 255))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            text = "[ 0ms ]"
        }
        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb((0.85 * 255).toInt(), 10, 12, 18))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(
                latency,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                transcript,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            setOnClickListener { executionGuard.stop() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        32f,
                        32f,
                        android.graphics.Shader.TileMode.CLAMP,
                    ),
                )
            }
        }

        val metrics = appContext.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_WINDOW,
            (metrics.heightPixels * 0.45f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(content, params)
        panel = content
        transcriptView = transcript
        latencyView = latency
        return true
    }

    fun updateTranscript(text: String, latencyMs: Long) {
        transcriptView?.text = text
        latencyView?.text = "[ $latencyMs ms ]"
    }

    fun setExecuting(executing: Boolean) {
        panel?.setBackgroundColor(
            if (executing) Color.argb((0.90 * 255).toInt(), 10, 20, 30)
            else Color.argb((0.85 * 255).toInt(), 10, 12, 18),
        )
    }

    fun hide() {
        panel?.let {
            windowManager.removeView(it)
            panel = null
            transcriptView = null
            latencyView = null
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            appContext.resources.displayMetrics,
        ).toInt()
}
