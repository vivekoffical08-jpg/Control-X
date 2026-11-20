package com.controlx.agent

import android.view.accessibility.AccessibilityNodeInfo
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.accessibility.GestureExecutor
import com.controlx.accessibility.Point
import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot
import com.controlx.security.SafetyGuardian
import com.controlx.security.SafetyVerdict
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AgentNodeExecutor(
    private val service: ControlAccessibilityService,
) : ReActEngine.ActionExecutor {
    private val gestureExecutor = GestureExecutor(service)

    override suspend fun execute(
        action: AgentAction,
        snapshot: ScreenSnapshot,
    ): Boolean {
        val verdict = SafetyGuardian().evaluateActionSafety(action, snapshot.packageName)
        if (verdict != SafetyVerdict.Allowed) return false

        return when (action.action) {
            ActionType.CLICK -> executeClick(action)
            ActionType.TYPE -> executeType(action)
            ActionType.SCROLL -> executeScroll(action)
            ActionType.SYSTEM_ACTION -> executeSystemAction(action)
            ActionType.FINISH -> true
            ActionType.FAIL -> false
        }
    }

    private suspend fun executeClick(action: AgentAction): Boolean {
        if (action.targetNodeId != null) {
            val node = service.resolveNode(action.targetNodeId) ?: return false
            if (node.isPassword || node.isSensitiveForControlX()) return false
            val result = gestureExecutor.executeNodeClick(node)
            if (result) return true
        }

        val coordinates = action.coordinates
        if (coordinates != null && coordinates.size >= 2) {
            return suspendCancellableCoroutine { continuation ->
                gestureExecutor.tap(
                    coordinates[0],
                    coordinates[1],
                ) { completed ->
                    if (continuation.isActive) continuation.resume(completed)
                }
            }
        }
        return false
    }

    private fun executeType(action: AgentAction): Boolean {
        val nodeId = action.targetNodeId ?: return false
        val node = service.resolveNode(nodeId) ?: return false
        val text = action.textInput ?: return false
        if (node.isPassword || node.isSensitiveForControlX()) return false
        return gestureExecutor.inputText(node, text)
    }

    private suspend fun executeScroll(action: AgentAction): Boolean {
        val direction = runCatching {
            com.controlx.domain.ScrollDirection.valueOf(
                action.scrollDirection?.uppercase() ?: "DOWN",
            )
        }.getOrDefault(com.controlx.domain.ScrollDirection.DOWN)

        if (action.targetNodeId != null) {
            val node = service.resolveNode(action.targetNodeId)
            if (node != null) {
                return gestureExecutor.executeScroll(node, direction)
            }
        }
        return suspendCancellableCoroutine { continuation ->
            gestureExecutor.executeScroll(
                service.rootInActiveWindow,
                direction,
            ) { completed ->
                if (continuation.isActive) continuation.resume(completed)
            }
        }
    }

    private fun executeSystemAction(action: AgentAction): Boolean {
        val command = action.systemCommand?.uppercase() ?: return false
        val globalAction = when (command) {
            "BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "LOCK_SCREEN" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "TAKE_SCREENSHOT" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return service.performGlobalAction(globalAction)
    }

    private fun AccessibilityNodeInfo.isSensitiveForControlX(): Boolean =
        isPassword ||
            text?.contains(Regex("""(?i)\b(pin|otp|cvv|password|passcode)\b""")) == true ||
            contentDescription?.contains(
                Regex("""(?i)\b(pin|otp|cvv|password|passcode)\b"""),
            ) == true
}
