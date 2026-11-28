package com.controlx.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lightweight, draggable quick-trigger overlay badge that launches Control X voice capture
 * over any app using only `SYSTEM_ALERT_WINDOW` (no Accessibility permission required).
 *
 * Automatically stays hidden while [suppressed] is true (e.g., Payment Safe Mode or while the
 * main assistant panel is open).
 */
class FloatingTriggerManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop

    private var bubbleView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var activeLongPressRunnable: Runnable? = null
    private var desiredVisible = false
    private var suppressed = false

    var onActivateRequested: (() -> Unit)? = null
    var onHideRequested: (() -> Unit)? = null

    fun show(): Boolean {
        desiredVisible = true
        runOnMain { syncVisibility() }
        return Settings.canDrawOverlays(appContext)
    }

    fun hide() {
        desiredVisible = false
        runOnMain { removeBubbleView() }
    }

    fun setSuppressed(hidden: Boolean) {
        if (suppressed == hidden) return
        suppressed = hidden
        runOnMain { syncVisibility() }
    }

    fun isShowing(): Boolean = bubbleView != null

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun syncVisibility() {
        val shouldShow = desiredVisible && !suppressed && Settings.canDrawOverlays(appContext)
        if (shouldShow) {
            attachBubbleView()
        } else {
            removeBubbleView()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBubbleView() {
        if (bubbleView != null || !Settings.canDrawOverlays(appContext)) return
        val metrics = appContext.resources.displayMetrics
        val sizePx = dp(48)
        val marginPx = dp(12)

        val params = layoutParams ?: WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - sizePx - marginPx).coerceAtLeast(0)
            y = (metrics.heightPixels * 0.42f).roundToInt()
        }

        val view = TextView(appContext).apply {
            text = "CX"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(100, 220, 255))
            gravity = Gravity.CENTER
            contentDescription = "Ask Control X"
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(232, 18, 24, 38))
                setStroke(dp(2), Color.rgb(79, 209, 197))
            }
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressTriggered = false
        val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(450L)
        val longPressRunnable = Runnable {
            longPressTriggered = true
            onHideRequested?.invoke()
        }
        activeLongPressRunnable = longPressRunnable

        view.setOnTouchListener { v, event ->
            val currentParams = layoutParams ?: return@setOnTouchListener false
            val currentMetrics = appContext.resources.displayMetrics
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = currentParams.x
                    startY = currentParams.y
                    moved = false
                    longPressTriggered = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        val maxX = (currentMetrics.widthPixels - sizePx).coerceAtLeast(0)
                        val minY = dp(24)
                        val maxY = (currentMetrics.heightPixels - sizePx - dp(36)).coerceAtLeast(minY)
                        currentParams.x = (startX + dx.roundToInt()).coerceIn(0, maxX)
                        currentParams.y = (startY + dy.roundToInt()).coerceIn(minY, maxY)
                        runCatching { windowManager.updateViewLayout(v, currentParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered) {
                        onActivateRequested?.invoke()
                    } else if (moved) {
                        val centerX = currentParams.x + sizePx / 2
                        val rightX = (currentMetrics.widthPixels - sizePx - marginPx).coerceAtLeast(0)
                        currentParams.x = if (centerX < currentMetrics.widthPixels / 2) marginPx else rightX
                        runCatching { windowManager.updateViewLayout(v, currentParams) }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        val added = runCatching {
            windowManager.addView(view, params)
            true
        }.getOrDefault(false)

        if (added) {
            bubbleView = view
            layoutParams = params
        } else {
            activeLongPressRunnable = null
        }
    }

    private fun removeBubbleView() {
        activeLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
        activeLongPressRunnable = null
        val view = bubbleView ?: return
        bubbleView = null
        view.setOnTouchListener(null)
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { runCatching { windowManager.removeView(view) } }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        appContext.resources.displayMetrics,
    ).roundToInt()
}
