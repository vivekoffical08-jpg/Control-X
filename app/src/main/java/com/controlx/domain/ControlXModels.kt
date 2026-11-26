package com.controlx.domain

import android.graphics.Rect

enum class ActionType {
    CLICK,
    TYPE,
    SCROLL,
    SYSTEM_ACTION,
    FINISH,
    FAIL,
}

data class CleanNode(
    val id: String,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isSensitive: Boolean,
)

data class ScreenSnapshot(
    val packageName: String,
    val timestamp: Long,
    val nodes: List<CleanNode>,
    val activityName: String? = null,
)

data class AgentAction(
    val thought: String,
    val action: ActionType,
    val targetNodeId: String?,
    val coordinates: List<Float>?,
    val textInput: String?,
    val scrollDirection: String?,
    val systemCommand: String?,
    val expectedOutcome: String,
)

sealed interface FastPathResult {
    data class Executed(val message: String, val latencyMs: Long) : FastPathResult
    data object Unhandled : FastPathResult
    data class Error(val reason: String) : FastPathResult
}

enum class VolumeDirection {
    UP,
    DOWN,
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}
