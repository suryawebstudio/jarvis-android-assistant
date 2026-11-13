package com.surya.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * User-enabled wake-word listener. It keeps a visible microphone foreground
 * notification and stops itself as soon as it hears "Jarvis".
 *
 * On-device recognition is preferred when the device exposes it. The Android
 * speech provider may still use its own network service on devices without an
 * on-device recognizer; this is shown in the notification wording.
 */
class JarvisWakeWordService : Service(), RecognitionListener {
    companion object {
        private const val CHANNEL_ID = "jarvis_wake_word_channel"
        private const val NOTIFICATION_ID = 703
        private const val ACTION_START = "com.surya.jarvis.action.START_WAKE_WORD"
        private const val WAKE_WORD = "jarvis"

        fun start(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisWakeWordService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(NOTIFICATION_ID, buildNotification())
            handler.post { beginListening() }
        }
        return START_NOT_STICKY
    }

    private fun beginListening() {
        if (shuttingDown || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        recognizer?.destroy()
        recognizer = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(this)
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listening = true
        recognizer?.startListening(request)
    }

    private fun checkWakeWord(texts: List<String>) {
        if (texts.any { containsWakeWord(it) }) triggerWake()
    }

    private fun containsWakeWord(value: String): Boolean =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .contains(WAKE_WORD)

    private fun triggerWake() {
        if (shuttingDown) return
        shuttingDown = true
        listening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        handler.postDelayed({ JarvisVoiceForegroundService.start(this) }, 250L)
    }

    override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        listening = false
        scheduleRestart()
    }
    override fun onError(error: Int) {
        listening = false
        if (!shuttingDown) scheduleRestart()
    }
    override fun onResults(results: android.os.Bundle?) {
        listening = false
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        checkWakeWord(texts)
        if (!shuttingDown) scheduleRestart()
    }
    override fun onPartialResults(partialResults: android.os.Bundle?) {
        val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        checkWakeWord(texts)
    }
    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

    private fun scheduleRestart() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ beginListening() }, 350L)
    }

    override fun onDestroy() {
        shuttingDown = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("JARVIS wake word enabled")
        .setContentText("Say “Jarvis” to start voice mode")
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                3,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS wake word",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows while JARVIS listens for its user-enabled wake word."
                    setSound(null, null)
                }
            )
        }
    }
}
