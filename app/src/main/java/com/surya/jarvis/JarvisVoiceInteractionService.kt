package com.surya.jarvis

import android.service.voice.VoiceInteractionService

/**
 * System-owned entry point for MARK when the user selects it as the default
 * digital assistant. Heavy voice work stays in the existing foreground service.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        JarvisVoiceForegroundService.start(this)
    }
}
