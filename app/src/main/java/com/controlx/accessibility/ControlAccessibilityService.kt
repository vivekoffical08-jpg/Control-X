package com.controlx.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.platform.CommandApiClient
import com.controlx.security.ExecutionGuard
import com.controlx.security.PaymentAppPolicy
import com.controlx.security.PaymentSafeModeMonitor
import com.controlx.security.SafetyGuardian
import com.controlx.security.SafetyVerdict
import com.controlx.security.SensitiveActionDecision
import com.controlx.security.SensitiveActionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ControlAccessibilityService : AccessibilityService() {
    companion object {
        val executionGuard = ExecutionGuard.shared

        @Volatile
        private var activeInstance: ControlAccessibilityService? = null

        @Volatile
        private var autoDisableOnPayment: Boolean = true

        fun isActive(): Boolean = activeInstance != null

        fun activeInstance(): ControlAccessibilityService? = activeInstance

        fun syncAutoDisableOnPayment(context: Context) {
            autoDisableOnPayment = runCatching {
                CommandApiClient(context).autoDisableAccessibilityOnPayment()
            }.getOrDefault(true)
        }

        fun setAutoDisableOnPayment(enabled: Boolean) {
            autoDisableOnPayment = enabled
        }

        fun disableForPaymentSafety(): Boolean {
            val service = activeInstance ?: return false
            service.clearScreenNodes()
            executionGuard.stop()
            PaymentSafeModeMonitor.shared.markAccessibilityDisabled()
            activeInstance = null
            runCatching { service.disableSelf() }
            return true
        }

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
        syncAutoDisableOnPayment(this)
        handleForegroundSafety(rootInActiveWindow?.packageName?.toString())
    }

    private val _snapshot = MutableStateFlow<AccessibilitySnapshot?>(null)
    val snapshot: StateFlow<AccessibilitySnapshot?> = _snapshot.asStateFlow()
    private val nodeParser = ScreenNodeParser()

    @Volatile
    private var foregroundActivityName: String? = null

    fun captureScreenSnapshot(): com.controlx.domain.ScreenSnapshot =
        nodeParser.captureAndSanitize(rootInActiveWindow, foregroundActivityName)

    fun resolveNode(nodeId: String): AccessibilityNodeInfo? =
        nodeParser.resolveNode(nodeId)

    fun clearScreenNodes() {
        _snapshot.value = null
        nodeParser.clear()
    }

    fun typeIntoFocusedNode(text: String): Boolean {
        if (text.isBlank() || PaymentSafeModeMonitor.shared.isActive) return false
        if (SensitiveActionPolicy().evaluate(text) is SensitiveActionDecision.Blocked) return false
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            val packageName = root.packageName?.toString().orEmpty()
            val verdict = SafetyGuardian().evaluateActionSafety(
                AgentAction(
                    thought = "User requested direct text entry.",
                    action = ActionType.TYPE,
                    targetNodeId = null,
                    coordinates = null,
                    textInput = text,
                    scrollDirection = null,
                    systemCommand = null,
                    expectedOutcome = "Text entered in the focused field.",
                ),
                packageName,
            )
            if (verdict != SafetyVerdict.Allowed ||
                PaymentAppPolicy.matches(packageName) ||
                target.isPassword ||
                !target.isEditable ||
                target.text?.contains(
                    Regex("""(?i)\b(pin|otp|cvv|password|passcode|security code)\b"""),
                ) == true ||
                target.contentDescription?.contains(
                    Regex("""(?i)\b(pin|otp|cvv|password|passcode|security code)\b"""),
                ) == true
            ) {
                false
            } else {
                GestureExecutor(this).inputText(target, text)
            }
        } finally {
            target.recycle()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundActivityName = event.className?.toString()
            handleForegroundSafety(event.packageName?.toString())
        }
        if (PaymentSafeModeMonitor.shared.isActive) {
            clearScreenNodes()
            return
        }
        val root = rootInActiveWindow ?: return
        _snapshot.value = AccessibilitySnapshot(
            packageName = root.packageName?.toString(),
            nodes = collectNodes(root),
        )
    }

    private fun handleForegroundSafety(packageName: String?) {
        val safeMode = PaymentSafeModeMonitor.shared
        safeMode.onForegroundPackageChanged(packageName)
        if (!safeMode.isActive) return

        executionGuard.stop()
        clearScreenNodes()
        if (autoDisableOnPayment) {
            safeMode.markAccessibilityDisabled()
            if (activeInstance === this) activeInstance = null
            runCatching { disableSelf() }
        }
    }

    override fun onInterrupt() {
        clearScreenNodes()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        PaymentSafeModeMonitor.shared.reset()
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
