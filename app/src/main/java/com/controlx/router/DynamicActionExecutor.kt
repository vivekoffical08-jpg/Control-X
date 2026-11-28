package com.controlx.router

import android.content.Context
import android.media.AudioManager
import com.controlx.agent.DynamicIntentPlan
import com.controlx.agent.DynamicIntentType
import com.controlx.domain.FastPathResult
import com.controlx.domain.VolumeDirection
import com.controlx.security.PaymentSafeModeMonitor
import com.controlx.security.SensitiveActionDecision
import com.controlx.security.SensitiveActionPolicy

sealed interface DynamicExecutionResult {
    data class Executed(
        val message: String,
        val keepVisibleLonger: Boolean = false,
    ) : DynamicExecutionResult

    data class NeedsScreenAgent(
        val goal: String,
        val launchTarget: String? = null,
    ) : DynamicExecutionResult

    data class Error(val reason: String) : DynamicExecutionResult
}

class DynamicActionExecutor(
    private val context: Context,
    private val router: FastPathRouter,
    private val sensitiveActionPolicy: SensitiveActionPolicy = SensitiveActionPolicy(),
) {
    suspend fun execute(
        plan: DynamicIntentPlan,
        originalCommand: String,
    ): DynamicExecutionResult {
        if (PaymentSafeModeMonitor.shared.isActive) {
            return DynamicExecutionResult.Error("Payment Safe Mode paused automation.")
        }

        val combinedText = listOfNotNull(
            originalCommand,
            plan.contactQuery,
            plan.messageText,
            plan.emailSubject,
            plan.calendarTitle,
            plan.searchQuery,
            plan.agentGoal,
        ).joinToString(" ")

        if (sensitiveActionPolicy.evaluate(combinedText) is SensitiveActionDecision.Blocked) {
            return DynamicExecutionResult.Error(
                "Blocked protected financial, credential, or destructive action.",
            )
        }

        return when (plan.intent) {
            DynamicIntentType.OPEN_APP -> {
                val target = plan.appPackage?.takeIf { it.isNotBlank() }
                    ?: plan.appName?.takeIf { it.isNotBlank() }
                    ?: return DynamicExecutionResult.Error("No matching app was identified.")
                if (router.launchApp(target)) {
                    DynamicExecutionResult.Executed("Opened ${plan.appName ?: target}.")
                } else {
                    DynamicExecutionResult.Error("Could not launch '$target' on this device.")
                }
            }
            DynamicIntentType.DIAL_CONTACT ->
                toDynamicResult(router.dialQuery(plan.contactQuery.orEmpty()))
            DynamicIntentType.DRAFT_SMS ->
                toDynamicResult(router.draftSms(plan.contactQuery.orEmpty(), plan.messageText))
            DynamicIntentType.WHATSAPP_MESSAGE ->
                toDynamicResult(
                    AppActionDispatcher.whatsappMessage(
                        context = context,
                        recipientQuery = plan.contactQuery,
                        messageText = plan.messageText,
                    ),
                )
            DynamicIntentType.DRAFT_EMAIL ->
                toDynamicResult(
                    AppActionDispatcher.draftEmail(
                        context = context,
                        recipientEmail = plan.contactQuery,
                        subject = plan.emailSubject,
                        body = plan.messageText,
                    ),
                )
            DynamicIntentType.IN_APP_SEARCH ->
                toDynamicResult(
                    AppActionDispatcher.searchInApp(
                        context = context,
                        appQuery = plan.appPackage ?: plan.appName.orEmpty(),
                        searchQuery = plan.searchQuery.orEmpty(),
                    ),
                )
            DynamicIntentType.MAPS_ACTION ->
                toDynamicResult(
                    AppActionDispatcher.mapsAction(
                        context = context,
                        query = plan.searchQuery.orEmpty(),
                        navigate = plan.navigate == true,
                    ),
                )
            DynamicIntentType.OPEN_CAMERA ->
                toDynamicResult(
                    AppActionDispatcher.openCamera(
                        context = context,
                        video = plan.cameraVideo == true,
                    ),
                )
            DynamicIntentType.CREATE_CALENDAR_EVENT ->
                toDynamicResult(
                    AppActionDispatcher.createCalendarEvent(
                        context = context,
                        title = plan.calendarTitle ?: plan.thought,
                        description = plan.messageText,
                    ),
                )
            DynamicIntentType.PLAY_MEDIA -> {
                val base = plan.mediaQuery?.trim().orEmpty()
                if (base.isEmpty()) {
                    DynamicExecutionResult.Error("No song or video query specified.")
                } else {
                    val suffix = when (plan.mediaPlatform?.lowercase()) {
                        "spotify" -> " on spotify"
                        "youtube" -> " on youtube"
                        else -> ""
                    }
                    toDynamicResult(router.playMediaQuery("$base$suffix"))
                }
            }
            DynamicIntentType.WEB_SEARCH ->
                toDynamicResult(router.searchWebQuery(plan.searchQuery.orEmpty()))
            DynamicIntentType.SET_ALARM ->
                toDynamicResult(
                    router.openAlarmAt(
                        hour = plan.alarmHour ?: 7,
                        minute = plan.alarmMinute ?: 0,
                    ),
                )
            DynamicIntentType.SET_TIMER ->
                toDynamicResult(
                    router.openTimerSeconds((plan.timerSeconds ?: 0).toLong()),
                )
            DynamicIntentType.TOGGLE_TORCH ->
                toDynamicResult(router.setTorch(plan.torchEnabled ?: true))
            DynamicIntentType.SET_VOLUME -> {
                val stream = when (plan.volumeStream?.lowercase()) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }
                val percent = plan.volumePercent
                if (percent != null) {
                    toDynamicResult(router.setExactVolume(stream, percent))
                } else {
                    val direction = if (plan.volumeDirection.equals("DOWN", ignoreCase = true)) {
                        VolumeDirection.DOWN
                    } else {
                        VolumeDirection.UP
                    }
                    router.adjustVolume(stream, direction)
                    DynamicExecutionResult.Executed("Volume adjusted.")
                }
            }
            DynamicIntentType.OPEN_SETTINGS ->
                toDynamicResult(router.openSettingsTarget(plan.settingsTarget.orEmpty()))
            DynamicIntentType.LOCK_SCREEN ->
                toDynamicResult(router.lockDeviceScreen())
            DynamicIntentType.ANSWER_TEXT -> {
                val answer = plan.answerText?.trim()?.takeIf { it.isNotEmpty() }
                    ?: plan.thought.trim().ifEmpty { "Done." }
                DynamicExecutionResult.Executed(answer, keepVisibleLonger = true)
            }
            DynamicIntentType.NEEDS_SCREEN_AGENT ->
                DynamicExecutionResult.NeedsScreenAgent(
                    goal = plan.agentGoal?.trim()?.takeIf { it.isNotEmpty() } ?: originalCommand,
                    launchTarget = plan.appPackage?.takeIf { it.isNotBlank() }
                        ?: plan.appName?.takeIf { it.isNotBlank() },
                )
            DynamicIntentType.BLOCKED ->
                DynamicExecutionResult.Error(
                    plan.thought.ifBlank { "This action is blocked for safety." },
                )
        }
    }

    private fun toDynamicResult(result: FastPathResult): DynamicExecutionResult =
        when (result) {
            is FastPathResult.Executed -> DynamicExecutionResult.Executed(result.message)
            is FastPathResult.Error -> DynamicExecutionResult.Error(result.reason)
            FastPathResult.Unhandled -> DynamicExecutionResult.Error("Unable to handle command.")
        }
}
