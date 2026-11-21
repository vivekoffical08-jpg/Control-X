package com.controlx.security

import com.controlx.domain.AgentAction

sealed interface SafetyVerdict {
    data object Allowed : SafetyVerdict
    data object Blocked : SafetyVerdict
    data object RequiresBiometric : SafetyVerdict
}

class SafetyGuardian {
    private val executionGuard = ExecutionGuard.shared

    private val sensitivePackages = setOf(
        "com.google.android.apps.authenticator2",
        "com.android.settings",
        "com.google.android.gms",
    )

    private val protectedPackagePrefixes = listOf(
        "com.google.android.apps.authenticator",
        "com.android.settings",
        "com.android.vending",
        "com.google.android.gms",
        "com.phonepe",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa",
        "in.amazon.mShop",
    )

    private val protectedPackageKeywords = listOf(
        "bank",
        "upi",
        "pay",
        "payment",
        "wallet",
        "checkout",
    )

    fun evaluateActionSafety(
        action: AgentAction,
        currentPackage: String,
    ): SafetyVerdict {
        val normalizedPackage = currentPackage.lowercase()
        if (currentPackage in sensitivePackages ||
            protectedPackagePrefixes.any(normalizedPackage::startsWith) ||
            protectedPackageKeywords.any(normalizedPackage::contains)
        ) return SafetyVerdict.Blocked

        val combined = buildString {
            append(action.thought)
            append(' ')
            append(action.textInput.orEmpty())
            append(' ')
            append(action.systemCommand.orEmpty())
        }.lowercase()

        if (Regex("""(?i)\b(password|pin|otp|cvv|seed phrase)\b""").containsMatchIn(combined)) {
            return SafetyVerdict.Blocked
        }
        if (Regex("""(?i)\b(send money|wire|transfer|purchase|buy|delete|erase|wipe)\b""")
                .containsMatchIn(combined)
        ) {
            return SafetyVerdict.RequiresBiometric
        }
        return SafetyVerdict.Allowed
    }

    fun triggerEmergencyKill() {
        executionGuard.stop()
    }
}
