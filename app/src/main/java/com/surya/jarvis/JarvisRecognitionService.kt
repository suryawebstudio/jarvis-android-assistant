package com.surya.jarvis

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Compatibility entry point required by VoiceInteractionService metadata on
 * older Android releases. MARK uses Gemini Live AudioRecord for recognition.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {
        callback?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(callback: Callback?) = Unit

    override fun onCancel(callback: Callback?) = Unit
}
