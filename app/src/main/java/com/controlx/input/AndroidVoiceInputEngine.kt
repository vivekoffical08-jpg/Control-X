package com.controlx.input

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class AndroidVoiceInputEngine(
    context: Context,
) : VoiceInputEngine {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listener: VoiceInputEngine.Listener? = null

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return null
        recognizer?.let { return it }
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { listener?.onPartialResult(it) }
            }

            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { listener?.onFinalResult(it) }
                    ?: listener?.onError("Speech recognition returned no result.")
            }

            override fun onError(error: Int) {
                listener?.onError("Speech recognition failed with code $error.")
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer = created
        return created
    }

    override fun start(listener: VoiceInputEngine.Listener) {
        this.listener = listener
        val activeRecognizer = ensureRecognizer()
        if (activeRecognizer == null) {
            listener.onError("Speech recognition is unavailable on this device.")
            return
        }
        activeRecognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
    }

    override fun stop() {
        recognizer?.cancel()
        listener = null
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listener = null
    }
}
