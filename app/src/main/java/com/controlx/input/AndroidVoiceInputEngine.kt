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
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    private var listener: VoiceInputEngine.Listener? = null

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
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
    }

    override fun start(listener: VoiceInputEngine.Listener) {
        this.listener = listener
        recognizer.startListening(
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
        recognizer.cancel()
        listener = null
    }

    fun destroy() {
        recognizer.destroy()
        listener = null
    }
}
