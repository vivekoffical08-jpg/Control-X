package com.controlx.core

import com.controlx.domain.ActionRecord
import com.controlx.domain.AgentAction
import com.controlx.domain.FastPathResult
import com.controlx.domain.ScreenSnapshot

interface VoiceInputPort {
    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    )

    fun stopListening()
    fun destroy()
}

interface FastPathPort {
    suspend fun route(command: String): FastPathResult
}

interface ScreenPerceptionPort {
    fun capture(): ScreenSnapshot?
}

interface GestureExecutorPort {
    suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot): Boolean
}

interface AgentBrainPort {
    suspend fun planNextStep(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<ActionRecord>,
    ): AgentAction
}

interface SafetyGuardianPort {
    fun evaluate(action: AgentAction, currentPackage: String): CoreSafetyVerdict
}

sealed interface CoreSafetyVerdict {
    data object Allowed : CoreSafetyVerdict
    data class Blocked(val reason: String) : CoreSafetyVerdict
    data class RequiresExplicitApproval(val reason: String) : CoreSafetyVerdict
}

data class PlatformCapability(
    val id: String,
    val granted: Boolean,
    val platformDetails: Map<String, String> = emptyMap(),
)
