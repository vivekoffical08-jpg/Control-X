package com.controlx.input

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceInputManager(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var partialCallback: (String) -> Unit = {}
    private var finalCallback: (String) -> Unit = {}
    private var errorCallback: (Int) -> Unit = {}
    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (!isAvailable()) return null
        recognizer?.let { return it }
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { text -> partialCallback(text) }
            }

            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { text -> finalCallback(text) }
            }

            override fun onError(error: Int) = errorCallback(error)
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer = created
        return created
    }

    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        partialCallback = onPartial
        finalCallback = onFinal
        errorCallback = onError

        val activeRecognizer = ensureRecognizer()
        if (activeRecognizer == null) {
            onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        activeRecognizer.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        partialCallback = {}
        finalCallback = {}
        errorCallback = {}
    }
}
