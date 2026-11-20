package com.controlx.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.controlx.domain.ScrollDirection

data class Point(val x: Float, val y: Float)

class GestureExecutor(
    private val service: AccessibilityService,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun tap(x: Float, y: Float, onComplete: (Boolean) -> Unit = {}) =
        executeCoordinateTap(x, y, 50L, onComplete)

    fun executeCoordinateTap(
        x: Float,
        y: Float,
        onComplete: (Boolean) -> Unit,
    ) {
        tap(x, y, onComplete)
    }

    fun executeNodeClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.isEnabled) {
                val result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target.recycle()
                return result
            }
            target = target.parent
        }
        return false
    }

    fun doubleTap(x: Float, y: Float, onComplete: (Boolean) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { doubleTap(x, y, onComplete) }
            return
        }
        val first = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, y) },
            0,
            50,
        )
        val second = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, y) },
            100,
            50,
        )
        dispatch(GestureDescription.Builder().addStroke(first).addStroke(second).build(), onComplete)
    }

    fun longPress(
        x: Float,
        y: Float,
        durationMillis: Long = 700,
        onComplete: (Boolean) -> Unit = {},
    ) {
        executeCoordinateTap(x, y, durationMillis.coerceIn(600, 1000), onComplete)
    }

    fun executeCoordinateTap(
        x: Float,
        y: Float,
        durationMillis: Long,
        onComplete: (Boolean) -> Unit,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { executeCoordinateTap(x, y, durationMillis, onComplete) }
            return
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMillis.coerceAtLeast(1),
        )
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onComplete)
    }

    fun swipe(
        start: Point,
        end: Point,
        durationMillis: Long = 260,
        onComplete: (Boolean) -> Unit = {},
    ) {
        dragAndDrop(start, end, durationMillis, onComplete)
    }

    fun dragAndDrop(
        from: Point,
        to: Point,
        durationMillis: Long = 420,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dragAndDrop(from, to, durationMillis, onComplete) }
            return
        }
        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(to.x, to.y)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMillis.coerceIn(50, 5000),
        )
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onComplete)
    }

    fun pinch(
        center: Point,
        startSpan: Float,
        endSpan: Float,
        angleDegrees: Float = 0f,
        durationMillis: Long = 360,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                pinch(center, startSpan, endSpan, angleDegrees, durationMillis, onComplete)
            }
            return
        }
        val angle = Math.toRadians(angleDegrees.toDouble())
        val cos = kotlin.math.cos(angle).toFloat()
        val sin = kotlin.math.sin(angle).toFloat()
        val startHalf = startSpan / 2f
        val endHalf = endSpan / 2f
        fun pair(half: Float): Pair<Point, Point> =
            Point(center.x - cos * half, center.y - sin * half) to
                Point(center.x + cos * half, center.y + sin * half)

        val start = pair(startHalf)
        val end = pair(endHalf)
        val builder = GestureDescription.Builder()
        builder.addStroke(
            GestureDescription.StrokeDescription(
                Path().apply {
                    moveTo(start.first.x, start.first.y)
                    lineTo(end.first.x, end.first.y)
                },
                0,
                durationMillis.coerceIn(50, 5000),
            ),
        )
        builder.addStroke(
            GestureDescription.StrokeDescription(
                Path().apply {
                    moveTo(start.second.x, start.second.y)
                    lineTo(end.second.x, end.second.y)
                },
                0,
                durationMillis.coerceIn(50, 5000),
            ),
        )
        dispatch(builder.build(), onComplete)
    }

    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean =
        executeTextInjection(node, text)

    fun executeTextInjection(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable || node.isPassword) return false
        if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun sendImeAction(
        node: AccessibilityNodeInfo,
        action: ImeAction,
    ): Boolean {
        if (!node.isEditable || node.isPassword) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return when (action) {
            ImeAction.ENTER, ImeAction.SEARCH, ImeAction.SEND -> {
                val bundle = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        node.text?.toString().orEmpty() + "\n",
                    )
                }
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    bundle,
                )
            }
            ImeAction.NEXT ->
                node.performAction(
                    AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                )
            ImeAction.BACKSPACE -> {
                val text = node.text?.toString().orEmpty()
                if (text.isEmpty()) true
                else {
                val bundle = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text.dropLast(1),
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                }
            }
        }
    }

    fun selectAll(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                node.text?.length ?: 0,
            )
        })

    fun copy(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_COPY)

    fun cut(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CUT)

    fun paste(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

    fun executeScroll(
        node: AccessibilityNodeInfo?,
        direction: ScrollDirection,
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        var target = node
        while (target != null) {
            if (target.isScrollable) {
                val action = when (direction) {
                    ScrollDirection.UP, ScrollDirection.LEFT ->
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val result = target.performAction(action)
                if (result) {
                    onComplete(true)
                    return true
                }
            }
            target = target.parent
        }
        val bounds = service.rootInActiveWindow?.let {
            android.graphics.Rect().also { rect -> it.getBoundsInScreen(rect) }
        } ?: return false
        val center = Point(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        val startAndEnd = when (direction) {
            ScrollDirection.UP ->
                Point(center.x, bounds.bottom * 0.78f) to
                    Point(center.x, bounds.top + bounds.height() * 0.22f)
            ScrollDirection.DOWN ->
                Point(center.x, bounds.top + bounds.height() * 0.22f) to
                    Point(center.x, bounds.bottom * 0.78f)
            ScrollDirection.LEFT ->
                Point(bounds.right * 0.78f, center.y) to
                    Point(bounds.left + bounds.width() * 0.22f, center.y)
            ScrollDirection.RIGHT ->
                Point(bounds.left + bounds.width() * 0.22f, center.y) to
                    Point(bounds.right * 0.78f, center.y)
        }
        dragAndDrop(startAndEnd.first, startAndEnd.second, 260, onComplete)
        return true
    }

    fun executeScroll(node: AccessibilityNodeInfo?, direction: ScrollDirection): Boolean =
        executeScroll(node, direction) {}

    fun executeGlobalNavigation(action: Int): Boolean =
        try {
            service.performGlobalAction(action)
        } catch (_: Exception) {
            false
        }

    fun back(): Boolean =
        executeGlobalNavigation(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home(): Boolean =
        executeGlobalNavigation(AccessibilityService.GLOBAL_ACTION_HOME)

    fun recents(): Boolean =
        executeGlobalNavigation(AccessibilityService.GLOBAL_ACTION_RECENTS)

    fun notifications(): Boolean =
        executeGlobalNavigation(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    fun quickSettings(): Boolean =
        executeGlobalNavigation(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

    fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            executeGlobalNavigation(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }

    fun takeScreenshot(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            executeGlobalNavigation(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }

    private fun dispatch(
        gesture: GestureDescription,
        onComplete: (Boolean) -> Unit,
    ) {
        try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) =
                        onComplete(true)

                    override fun onCancelled(gestureDescription: GestureDescription?) =
                        onComplete(false)
                },
                mainHandler,
            )
        } catch (_: Exception) {
            onComplete(false)
        }
    }

    enum class ImeAction {
        ENTER,
        SEARCH,
        SEND,
        NEXT,
        BACKSPACE,
    }
}
