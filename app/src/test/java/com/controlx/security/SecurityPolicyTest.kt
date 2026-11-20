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
}
