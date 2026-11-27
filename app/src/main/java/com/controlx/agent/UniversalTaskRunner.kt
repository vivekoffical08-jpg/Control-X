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

        val openMatch = Regex("""(?i)^(?:open|launch)\s+(.+)$""").find(goal.trim())
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            val launched = router.launchApp(appName)
            if (!launched) {
                return@withContext TaskResult.Error(
                    "No installed app matched '$appName'.",
                )
            }
            delay(700)
        }

        val reAct = ReActEngine(backendAiClient)
        val executor = AgentNodeExecutor(service)

        val result = reAct.runGoal(
            goal = goal,
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
}
