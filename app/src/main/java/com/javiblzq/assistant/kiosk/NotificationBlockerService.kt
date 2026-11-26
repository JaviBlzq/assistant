package com.javiblzq.assistant.kiosk

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Aggressively cancels all incoming system/app notifications so the tablet
 * remains 100% silent and distraction-free.
 */
class NotificationBlockerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        clearAllSafely()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cancelNotification(sbn.key)
            } else {
                @Suppress("DEPRECATION")
                cancelNotification(sbn.packageName, sbn.tag, sbn.id)
            }
        } catch (_: Exception) {
            clearAllSafely()
        }
    }

    private fun clearAllSafely() {
        try {
            cancelAllNotifications()
        } catch (_: Exception) {
        }
    }
}
