package com.controlx.agent

import com.controlx.accessibility.AccessibilityNodeSnapshot
import com.controlx.accessibility.AccessibilitySnapshot
import com.controlx.domain.ActionId
import org.junit.Assert.assertTrue
import org.junit.Test
import com.controlx.domain.ActionRecord
import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot
import com.controlx.agent.ReActEngine
import android.graphics.Rect
import com.controlx.domain.CleanNode

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

    @Test
    fun reActStopsAtTenRecordedSteps() {
        val history = (1..10).map { index ->
            ActionRecord(
                action = AgentAction(
                    thought = "step",
                    action = ActionType.CLICK,
                    targetNodeId = null,
                    coordinates = listOf(10f, 10f),
                    textInput = null,
                    scrollDirection = null,
                    systemCommand = null,
                    expectedOutcome = "continue",
                ),
                snapshotFingerprint = "snapshot-$index",
                timestampMillis = index.toLong(),
            )
        }
        assertTrue(
            ReActEngine(GroqApiClient(apiKeyProvider = { "test" }))
                .maxStepsReached(history),
        )
    }

    @Test
    fun unchangedActionAndScreenIsDetected() {
        val action = AgentAction(
            thought = "press continue",
            action = ActionType.CLICK,
            targetNodeId = "cx_1",
            coordinates = null,
            textInput = null,
            scrollDirection = null,
            systemCommand = null,
            expectedOutcome = "continue",
        )
        val snapshot = ScreenSnapshot(
            packageName = "com.example",
            timestamp = 1L,
            nodes = listOf(
                CleanNode(
                    id = "cx_1",
                    className = "Button",
                    text = "Continue",
                    contentDescription = null,
                    viewIdResourceName = null,
                    bounds = Rect(0, 0, 100, 100),
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isSensitive = false,
                ),
            ),
        )
        val engine = ReActEngine(
            GroqApiClient(apiKeyProvider = { "test" }),
        )
        val fingerprint = engine.screenFingerprint(snapshot)
        val history = listOf(
            ActionRecord(action, fingerprint, 1L),
            ActionRecord(action, fingerprint, 2L),
        )
        assertTrue(engine.detectLoop(history, snapshot))
    }
}
