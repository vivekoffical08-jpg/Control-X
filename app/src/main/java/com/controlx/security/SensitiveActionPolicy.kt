package com.controlx.security

class SensitiveActionPolicy {
    private val blockedTerms = setOf(
        "password",
        "pin",
        "otp",
        "one-time code",
        "send money",
        "transfer",
        "purchase",
        "buy",
        "delete",
        "erase",
        "pay now",
        "make payment",
        "payment",
        "checkout",
        "upi",
        "card number",
    )

    fun evaluate(command: String): SensitiveActionDecision {
        val normalized = command.lowercase()
        val matchedTerm = blockedTerms.firstOrNull { normalized.contains(it) }
        return if (matchedTerm == null) {
            SensitiveActionDecision.Allowed
        } else {
            SensitiveActionDecision.Blocked(
                "The command contains a protected operation: $matchedTerm.",
            )
        }
    }
}

sealed interface SensitiveActionDecision {
    data object Allowed : SensitiveActionDecision
    data class Blocked(val reason: String) : SensitiveActionDecision
}
