package com.controlx.input

interface VoiceInputEngine {
    fun start(listener: Listener)
    fun stop()

    interface Listener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
    }
}
