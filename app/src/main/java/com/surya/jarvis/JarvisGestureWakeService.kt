package com.surya.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * User-enabled, sensor-only fallback. It does not use the microphone; it waits
 * for a shake and then asks the existing voice service to start MARK Live.
 */
class JarvisGestureWakeService : Service(), SensorEventListener {
    companion object {
        private const val CHANNEL_ID = "jarvis_gesture_channel"
        private const val NOTIFICATION_ID = 702
        private const val ACTION_START = "com.surya.jarvis.action.START_GESTURE_WAKE"
        private const val ACTION_STOP = "com.surya.jarvis.action.STOP_GESTURE_WAKE"
        private const val SHAKE_THRESHOLD = 10.5f
        private const val SHAKE_COOLDOWN_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, JarvisGestureWakeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisGestureWakeService::class.java))
        }
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastMagnitude = SensorManager.GRAVITY_EARTH
    private var sensorRegistered = false
    private var lastShakeAt = 0L
    private val stopped = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        stopped.set(false)
        createNotificationChannel()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                if (accelerometer == null) {
                    stopSelf()
                } else {
                    sensorRegistered = sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME) == true
                    if (!sensorRegistered) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || stopped.get()) return
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        val delta = kotlin.math.abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        val now = System.currentTimeMillis()
        if (delta >= SHAKE_THRESHOLD && now - lastShakeAt >= SHAKE_COOLDOWN_MS) {
            lastShakeAt = now
            JarvisVoiceForegroundService.start(this)
            stopSelf()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        stopped.set(true)
        if (sensorRegistered) sensorManager?.unregisterListener(this)
        sensorRegistered = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setContentTitle("MARK shake wake enabled")
        .setContentText("Shake the phone to start MARK voice mode")
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                2,
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
                    "MARK gesture wake",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows while the user-enabled shake wake sensor is active."
                    setSound(null, null)
                }
            )
        }
    }
}
