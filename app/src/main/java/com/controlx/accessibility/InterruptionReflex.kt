package com.controlx.accessibility

import android.view.accessibility.AccessibilityNodeInfo

object InterruptionReflex {
    fun attemptSafeDismiss(root: AccessibilityNodeInfo): Boolean =
        false
}
