package com.controlx.overlay

import android.content.Context
import android.provider.Settings
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.agent.BackendAiClient
import com.controlx.agent.DeviceCapabilities
import com.controlx.agent.UniversalTaskRunner
import com.controlx.agent.UniversalTaskRunner.TaskResult
import com.controlx.domain.AgentAction
import com.controlx.domain.FastPathResult
import com.controlx.input.VoiceInputManager
import com.controlx.platform.AppCatalogManager
import com.controlx.platform.CommandApiClient
import com.controlx.platform.PermissionRepository
import com.controlx.router.DynamicActionExecutor
import com.controlx.router.DynamicExecutionResult
import com.controlx.router.FastPathRouter
import com.controlx.security.ExecutionGuard
import com.controlx.security.PaymentSafeModeMonitor
import com.controlx.security.SensitiveActionDecision
import com.controlx.security.SensitiveActionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayController(
    context: Context,
    private val executionGuard: ExecutionGuard = ExecutionGuard.shared,
) {
    private val appContext = context.applicationContext
    private val manager = AssistantOverlayManager(appContext, executionGuard)
    private val voiceInput by lazy { VoiceInputManager(appContext) }
    private val router = FastPathRouter(appContext)
    private val dynamicExecutor = DynamicActionExecutor(router)
    private val commandApiClient by lazy { CommandApiClient(appContext) }
    private val backendAiClient by lazy { BackendAiClient(commandApiClient) }
    private val taskRunner by lazy { UniversalTaskRunner(appContext, commandApiClient) }
    private val permissionRepository by lazy { PermissionRepository(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningForCompletion = false
    private val sensitiveActionPolicy = SensitiveActionPolicy()
    private var pendingAgentConfirmation: CompletableDeferred<Boolean>? = null
    private var commandJob: Job? = null

    init {
        manager.onDismissRequested = { dismiss() }
        manager.onEmergencyAbort = { emergencyAbort() }
        manager.onTranscriptSubmit = { command ->
            submitCommand(command)
        }
        manager.onAgentActionApproval = { approved ->
            pendingAgentConfirmation?.complete(approved)
        }

        scope.launch {
            executionGuard.stopped.collectLatest { stopped ->
                if (stopped) dismissImmediate()
            }
        }
        scope.launch {
            PaymentSafeModeMonitor.shared.state.collectLatest { state ->
                if (state.active) pauseForPayment()
            }
        }
    }

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext) ||
            PaymentSafeModeMonitor.shared.isActive
        ) return false
        executionGuard.reset()
        if (!manager.show()) return false
        startListening()
        return true
    }

    fun hide() {
        dismiss()
    }

    fun emergencyAbort() {
        executionGuard.stop()
        dismissImmediate()
    }

    fun destroy() {
        listeningForCompletion = false
        voiceInput.destroy()
        manager.hide()
        scope.cancel()
    }

    private fun startListening() {
        listeningForCompletion = true
        manager.setState(OverlayState.LISTENING)
        manager.updateTranscript("Listening...", 0)
        voiceInput.startListening(
            onPartial = { text ->
                scope.launch(Dispatchers.Main.immediate) {
                    manager.updateTranscript(text, 35)
                }
            },
            onFinal = { text ->
                scope.launch(Dispatchers.Main.immediate) {
                    manager.updateTranscript(text, 35)
                    submitCommand(text)
                }
            },
            onError = { error ->
                scope.launch(Dispatchers.Main.immediate) {
                    listeningForCompletion = false
                    manager.setState(OverlayState.PARSING)
                    manager.updateTranscript(
                        "Speech recognition unavailable (code $error).",
                        0,
                    )
                }
            },
        )
    }

    private fun submitCommand(command: String) {
        if (PaymentSafeModeMonitor.shared.isActive) {
            pauseForPayment()
            return
        }
        commandJob?.cancel()
        commandJob = scope.launch { processCommand(command) }
    }

    private suspend fun processCommand(command: String) {
        if (PaymentSafeModeMonitor.shared.isActive) {
            pauseForPayment()
            return
        }
        when (val sensitive = sensitiveActionPolicy.evaluate(command)) {
            is SensitiveActionDecision.Blocked -> {
                listeningForCompletion = false
                voiceInput.stopListening()
                manager.setState(OverlayState.PARSING)
                manager.updateTranscript("✕ ${sensitive.reason}", 0)
                return
            }
            SensitiveActionDecision.Allowed -> Unit
        }
        listeningForCompletion = false
        voiceInput.stopListening()
        manager.setState(OverlayState.PARSING)
        manager.updateTranscript("Processing: $command", 0)

        val started = System.nanoTime()
        val result = withContext(Dispatchers.Default) {
            router.route(command)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000

        logCommandAsync(command, result, elapsed)

        if (executionGuard.checkCanContinue().let { it is com.controlx.security.GuardResult.Blocked }) {
            dismissImmediate()
            return
        }

        when (result) {
            is FastPathResult.Executed -> {
                manager.setState(OverlayState.EXECUTING)
                manager.updateTranscript("✓ ${result.message}", elapsed)
                if (Regex("""(?i)^play\b""").containsMatchIn(command) &&
                    ControlAccessibilityService.isActive() &&
                    backendAiClient.isConfigured()
                ) {
                    runYouTubeSelectionIfAccessible(command, elapsed)
                }
                delay(800)
                dismiss()
            }
            FastPathResult.Unhandled -> handleWithBackendOrAgent(command, started)
            is FastPathResult.Error -> {
                manager.setState(OverlayState.PARSING)
                manager.updateTranscript("✕ ${result.reason}", elapsed)
            }
        }
    }

    private suspend fun handleWithBackendOrAgent(command: String, startedNs: Long) {
        if (!backendAiClient.isConfigured()) {
            val elapsed = (System.nanoTime() - startedNs) / 1_000_000
            manager.setState(OverlayState.PARSING)
            manager.updateTranscript(
                "✕ Command not recognized offline. Connect a Control X backend server in Settings for dynamic AI.",
                elapsed,
            )
            return
        }

        manager.setState(OverlayState.PARSING)
        val interpreted = runCatching {
            withContext(Dispatchers.IO) {
                if (AppCatalogManager.snapshot().isEmpty()) {
                    AppCatalogManager.refresh(appContext)
                }
                val perms = permissionRepository.snapshot()
                backendAiClient.interpretIntent(
                    command = command,
                    installedApps = AppCatalogManager.snapshot(),
                    capabilities = DeviceCapabilities(
                        contactsGranted = perms.contactsGranted,
                        accessibilityEnabled = ControlAccessibilityService.isActive(),
                        deviceAdminActive = perms.deviceAdminActive,
                    ),
                )
            }
        }

        val elapsed = (System.nanoTime() - startedNs) / 1_000_000
        if (interpreted.isFailure) {
            val message = interpreted.exceptionOrNull()?.message ?: "Backend AI request failed."
            manager.updateTranscript("✕ $message", elapsed)
            return
        }

        when (val outcome = dynamicExecutor.execute(interpreted.getOrThrow(), command)) {
            is DynamicExecutionResult.Executed -> {
                manager.setState(OverlayState.EXECUTING)
                manager.updateTranscript("✓ ${outcome.message}", elapsed)
                delay(if (outcome.keepVisibleLonger) 4000 else 800)
                dismiss()
            }
            is DynamicExecutionResult.NeedsScreenAgent -> {
                val agentResult = taskRunner.run(outcome.goal) { action ->
                    requestAgentActionApproval(action)
                }
                val totalElapsed = (System.nanoTime() - startedNs) / 1_000_000
                when (agentResult) {
                    is TaskResult.Executed -> {
                        manager.setState(OverlayState.EXECUTING)
                        manager.updateTranscript("✓ ${agentResult.message}", totalElapsed)
                        delay(800)
                        dismiss()
                    }
                    is TaskResult.Error -> {
                        manager.setState(OverlayState.PARSING)
                        manager.updateTranscript("✕ ${agentResult.reason}", totalElapsed)
                    }
                }
            }
            is DynamicExecutionResult.Error -> {
                manager.setState(OverlayState.PARSING)
                manager.updateTranscript("✕ ${outcome.reason}", elapsed)
            }
        }
    }

    private suspend fun runYouTubeSelectionIfAccessible(command: String, elapsed: Long) {
        manager.updateTranscript(
            "✓ Opening playback and selecting the first accessible match.",
            elapsed,
        )
        val query = Regex("""(?i)^play\s+(.+)$""")
            .find(command)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
        val mediaGoal =
            "In the currently open YouTube or browser media screen, search for $query, wait for results, and play the first clearly matching video or track."
        val agentResult = taskRunner.run(mediaGoal) { action ->
            action.action.name != "TYPE" ||
                !action.textInput.orEmpty().contains(
                    Regex("""(?i)(password|pin|otp|cvv|secret)"""),
                )
        }
        when (agentResult) {
            is TaskResult.Executed ->
                manager.updateTranscript("✓ Media result selected.", elapsed)
            is TaskResult.Error ->
                manager.updateTranscript("✕ ${agentResult.reason}", elapsed)
        }
    }

    private fun logCommandAsync(command: String, result: FastPathResult, elapsed: Long) {
        val recordStatus = when (result) {
            is FastPathResult.Executed -> "executed"
            FastPathResult.Unhandled -> "unhandled"
            is FastPathResult.Error -> "error"
        }
        val recordMessage = when (result) {
            is FastPathResult.Executed -> result.message
            FastPathResult.Unhandled -> "Forwarded to dynamic backend router."
            is FastPathResult.Error -> result.reason
        }
        scope.launch(Dispatchers.IO) {
            if (commandApiClient.isConfigured()) {
                commandApiClient.saveCommand(
                    commandText = command,
                    source = "overlay",
                    resultStatus = recordStatus,
                    resultMessage = recordMessage,
                    latencyMs = elapsed,
                )
            }
        }
    }

    private fun dismiss() {
        listeningForCompletion = false
        voiceInput.stopListening()
        manager.hide()
        executionGuard.reset()
    }

    private fun dismissImmediate() {
        listeningForCompletion = false
        voiceInput.stopListening()
        manager.hide()
    }

    private fun pauseForPayment() {
        executionGuard.stop()
        commandJob?.cancel()
        pendingAgentConfirmation?.complete(false)
        dismissImmediate()
    }

    private suspend fun requestAgentActionApproval(action: AgentAction): Boolean {
        pendingAgentConfirmation?.cancel()
        val confirmation = CompletableDeferred<Boolean>()
        pendingAgentConfirmation = confirmation
        manager.requestAgentActionApproval(
            action.thought.ifBlank { action.action.name.lowercase() },
        )
        return try {
            confirmation.await()
        } finally {
            pendingAgentConfirmation = null
            manager.clearAgentActionApproval()
        }
    }
}
