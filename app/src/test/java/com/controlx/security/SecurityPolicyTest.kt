package com.controlx.security

import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyTest {
    @Test
    fun protectedOperationsAreBlocked() {
        val result = SensitiveActionPolicy().evaluate("send money to Alex")

        assertTrue(result is SensitiveActionDecision.Blocked)
    }

    @Test
    fun killSwitchStopsExecution() {
        val guard = ExecutionGuard()
        guard.stop()

        assertTrue(guard.checkCanContinue() is GuardResult.Blocked)
    }

    @Test
    fun credentialTermsAreBlocked() {
        val result = SensitiveActionPolicy().evaluate("enter my password")

        assertTrue(result is SensitiveActionDecision.Blocked)
    }

    @Test
    fun ordinaryCommandsRemainAllowed() {
        val result = SensitiveActionPolicy().evaluate("open Chrome")

        assertTrue(result is SensitiveActionDecision.Allowed)
    }

    @Test
    fun internetSearchCommandsRemainAllowed() {
        val result = SensitiveActionPolicy().evaluate("search for Android 15 features")

        assertTrue(result is SensitiveActionDecision.Allowed)
    }

    @Test
    fun paymentGatewayActionsAreBlocked() {
        val result = SensitiveActionPolicy().evaluate("complete payment checkout")

        assertTrue(result is SensitiveActionDecision.Blocked)
    }

    @Test
    fun paymentAndBankingPackagesAreBlocked() {
        val guardian = SafetyGuardian()
        val action = com.controlx.domain.AgentAction(
            thought = "tap continue",
            action = com.controlx.domain.ActionType.CLICK,
            targetNodeId = "cx_1",
            coordinates = null,
            textInput = null,
            scrollDirection = null,
            systemCommand = null,
            expectedOutcome = "continue",
        )

        assertTrue(
            guardian.evaluateActionSafety(action, "net.one97.paytm") ==
                SafetyVerdict.Blocked,
        )
    }
}
