package com.controlx.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExecutionGuard {
    private val _stopped = MutableStateFlow(false)
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    fun stop() {
        _stopped.value = true
    }

    fun reset() {
        _stopped.value = false
    }

    fun checkCanContinue(): GuardResult =
        if (_stopped.value) GuardResult.Blocked("Execution stopped by the user.")
        else GuardResult.Allowed
}

sealed interface GuardResult {
    data object Allowed : GuardResult
    data class Blocked(val reason: String) : GuardResult
}
