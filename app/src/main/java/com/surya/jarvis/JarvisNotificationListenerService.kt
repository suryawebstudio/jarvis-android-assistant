package com.surya.jarvis

import android.service.notification.NotificationListenerService

/**
 * Presence-only notification listener. MARK does not persist or upload notification
 * contents unless a separate, explicit feature is added later.
 */
class JarvisNotificationListenerService : NotificationListenerService()
