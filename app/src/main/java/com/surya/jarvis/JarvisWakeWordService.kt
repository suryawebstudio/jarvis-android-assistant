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
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Fully local wake-word listener. Vosk reads microphone PCM and recognizes only
 * the small grammar containing "mark" and unknown speech. No Android
 * SpeechRecognizer, network speech provider, or Gemini connection is used here.
 */
class JarvisWakeWordService : Service() {
    companion object {
        private const val TAG = "MARKWakeWord"
        private const val CHANNEL_ID = "jarvis_wake_word_channel"
        private const val NOTIFICATION_ID = 703
        private const val ACTION_START = "com.surya.jarvis.action.START_WAKE_WORD"
        private const val ASSET_MODEL = "vosk-model-small-en-us-0.15"
        private const val MODEL_DIR = "jarvis-vosk-model"
        private const val SAMPLE_RATE = 16000f

        fun start(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisWakeWordService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var shuttingDown = false
    private var loading = false

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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Loading offline wake model"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification("Loading offline wake model"))
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not start microphone foreground service", error)
                stopSelf()
                return START_NOT_STICKY
            }
            handler.post { loadModelAndListen() }
        }
        return START_NOT_STICKY
    }

    private fun loadModelAndListen() {
        if (shuttingDown || loading || speechService != null) return
        loading = true
        StorageService.unpack(
            this,
            ASSET_MODEL,
            MODEL_DIR,
            { unpackedModel ->
                if (shuttingDown) {
                    unpackedModel.close()
                    return@unpack
                }
                try {
                    model = unpackedModel
                    // A constrained grammar keeps this always-on recognizer small
                    // and prevents ordinary speech from becoming an action.
                    val localRecognizer = Recognizer(
                        unpackedModel,
                        SAMPLE_RATE,
                        "[\"mark\", \"[unk]\"]"
                    )
                    recognizer = localRecognizer
                    speechService = SpeechService(localRecognizer, SAMPLE_RATE)
                    updateNotification("Offline wake word active — say Mark")
                    speechService?.startListening(voskListener)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not initialize Vosk wake-word microphone", error)
                    updateNotification("Wake word microphone failed — reopen MARK and try again")
                    stopSelf()
                } finally {
                    loading = false
                }
            },
            { error ->
                Log.e(TAG, "Could not unpack offline wake model", error)
                updateNotification("Wake model failed — reopen MARK and try again")
                loading = false
                stopSelf()
            }
        )
    }

    private val voskListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (containsWakeWord(hypothesis)) triggerWake()
        }

        override fun onResult(hypothesis: String?) {
            if (containsWakeWord(hypothesis)) triggerWake()
        }

        override fun onFinalResult(hypothesis: String?) {
            if (containsWakeWord(hypothesis)) triggerWake()
        }

        override fun onError(exception: Exception?) {
            if (!shuttingDown) handler.postDelayed({ restartRecognition() }, 500L)
        }

        override fun onTimeout() {
            if (!shuttingDown) handler.post { restartRecognition() }
        }
    }

    private fun containsWakeWord(hypothesis: String?): Boolean {
        val text = hypothesis.orEmpty()
        val candidates = listOf(
            runCatching { JSONObject(text).optString("partial") }.getOrDefault(""),
            runCatching { JSONObject(text).optString("text") }.getOrDefault("")
        )
        return candidates.any { candidate ->
            candidate.lowercase().split(Regex("[^a-z0-9]+"))
                .any { it == "mark" || it == "mark." }
        }
    }

    private fun restartRecognition() {
        if (shuttingDown || speechService == null) return
        runCatching {
            speechService?.cancel()
            speechService?.startListening(voskListener)
        }.onFailure { stopSelf() }
    }

    private fun triggerWake() {
        if (shuttingDown) return
        shuttingDown = true
        runCatching { speechService?.cancel() }
        // Start the microphone foreground service before destroying this
        // detector. onDestroy() clears pending callbacks, so a delayed start
        // here would be cancelled before it could run.
        runCatching { JarvisVoiceForegroundService.start(this) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shuttingDown = true
        handler.removeCallbacksAndMessages(null)
        runCatching { speechService?.shutdown() }
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        speechService = null
        recognizer = null
        model = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(status: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("MARK offline wake word enabled")
        .setContentText(status)
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

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MARK offline wake word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while MARK locally listens for its wake word."
                setSound(null, null)
            }
        )
    }
}
