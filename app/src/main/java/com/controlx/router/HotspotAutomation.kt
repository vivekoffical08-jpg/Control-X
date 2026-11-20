package com.controlx.router

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.controlx.accessibility.ControlAccessibilityService
import kotlinx.coroutines.delay

object HotspotAutomation {
    suspend fun toggle(context: Context): Boolean {
        context.startActivity(
            Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            },
        )
        delay(500)

        val service = ControlAccessibilityService.activeInstance() ?: return false
        val root = service.rootInActiveWindow ?: return false
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            val text = node.text?.toString().orEmpty().lowercase()
            val description =
                node.contentDescription?.toString().orEmpty().lowercase()
            val looksLikeHotspot =
                text.contains("hotspot") ||
                    text.contains("tether") ||
                    description.contains("hotspot") ||
                    description.contains("tether")
            if (looksLikeHotspot &&
                (node.isCheckable || node.isClickable)
            ) {
                val completed = node.performAction(
                    if (node.isCheckable) {
                        AccessibilityNodeInfo.ACTION_CLICK
                    } else {
                        AccessibilityNodeInfo.ACTION_CLICK
                    },
                )
                delay(250)
                return completed
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(deque::add)
            }
        }
        return false
    }
}
