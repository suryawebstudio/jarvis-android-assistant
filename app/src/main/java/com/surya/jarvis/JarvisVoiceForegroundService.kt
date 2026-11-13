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
            val intent = Intent(context, JarvisVoiceForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private var voiceManager: LiveVoiceManager? = null

    override fun onCreate() {
        super.onCreate()
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
                startForeground(NOTIFICATION_ID, buildNotification("Starting JARVIS voice listening"))
                if (!isRunning) {
                    isRunning = true
                    sendStatus("CONNECTING TO JARVIS LIVE...")
                    voiceManager?.startListening()
                    updateNotification("JARVIS is listening")
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        voiceManager?.release()
        voiceManager = null
        sendStatus("VOICE MODE OFF")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        isRunning = false
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sendStatus(status: String) {
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
            .setContentTitle("J.A.R.V.I.S. voice control")
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
                "JARVIS voice control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when JARVIS is listening in the background."
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
