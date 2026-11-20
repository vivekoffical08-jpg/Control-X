package com.controlx.input

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class AndroidVoiceRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    private var activeCallback: Callback? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        activeCallback = callback
        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        onCancel(callback)
        val created = SpeechRecognizer.createSpeechRecognizer(applicationContext)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callback.readyForSpeech(params)
            override fun onBeginningOfSpeech() = callback.beginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) = callback.rmsChanged(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = callback.bufferReceived(buffer)
            override fun onEndOfSpeech() = callback.endOfSpeech()
            override fun onPartialResults(results: Bundle?) =
                callback.partialResults(results)
            override fun onResults(results: Bundle?) = callback.results(results)
            override fun onError(error: Int) = callback.error(error)
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        created.startListening(
            intent.apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true,
                )
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
            },
        )
    }

    override fun onCancel(callback: Callback) {
        if (activeCallback === callback) activeCallback = null
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onStopListening(callback: Callback) {
        recognizer?.stopListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
