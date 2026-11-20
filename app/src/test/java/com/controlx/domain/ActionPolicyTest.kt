package com.controlx.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionPolicyTest {
    private val policy = ActionPolicy()

    @Test
    fun agentProposalAlwaysRequiresConfirmation() {
        val result = policy.evaluate(
            ActionRequest(
                actionId = ActionId.HOME,
                source = RequestSource.AGENT_PROPOSAL,
            ),
        )

        assertEquals(ResultStatus.REQUIRES_CONFIRMATION, result.status)
    }

    @Test
    fun missingRequiredArgumentIsRejected() {
        val result = policy.evaluate(
            ActionRequest(
                actionId = ActionId.OPEN_APP,
                source = RequestSource.USER_TEXT,
            ),
        )

        assertEquals(ResultStatus.REJECTED, result.status)
    }

    @Test
    fun simpleNavigationActionIsAllowed() {
        val result = policy.evaluate(
            ActionRequest(
                actionId = ActionId.HOME,
                source = RequestSource.USER_TAP,
            ),
        )

        assertEquals(ResultStatus.ALLOWED, result.status)
    }

    @Test
    fun volumeOutsideRangeIsRejected() {
        val result = policy.evaluate(
            ActionRequest(
                actionId = ActionId.SET_MEDIA_VOLUME,
                arguments = mapOf("volumePercent" to "101"),
                source = RequestSource.USER_TEXT,
            ),
        )

        assertEquals(ResultStatus.REJECTED, result.status)
    }
}
