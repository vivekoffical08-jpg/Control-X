package com.controlx.agent

import com.controlx.domain.ActionRecord
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot
import com.controlx.domain.ActionType
import com.controlx.security.SafetyGuardian
import com.controlx.security.SafetyVerdict
import com.controlx.security.ExecutionGuard
import com.controlx.security.GuardResult
import com.controlx.security.PaymentSafeModeMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class ReActEngine(
    private val groqApiClient: GroqApiClient,
) {
    fun interface ScreenProvider {
        fun capture(): ScreenSnapshot?
    }

    fun interface ActionExecutor {
        suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot): Boolean
    }

    sealed interface LoopResult {
        data object Completed : LoopResult
        data class Failed(val reason: String) : LoopResult
        data class Blocked(val reason: String) : LoopResult
        data object LimitReached : LoopResult
    }

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
        SnapshotActionValidator.fingerprint(snapshot)

    fun maxStepsReached(history: List<ActionRecord>): Boolean = history.size >= 10

    suspend fun runGoal(
        goal: String,
        screenProvider: ScreenProvider,
        actionExecutor: ActionExecutor,
        safetyGuardian: SafetyGuardian = SafetyGuardian(),
        settleMillis: Long = 600,
        onStep: (String) -> Unit = {},
        actionApprover: suspend (AgentAction) -> Boolean = { false },
    ): LoopResult {
        val history = mutableListOf<ActionRecord>()
        var previousFingerprint: String? = null

        for (step in 0 until 10) {
            if (PaymentSafeModeMonitor.shared.isActive) {
                return LoopResult.Blocked("Payment Safe Mode stopped automation.")
            }
            if (ExecutionGuard.shared.checkCanContinue() is GuardResult.Blocked) {
                return LoopResult.Blocked("Execution stopped by the user.")
            }
            val snapshot = screenProvider.capture()
                ?: return LoopResult.Failed("Screen snapshot is unavailable.")
            val fingerprint = screenFingerprint(snapshot)

            val action = try {
                planNextStep(goal, snapshot, history)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return LoopResult.Failed(
                    error.message ?: "The agent could not decide the next action.",
                )
            }

            val verdict = safetyGuardian.evaluateActionSafety(
                action,
                snapshot.packageName,
            )
            when (verdict) {
                SafetyVerdict.Blocked ->
                    return LoopResult.Blocked("A sensitive or protected action was blocked.")
                SafetyVerdict.RequiresBiometric ->
                    return LoopResult.Blocked(
                        "Financial or destructive actions require explicit biometric approval.",
                    )
                SafetyVerdict.Allowed -> Unit
            }

            if (action.action == ActionType.FINISH) {
                onStep(action.thought)
                return LoopResult.Completed
            }
            if (action.action == ActionType.FAIL) {
                return LoopResult.Failed(action.thought.ifBlank {
                    "The agent could not complete the task."
                })
            }

            if (detectLoop(history, snapshot)) {
                onStep("Repeated action and screen detected. Ending safely.")
                return LoopResult.Blocked("The agent stopped to avoid a repeated action loop.")
            }

            val groundingError = SnapshotActionValidator.rejectionReason(action, snapshot)
            if (groundingError != null) {
                return LoopResult.Blocked(groundingError)
            }

            onStep("Step ${step + 1}: ${action.thought}")
            if (action.action != ActionType.FINISH &&
                action.action != ActionType.FAIL &&
                !actionApprover(action)
            ) {
                return LoopResult.Blocked("The proposed action was not approved.")
            }
            if (PaymentSafeModeMonitor.shared.isActive ||
                ExecutionGuard.shared.checkCanContinue() is GuardResult.Blocked
            ) {
                return LoopResult.Blocked("Payment Safe Mode stopped automation.")
            }
            val currentSnapshot = screenProvider.capture()
                ?: return LoopResult.Blocked("The screen snapshot became unavailable.")
            if (screenFingerprint(currentSnapshot) != fingerprint) {
                return LoopResult.Blocked(
                    "The screen changed after approval. Re-run the task against the current screen.",
                )
            }
            val executed = actionExecutor.execute(action, snapshot)
            if (!executed) {
                return LoopResult.Failed(
                    "The action could not be dispatched against the approved screen snapshot.",
                )
            }

            history += ActionRecord(
                action = action,
                snapshotFingerprint = fingerprint,
                timestampMillis = System.currentTimeMillis(),
            )

            delay(settleMillis)
            val settledSnapshot = screenProvider.capture()
            val settledFingerprint = settledSnapshot?.let(::screenFingerprint)

            if (settledFingerprint != null &&
                settledFingerprint == fingerprint &&
                previousFingerprint == fingerprint &&
                history.size >= 2
            ) {
                onStep("The screen did not change after repeated actions. Ending safely.")
                return LoopResult.Blocked(
                    "The screen did not change after repeated actions; no automatic recovery gesture was sent.",
                )
            }
            previousFingerprint = settledFingerprint
        }
        return LoopResult.LimitReached
    }
}
