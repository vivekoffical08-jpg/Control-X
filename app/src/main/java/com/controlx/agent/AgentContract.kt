package com.controlx.agent

import com.controlx.accessibility.AccessibilitySnapshot
import com.controlx.domain.ActionId

data class AgentProposal(
    val actionId: ActionId,
    val nodeId: String? = null,
    val arguments: Map<String, String> = emptyMap(),
)

sealed interface AgentValidation {
    data class Accepted(val proposal: AgentProposal) : AgentValidation
    data class Rejected(val reason: String) : AgentValidation
}

class AgentContract {
    fun validate(
        proposal: AgentProposal,
        snapshot: AccessibilitySnapshot,
    ): AgentValidation {
        val nodeId = proposal.nodeId ?: return AgentValidation.Accepted(proposal)
        return if (snapshot.nodes.any { it.nodeId == nodeId }) {
            AgentValidation.Accepted(proposal)
        } else {
            AgentValidation.Rejected("The proposed node is not present in the current screen snapshot.")
        }
    }
}
