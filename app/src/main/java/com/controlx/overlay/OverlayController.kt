package com.controlx.overlay

import android.content.Context
import android.provider.Settings
import com.controlx.domain.FastPathResult
import com.controlx.input.VoiceInputManager
import com.controlx.router.FastPathRouter
import com.controlx.security.ExecutionGuard
import com.controlx.platform.GroqKeyStore
import com.controlx.platform.CommandApiClient
import com.controlx.agent.UniversalTaskRunner
import com.controlx.agent.UniversalTaskRunner.TaskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val groqKeyStore by lazy { GroqKeyStore(appContext) }
    private val commandApiClient by lazy { CommandApiClient(appContext) }
    private val taskRunner by lazy {
        UniversalTaskRunner(appContext) { groqKeyStore.load() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningForCompletion = false

    init {
        manager.onDismissRequested = { dismiss() }
        manager.onEmergencyAbort = { emergencyAbort() }
        manager.onTranscriptSubmit = { command ->
            scope.launch { processCommand(command) }
        }

        scope.launch {
            executionGuard.stopped.collectLatest { stopped ->
                if (stopped) dismissImmediate()
            }
        }
    }

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext)) return false
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
                    processCommand(text)
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

    private suspend fun processCommand(command: String) {
        if (!listeningForCompletion) listeningForCompletion = true
        listeningForCompletion = false
        voiceInput.stopListening()
        manager.setState(OverlayState.PARSING)
        manager.updateTranscript("Processing: $command", 0)

        val started = System.nanoTime()
        val result = withContext(Dispatchers.Default) {
            router.route(command)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000

        val recordStatus = when (result) {
            is FastPathResult.Executed -> "executed"
            FastPathResult.Unhandled -> "unhandled"
            is FastPathResult.Error -> "error"
        }
        val recordMessage = when (result) {
            is FastPathResult.Executed -> result.message
            FastPathResult.Unhandled -> "No Fast-Path match."
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

        if (executionGuard.checkCanContinue().let { it is com.controlx.security.GuardResult.Blocked }) {
            dismissImmediate()
            return
        }

        when (result) {
            is FastPathResult.Executed -> {
                manager.setState(OverlayState.EXECUTING)
                manager.updateTranscript("✓ ${result.message}", elapsed)
                delay(800)
                dismiss()
            }
            FastPathResult.Unhandled -> {
                manager.setState(OverlayState.PARSING)
                val agentResult = taskRunner.run(command)
                when (agentResult) {
                    is TaskResult.Executed -> {
                        manager.setState(OverlayState.EXECUTING)
                        manager.updateTranscript("✓ ${agentResult.message}", elapsed)
                        delay(800)
                        dismiss()
                    }
                    is TaskResult.Error -> {
                        manager.setState(OverlayState.PARSING)
                        manager.updateTranscript("✕ ${agentResult.reason}", elapsed)
                    }
                }
            }
            is FastPathResult.Error -> {
                manager.setState(OverlayState.PARSING)
                manager.updateTranscript("✕ ${result.reason}", elapsed)
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
}
