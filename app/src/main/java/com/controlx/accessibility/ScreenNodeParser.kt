package com.controlx.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.controlx.domain.CleanNode
import com.controlx.domain.ScreenSnapshot
import java.util.concurrent.ConcurrentHashMap

class ScreenNodeParser {
    private val sensitivePattern =
        Regex("""(?i)\b(pin|otp|cvv|password|passcode|security code)\b""")

    private val nodeMap = ConcurrentHashMap<String, AccessibilityNodeInfo>()

    fun captureAndSanitize(
        root: AccessibilityNodeInfo?,
        activityName: String? = null,
    ): ScreenSnapshot {
        nodeMap.clear()
        val nodes = mutableListOf<CleanNode>()
        if (root == null) {
            return ScreenSnapshot(
                packageName = "",
                timestamp = System.currentTimeMillis(),
                nodes = nodes,
                activityName = activityName,
            )
        }

        collect(root, nodes)
        val packageName = root.packageName?.toString().orEmpty()
        return ScreenSnapshot(
            packageName = packageName,
            timestamp = System.currentTimeMillis(),
            nodes = nodes,
            activityName = activityName,
        )
    }

    fun resolveNode(nodeId: String): AccessibilityNodeInfo? =
        nodeMap[nodeId]

    private fun collect(
        node: AccessibilityNodeInfo,
        nodes: MutableList<CleanNode>,
    ) {
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        val sensitive = node.isPassword ||
            text?.let { sensitivePattern.containsMatchIn(it) } == true ||
            description?.let { sensitivePattern.containsMatchIn(it) } == true

        val meaningful = text != null || description != null ||
            node.isClickable || node.isEditable || node.isScrollable
        if (meaningful) {
            val id = "cx_${nodes.size + 1}"
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val cleanText = if (sensitive) "[REDACTED]" else text
            val cleanDescription = if (sensitive) "[REDACTED]" else description
            nodes += CleanNode(
                id = id,
                className = node.className?.toString().orEmpty(),
                text = cleanText,
                contentDescription = cleanDescription,
                viewIdResourceName = node.viewIdResourceName,
                bounds = Rect(bounds),
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isSensitive = sensitive,
            )
            nodeMap[id] = node
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collect(child, nodes)
        }
    }

    fun clear() {
        nodeMap.clear()
    }
}
