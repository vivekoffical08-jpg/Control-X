package com.controlx.accessibility

import android.view.accessibility.AccessibilityNodeInfo

object InterruptionReflex {
    private val dismissPatterns = listOf(
        "skip ad",
        "skip",
        "close",
        "dismiss",
        "not now",
        "no thanks",
        "only this time",
        "cancel",
    )

    fun attemptSafeDismiss(root: AccessibilityNodeInfo): Boolean {
        for (pattern in dismissPatterns) {
            val matches = runCatching {
                root.findAccessibilityNodeInfosByText(pattern)
            }.getOrDefault(emptyList())
            for (node in matches) {
                if (!node.isVisibleToUser || node.isPassword) continue
                val target = if (node.isClickable) {
                    node
                } else {
                    var parent = node.parent
                    while (parent != null && !parent.isClickable) {
                        val next = parent.parent
                        if (parent !== next) parent.recycle()
                        parent = next
                    }
                    parent
                }
                if (target != null &&
                    target.isEnabled &&
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
            }
        }
        return false
    }
}
