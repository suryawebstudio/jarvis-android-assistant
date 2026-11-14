package com.surya.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class JarvisVoiceForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.surya.jarvis.action.START_VOICE"
        const val ACTION_STOP = "com.surya.jarvis.action.STOP_VOICE"
        const val ACTION_STATUS = "com.surya.jarvis.action.VOICE_STATUS"
        const val ACTION_USER_SPEECH = "com.surya.jarvis.action.USER_SPEECH"
        const val EXTRA_STATUS = "status"
        const val EXTRA_SPEECH = "speech"

        private const val CHANNEL_ID = "jarvis_voice_channel"
        private const val NOTIFICATION_ID = 701

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: android.content.Context) {
            val intent = Intent(context, JarvisVoiceForegroundService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            // Stop the running service directly. Sending a second startService
            // command can leave the foreground notification alive on some devices.
            context.stopService(Intent(context, JarvisVoiceForegroundService::class.java))
        }
    }

    private var voiceManager: LiveVoiceManager? = null
    private val destroyed = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        destroyed.set(false)
        createNotificationChannel()

        voiceManager = LiveVoiceManager(
            applicationContext,
            onUserSpeech = { spokenText ->
                sendBroadcast(Intent(ACTION_USER_SPEECH).setPackage(packageName).putExtra(EXTRA_SPEECH, spokenText))
            },
            onStatus = { status ->
                sendStatus(status)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    // Mark the service stopped before callbacks are delivered. This prevents
                    // the manager's final status callback from trying to update a removed
                    // foreground notification during service destruction.
                    isRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                ACTION_START -> {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting MARK voice listening"))
                    if (!isRunning) {
                        isRunning = true
                        sendStatus("CONNECTING TO MARK LIVE...")
                        voiceManager?.startListening()
                        updateNotification("MARK is listening")
                    }
                }
            }
        } catch (_: Exception) {
            // A rapid start/stop transition can make an audio or notification
            // operation invalid. Terminate cleanly instead of crashing the app.
            isRunning = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!destroyed.compareAndSet(false, true)) return
        isRunning = false
        try {
            voiceManager?.release()
        } catch (_: Exception) {
            // Cleanup must never bring down the host process.
        }
        voiceManager = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // The service may already have been removed by the system.
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        isRunning = false
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sendStatus(status: String) {
        if (destroyed.get()) return
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
        updateNotification(status)
    }

    private fun updateNotification(status: String) {
        if (!isRunning) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MARK voice control")
            .setContentText(status)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, JarvisVoiceForegroundService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MARK voice control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when MARK is listening in the background."
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
