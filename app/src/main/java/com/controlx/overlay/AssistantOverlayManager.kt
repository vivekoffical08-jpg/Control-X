package com.controlx.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.InputMethodManager
import com.controlx.security.ExecutionGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OverlayState {
    IDLE,
    LISTENING,
    PARSING,
    EXECUTING,
    DISMISS,
}

class AssistantOverlayManager(
    context: Context,
    private val executionGuard: ExecutionGuard = ExecutionGuard.shared,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var scrim: View? = null
    private var transcriptView: TextView? = null
    private var latencyView: TextView? = null
    private var stateView: TextView? = null
    private var transcriptInput: EditText? = null
    private var keyboardVisible = false
    private val _state = MutableStateFlow(OverlayState.IDLE)
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    var onDismissRequested: (() -> Unit)? = null
    var onTranscriptSubmit: ((String) -> Unit)? = null
    var onEmergencyAbort: (() -> Unit)? = null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext) || panel != null) return false
        val metrics = appContext.resources.displayMetrics

        val scrimView = View(appContext).apply {
            setBackgroundColor(0x40000000)
        }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels * 0.55f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }
        windowManager.addView(scrimView, scrimParams)
        scrim = scrimView

        val transcript = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            text = "Listening..."
        }
        val stateBadge = TextView(appContext).apply {
            setTextColor(Color.rgb(255, 196, 92))
            textSize = 11f
            text = "● LISTENING"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = badgeBackground(Color.rgb(255, 196, 92))
        }
        val latency = TextView(appContext).apply {
            setTextColor(Color.argb(255, 100, 220, 255))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            text = "[ 0ms ]"
        }

        val closeButton = Button(appContext).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            setOnClickListener { onDismissRequested?.invoke() }
        }
        val keyboardButton = Button(appContext).apply {
            text = "⌨"
            textSize = 15f
            setTextColor(Color.WHITE)
            setOnClickListener { toggleKeyboard() }
        }
        val abortButton = Button(appContext).apply {
            text = "STOP"
            textSize = 11f
            setTextColor(Color.WHITE)
            setOnClickListener { onEmergencyAbort?.invoke() }
        }
        val input = EditText(appContext).apply {
            hint = "Type a command"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(180, 255, 255, 255))
            visibility = View.GONE
            setOnEditorActionListener { view, _, _ ->
                val text = text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) onTranscriptSubmit?.invoke(text)
                true
            }
        }

        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(appContext).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                    setMargins(0, 0, dp(10), 0)
                }
                setBackgroundColor(Color.rgb(100, 220, 255))
            })
            addView(stateBadge)
            addView(latency)
            addView(View(appContext), LinearLayout.LayoutParams(0, 1, 1f))
            addView(keyboardButton)
            addView(abortButton)
            addView(closeButton)
        }
        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb((0.88 * 255).toInt(), 10, 12, 18))
            setPadding(dp(18), dp(12), dp(18), dp(18))
            addView(header)
            addView(
                transcript,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply { setMargins(0, dp(12), 0, dp(10)) },
            )
            addView(input)
            setOnClickListener { onEmergencyAbort?.invoke() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels * 0.45f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(content, params)
        panel = content
        transcriptView = transcript
        latencyView = latency
        stateView = stateBadge
        transcriptInput = input
        setState(OverlayState.LISTENING)
        return true
    }

    fun updateTranscript(text: String, latencyMs: Long) {
        transcriptView?.text = text
        latencyView?.text = "[ $latencyMs ms ]"
    }

    fun setState(state: OverlayState) {
        _state.value = state
        val badge = stateView ?: return
        val (text, color) = when (state) {
            OverlayState.IDLE -> "● IDLE" to Color.LTGRAY
            OverlayState.LISTENING -> "● LISTENING" to Color.rgb(255, 196, 92)
            OverlayState.PARSING -> "◌ PARSING" to Color.rgb(100, 220, 255)
            OverlayState.EXECUTING -> "● EXECUTING" to Color.rgb(90, 240, 170)
            OverlayState.DISMISS -> "● DISMISS" to Color.WHITE
        }
        badge.text = text
        badge.setTextColor(color)
        badge.background = badgeBackground(color)
    }

    fun hide() {
        panel?.let {
            windowManager.removeView(it)
            panel = null
            transcriptView = null
            latencyView = null
            stateView = null
            transcriptInput = null
        }
        scrim?.let {
            windowManager.removeView(it)
            scrim = null
        }
        keyboardVisible = false
        _state.value = OverlayState.DISMISS
    }

    private fun toggleKeyboard() {
        val input = transcriptInput ?: return
        val params = panel?.layoutParams as? WindowManager.LayoutParams ?: return
        keyboardVisible = !keyboardVisible
        input.visibility = if (keyboardVisible) View.VISIBLE else View.GONE
        params.flags = if (keyboardVisible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv() or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(panel, params)
        if (keyboardVisible) {
            input.requestFocus()
            val inputMethodManager =
                appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } else {
            val inputMethodManager =
                appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun badgeBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(35, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(1), color)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            appContext.resources.displayMetrics,
        ).toInt()
}
