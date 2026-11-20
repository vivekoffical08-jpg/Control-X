package com.controlx.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.controlx.domain.ScrollDirection

class GestureExecutor(
    private val service: AccessibilityService,
) {
    fun executeNodeClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.isEnabled) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        return false
    }

    fun executeCoordinateTap(
        x: Float,
        y: Float,
        onComplete: (Boolean) -> Unit,
    ) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) =
                    onComplete(true)

                override fun onCancelled(gestureDescription: GestureDescription?) =
                    onComplete(false)
            },
            null,
        )
    }

    fun executeTextInjection(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable || node.isPassword) return false
        if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun executeScroll(
        node: AccessibilityNodeInfo?,
        direction: ScrollDirection,
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
                if (target.performAction(action)) return true
            }
            target = target.parent
        }

        val bounds = Rect()
        service.rootInActiveWindow?.getBoundsInScreen(bounds)
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float
        when (direction) {
            ScrollDirection.UP -> {
                startX = centerX; startY = bounds.bottom * 0.75f
                endX = centerX; endY = bounds.top + bounds.height() * 0.25f
            }
            ScrollDirection.DOWN -> {
                startX = centerX; startY = bounds.top + bounds.height() * 0.25f
                endX = centerX; endY = bounds.bottom * 0.75f
            }
            ScrollDirection.LEFT -> {
                startX = bounds.right * 0.75f; startY = centerY
                endX = bounds.left + bounds.width() * 0.25f; endY = centerY
            }
            ScrollDirection.RIGHT -> {
                startX = bounds.left + bounds.width() * 0.25f; startY = centerY
                endX = bounds.right * 0.75f; endY = centerY
            }
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()
        var completed = false
        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completed = false
                }
            },
            null,
        )
        return completed
    }

    fun executeGlobalNavigation(action: Int): Boolean =
        service.performGlobalAction(action)
}
