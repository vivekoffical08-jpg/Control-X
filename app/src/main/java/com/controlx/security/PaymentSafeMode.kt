package com.controlx.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PaymentAppPolicy {
    private val packagePrefixes = listOf(
        "com.phonepe",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa",
        "in.amazon.mshop",
    )

    private val packageKeywords = listOf(
        "bank",
        "upi",
        "pay",
        "payment",
        "wallet",
        "checkout",
    )

    fun matches(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        return packagePrefixes.any(normalized::startsWith) ||
            packageKeywords.any(normalized::contains)
    }
}

data class PaymentSafeModeState(
    val active: Boolean = false,
    val packageName: String? = null,
    val accessibilityAutoDisabled: Boolean = false,
)

class PaymentSafeModeMonitor {
    companion object {
        val shared = PaymentSafeModeMonitor()
    }

    private val _state = MutableStateFlow(PaymentSafeModeState())
    val state: StateFlow<PaymentSafeModeState> = _state.asStateFlow()
    val isActive: Boolean
        get() = _state.value.active

    fun onForegroundPackageChanged(packageName: String?) {
        val normalized = packageName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        if (PaymentAppPolicy.matches(normalized)) {
            _state.value = PaymentSafeModeState(
                active = true,
                packageName = normalized,
                accessibilityAutoDisabled = _state.value.accessibilityAutoDisabled,
            )
        } else if (!normalized.startsWith("com.android.systemui") &&
            normalized != "android" &&
            _state.value.active
        ) {
            _state.value = PaymentSafeModeState()
        }
    }

    fun markAccessibilityDisabled() {
        _state.value = _state.value.copy(accessibilityAutoDisabled = true)
    }

    fun reset() {
        _state.value = PaymentSafeModeState()
    }
}
