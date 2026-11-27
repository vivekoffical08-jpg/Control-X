package com.controlx.agent

import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.domain.FastPathResult
import com.controlx.router.FastPathRouter
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.controlx.domain.AgentAction

class UniversalTaskRunner(
    context: Context,
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String = { NvidiaNimApiClient.DEFAULT_MODEL },
) {
    private val appContext = context.applicationContext
    private val router = FastPathRouter(appContext)

    suspend fun run(
        goal: String,
        actionApprover: suspend (AgentAction) -> Boolean = { false },
    ): TaskResult = withContext(Dispatchers.Default) {
        val service = activeService() ?: return@withContext TaskResult.Error(
            "Enable Control X Accessibility before using screen automation.",
        )

        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext TaskResult.Error(
                "AI is not configured yet. Fast-Path commands remain available.",
            )
        }

        val openMatch = Regex("""(?i)^open\s+(.+)$""").find(goal.trim())
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

        val nimClient = NvidiaNimApiClient(apiKeyProvider, modelProvider)
        val reAct = ReActEngine(nimClient)
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
