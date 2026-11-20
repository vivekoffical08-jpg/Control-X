package com.controlx.agent

import com.controlx.accessibility.AccessibilityNodeSnapshot
import com.controlx.accessibility.AccessibilitySnapshot
import com.controlx.domain.ActionId
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContractTest {
    private val snapshot = AccessibilitySnapshot(
        packageName = "com.example",
        nodes = listOf(
            AccessibilityNodeSnapshot(
                nodeId = "0.1",
                className = "android.widget.Button",
                text = "Continue",
                contentDescription = null,
                boundsLeft = 0,
                boundsTop = 0,
                boundsRight = 100,
                boundsBottom = 100,
                isClickable = true,
                isEditable = false,
                isScrollable = false,
            ),
        ),
    )

    @Test
    fun rejectsNodeNotInCurrentSnapshot() {
        val result = AgentContract().validate(
            AgentProposal(ActionId.HOME, nodeId = "0.9"),
            snapshot,
        )

        assertTrue(result is AgentValidation.Rejected)
    }
}
