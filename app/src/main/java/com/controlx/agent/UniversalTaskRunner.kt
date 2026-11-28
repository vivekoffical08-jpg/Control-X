package com.controlx.agent

import android.content.Context
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.domain.AgentAction
import com.controlx.platform.CommandApiClient
import com.controlx.router.FastPathRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class UniversalTaskRunner(
    context: Context,
    private val commandApiClient: CommandApiClient = CommandApiClient(context),
) {
    private val appContext = context.applicationContext
    private val router = FastPathRouter(appContext)
    private val backendAiClient = BackendAiClient(commandApiClient)

    suspend fun run(
        goal: String,
        launchTarget: String? = null,
        actionApprover: suspend (AgentAction) -> Boolean = { false },
    ): TaskResult = withContext(Dispatchers.Default) {
        val service = activeService() ?: return@withContext TaskResult.Error(
            "Enable optional Screen Automation in Control X Settings to interact inside apps (keep OFF for Google Pay).",
        )

        if (!backendAiClient.isConfigured()) {
            return@withContext TaskResult.Error(
                "Connect a Control X backend server to use AI screen automation.",
            )
        }

        val parsed = splitAppAndGoal(goal)
        val appToLaunch = launchTarget?.takeIf { it.isNotBlank() } ?: parsed.first
        val resolvedGoal = parsed.second

        if (!appToLaunch.isNullOrBlank()) {
            val launched = router.launchApp(appToLaunch)
            if (!launched) {
                return@withContext TaskResult.Error(
                    "No installed app matched '$appToLaunch'.",
                )
            }
            delay(1000)
        }

        val reAct = ReActEngine(backendAiClient)
        val executor = AgentNodeExecutor(service)

        val result = reAct.runGoal(
            goal = resolvedGoal,
            screenProvider = ReActEngine.ScreenProvider {
                service.captureScreenSnapshot()
            },
            actionExecutor = executor,
            settleMillis = 600,
            onStep = { },
            actionApprover = actionApprover,
        )

        when (result) {
            ReActEngine.LoopResult.Completed ->
                TaskResult.Executed("The requested task completed.")
            is ReActEngine.LoopResult.Blocked ->
                TaskResult.Error(result.reason)
            is ReActEngine.LoopResult.Failed ->
                TaskResult.Error(result.reason)
            ReActEngine.LoopResult.LimitReached ->
                TaskResult.Error("The task reached the 10-step safety limit.")
        }
    }

    private fun activeService(): ControlAccessibilityService? =
        ControlAccessibilityService.activeInstance()

    sealed interface TaskResult {
        data class Executed(val message: String) : TaskResult
        data class Error(val reason: String) : TaskResult
    }

    companion object {
        private val COMPOUND_OPEN_REGEX =
            Regex("""(?i)^(?:open|launch)\s+(.+?)\s+(?:and|then|,)\s+(.+)$""")
        private val SIMPLE_OPEN_REGEX =
            Regex("""(?i)^(?:open|launch)\s+(.+)$""")

        internal fun splitAppAndGoal(goal: String): Pair<String?, String> {
            val trimmed = goal.trim()
            val compound = COMPOUND_OPEN_REGEX.find(trimmed)
            if (compound != null) {
                return compound.groupValues[1].trim() to compound.groupValues[2].trim()
            }
            val simple = SIMPLE_OPEN_REGEX.find(trimmed)
            if (simple != null) {
                val app = simple.groupValues[1].trim()
                if (!FastPathRouter.isCompoundTaskClause(app)) {
                    return app to trimmed
                }
            }
            return null to trimmed
        }
    }
}
