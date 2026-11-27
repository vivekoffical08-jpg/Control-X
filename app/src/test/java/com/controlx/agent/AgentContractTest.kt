package com.controlx.agent

import android.graphics.Rect
import com.controlx.accessibility.AccessibilityNodeSnapshot
import com.controlx.accessibility.AccessibilitySnapshot
import com.controlx.core.AgentBrainPort
import com.controlx.domain.ActionId
import com.controlx.domain.ActionRecord
import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.domain.CleanNode
import com.controlx.domain.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val dummyBrain = object : AgentBrainPort {
        override suspend fun planNextStep(
            goal: String,
            snapshot: ScreenSnapshot,
            history: List<ActionRecord>,
        ): AgentAction =
            AgentAction(
                thought = "done",
                action = ActionType.FINISH,
                targetNodeId = null,
                coordinates = null,
                textInput = null,
                scrollDirection = null,
                systemCommand = null,
                expectedOutcome = "complete",
            )
    }

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
        assertTrue(ReActEngine(dummyBrain).maxStepsReached(history))
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
        val targetSnapshot = ScreenSnapshot(
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
        val engine = ReActEngine(dummyBrain)
        val fingerprint = engine.screenFingerprint(targetSnapshot)
        val history = listOf(
            ActionRecord(action, fingerprint, 1L),
            ActionRecord(action, fingerprint, 2L),
        )
        assertTrue(engine.detectLoop(history, targetSnapshot))
    }

    @Test
    fun clickRequiresClickableGroundedNodeAndInBoundsCoordinates() {
        val targetSnapshot = ScreenSnapshot(
            packageName = "com.example",
            timestamp = 1L,
            nodes = listOf(
                CleanNode(
                    id = "cx_1",
                    className = "Button",
                    text = "Continue",
                    contentDescription = null,
                    viewIdResourceName = null,
                    bounds = Rect().apply {
                        left = 10
                        top = 20
                        right = 110
                        bottom = 120
                    },
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isSensitive = false,
                ),
            ),
        )
        val groundedClick = AgentAction(
            thought = "Click Continue",
            action = ActionType.CLICK,
            targetNodeId = "cx_1",
            coordinates = listOf(50f, 60f),
            textInput = null,
            scrollDirection = null,
            systemCommand = null,
            expectedOutcome = "Continue",
        )
        val offScreenClick = groundedClick.copy(coordinates = listOf(500f, 600f))

        assertNull(SnapshotActionValidator.rejectionReason(groundedClick, targetSnapshot))
        assertNotNull(SnapshotActionValidator.rejectionReason(offScreenClick, targetSnapshot))
        assertNotNull(
            SnapshotActionValidator.rejectionReason(
                groundedClick.copy(targetNodeId = null),
                targetSnapshot,
            ),
        )
    }

    @Test
    fun typeRequiresEditableNonSensitiveNode() {
        val targetSnapshot = ScreenSnapshot(
            packageName = "com.example",
            timestamp = 1L,
            nodes = listOf(
                CleanNode(
                    id = "cx_1",
                    className = "EditText",
                    text = null,
                    contentDescription = null,
                    viewIdResourceName = null,
                    bounds = Rect(0, 0, 200, 80),
                    isClickable = false,
                    isEditable = true,
                    isScrollable = false,
                    isSensitive = false,
                ),
                CleanNode(
                    id = "cx_2",
                    className = "EditText",
                    text = "[REDACTED]",
                    contentDescription = null,
                    viewIdResourceName = null,
                    bounds = Rect(0, 100, 200, 180),
                    isClickable = false,
                    isEditable = true,
                    isScrollable = false,
                    isSensitive = true,
                ),
            ),
        )
        val typeAction = AgentAction(
            thought = "Enter the search",
            action = ActionType.TYPE,
            targetNodeId = "cx_1",
            coordinates = null,
            textInput = "weather",
            scrollDirection = null,
            systemCommand = null,
            expectedOutcome = "Search",
        )

        assertNull(SnapshotActionValidator.rejectionReason(typeAction, targetSnapshot))
        assertNotNull(
            SnapshotActionValidator.rejectionReason(
                typeAction.copy(targetNodeId = "cx_2"),
                targetSnapshot,
            ),
        )
    }

    @Test
    fun backendClientParsesDynamicIntentPayload() {
        val client = BackendAiClient()
        val json = """
            {"intent":"DRAFT_SMS","thought":"Draft text message","contactQuery":"Mom","messageText":"Running 10 minutes late"}
        """.trimIndent()

        val plan = client.parseIntentJson(json)

        assertEquals(DynamicIntentType.DRAFT_SMS, plan.intent)
        assertEquals("Mom", plan.contactQuery)
        assertEquals("Running 10 minutes late", plan.messageText)
    }

    @Test
    fun backendClientParsesReActActionPayload() {
        val client = BackendAiClient()
        val json = """
            {"thought":"Tap Continue","action":"CLICK","targetNodeId":"cx_1","coordinates":null,"textInput":null,"scrollDirection":null,"systemCommand":null,"expectedOutcome":"Moves to next screen"}
        """.trimIndent()

        val action = client.parseAgentActionJson(json)

        assertEquals(ActionType.CLICK, action.action)
        assertEquals("cx_1", action.targetNodeId)
        assertEquals("Tap Continue", action.thought)
    }

    @Test(expected = IllegalArgumentException::class)
    fun backendClientRejectsUnknownDynamicIntent() {
        val client = BackendAiClient()
        client.parseIntentJson("""{"intent":"SHELL_EXEC","thought":"invalid"}""")
    }
}
