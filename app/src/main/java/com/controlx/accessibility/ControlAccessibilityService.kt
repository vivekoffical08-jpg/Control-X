package com.controlx.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import com.controlx.security.ExecutionGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ControlAccessibilityService : AccessibilityService() {
    companion object {
        val executionGuard = ExecutionGuard.shared
        @Volatile
        private var activeInstance: ControlAccessibilityService? = null

        fun isActive(): Boolean = activeInstance != null

        fun activeInstance(): ControlAccessibilityService? = activeInstance

        fun performGlobalAction(action: Int): Boolean =
            activeInstance?.performGlobalAction(action) == true

        fun scroll(direction: com.controlx.domain.ScrollDirection): Boolean {
            val service = activeInstance ?: return false
            val executor = GestureExecutor(service)
            return executor.executeScroll(
                service.rootInActiveWindow,
                direction,
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    private val _snapshot = MutableStateFlow<AccessibilitySnapshot?>(null)
    val snapshot: StateFlow<AccessibilitySnapshot?> = _snapshot.asStateFlow()
    private val nodeParser = ScreenNodeParser()

    fun captureScreenSnapshot(): com.controlx.domain.ScreenSnapshot =
        nodeParser.captureAndSanitize(rootInActiveWindow)

    fun resolveNode(nodeId: String): AccessibilityNodeInfo? =
        nodeParser.resolveNode(nodeId)

    fun clearScreenNodes() = nodeParser.clear()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        if (event?.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            )
        ) {
            InterruptionReflex.attemptSafeDismiss(root)
        }
        _snapshot.value = AccessibilitySnapshot(
            packageName = root.packageName?.toString(),
            nodes = collectNodes(root),
        )
    }

    override fun onInterrupt() {
        _snapshot.value = null
        nodeParser.clear()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            executionGuard.stop()
            return true
        }
        return super.onKeyEvent(event)
    }

    fun performSafeGlobalAction(action: SafeGlobalAction): Boolean {
        return when (action) {
            SafeGlobalAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            SafeGlobalAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            SafeGlobalAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeSnapshot> {
        val result = mutableListOf<AccessibilityNodeSnapshot>()
        collectNode(root, "0", result)
        return result
    }

    private fun collectNode(
        node: AccessibilityNodeInfo,
        nodeId: String,
        result: MutableList<AccessibilityNodeSnapshot>,
    ) {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val safeText = if (node.isPassword) null else node.text?.toString()
        val safeDescription = if (node.isPassword) null else node.contentDescription?.toString()
        result += AccessibilityNodeSnapshot(
            nodeId = nodeId,
            className = node.className?.toString(),
            text = safeText,
            contentDescription = safeDescription,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
        )

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectNode(child, "$nodeId.$index", result)
                child.recycle()
            }
        }
    }
}

enum class SafeGlobalAction {
    BACK,
    HOME,
    RECENTS,
}
