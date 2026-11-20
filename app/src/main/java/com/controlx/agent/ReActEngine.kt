package com.controlx.agent

import com.controlx.domain.ActionRecord
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot

class ReActEngine(
    private val groqApiClient: GroqApiClient,
) {
    suspend fun planNextStep(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<ActionRecord>,
    ): AgentAction =
        groqApiClient.planNextStep(goal, snapshot, history)

    fun detectLoop(history: List<ActionRecord>, snapshot: ScreenSnapshot): Boolean {
        if (history.size < 2) return false
        val latest = history.takeLast(2)
        val sameAction = latest[0].action == latest[1].action
        val sameScreen = latest[0].snapshotFingerprint == latest[1].snapshotFingerprint
        val currentSame = latest.last().snapshotFingerprint ==
            screenFingerprint(snapshot)
        return sameAction && sameScreen && currentSame
    }

    fun screenFingerprint(snapshot: ScreenSnapshot): String =
        snapshot.nodes.joinToString("|") { node ->
            "${node.id}:${node.text}:${node.contentDescription}:${node.bounds.flattenToString()}"
        }

    fun maxStepsReached(history: List<ActionRecord>): Boolean = history.size >= 10
}
