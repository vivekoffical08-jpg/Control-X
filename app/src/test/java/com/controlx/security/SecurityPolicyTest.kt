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

    @Test
    fun paymentSafeModeTracksSensitiveForegroundPackagesAndDoesNotResumeOnSystemUi() {
        val monitor = PaymentSafeModeMonitor()

        monitor.onForegroundPackageChanged("net.one97.paytm")
        assertTrue(monitor.isActive)

        monitor.onForegroundPackageChanged("com.android.systemui")
        assertTrue(monitor.isActive)

        monitor.onForegroundPackageChanged("com.android.launcher3")
        assertTrue(!monitor.isActive)
    }

    @Test
    fun paymentSafeModeRecognizesBankingPackages() {
        val monitor = PaymentSafeModeMonitor()

        monitor.onForegroundPackageChanged("com.example.mobilebanking")

        assertTrue(monitor.isActive)
    }
}
