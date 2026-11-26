package com.controlx.agent

import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot

object SnapshotActionValidator {
    fun rejectionReason(action: AgentAction, snapshot: ScreenSnapshot): String? {
        val target = action.targetNodeId?.let { targetId ->
            snapshot.nodes.firstOrNull { it.id == targetId }
                ?: return "The proposed node is not present in the current screen snapshot."
        }

        if (action.coordinates != null && action.action != ActionType.CLICK) {
            return "Coordinates are supported only for grounded clicks."
        }

        return when (action.action) {
            ActionType.CLICK -> {
                if (target == null || !target.isClickable || target.isSensitive) {
                    "A click must target a visible, clickable, non-sensitive screen node."
                } else if (action.coordinates != null &&
                    (action.coordinates.size != 2 ||
                        action.coordinates.any { !it.isFinite() } ||
                        action.coordinates[0] < target.bounds.left ||
                        action.coordinates[0] >= target.bounds.right ||
                        action.coordinates[1] < target.bounds.top ||
                        action.coordinates[1] >= target.bounds.bottom)
                ) {
                    "Click coordinates must fall inside the bounds of their target node."
                } else {
                    null
                }
            }
            ActionType.TYPE ->
                if (target == null || !target.isEditable || target.isSensitive) {
                    "Text entry must target a visible, editable, non-sensitive screen node."
                } else {
                    null
                }
            ActionType.SCROLL ->
                if (action.targetNodeId != null && (target == null || !target.isScrollable)) {
                    "A targeted scroll must reference a visible scrollable screen node."
                } else {
                    null
                }
            ActionType.SYSTEM_ACTION, ActionType.FINISH, ActionType.FAIL -> null
        }
    }

    fun fingerprint(snapshot: ScreenSnapshot): String =
        buildString {
            append(snapshot.packageName)
            append(':')
            append(snapshot.activityName)
            append(':')
            append(
                snapshot.nodes.joinToString("|") { node ->
                    "${node.id}:${node.className}:${node.text}:${node.contentDescription}:${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}"
                },
            )
        }
}
