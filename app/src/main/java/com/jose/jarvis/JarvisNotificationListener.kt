package com.jose.jarvis

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotification"

        @Volatile var latestCallerName: String = ""
        @Volatile var latestSenderName: String = ""
        @Volatile var latestMessageContent: String = ""
        @Volatile var latestMessagePackage: String = ""

        fun getLatestNotificationSummary(): String {
            if (latestSenderName.isNotBlank() && latestMessageContent.isNotBlank()) {
                return "Mensaje de $latestSenderName: \"$latestMessageContent\""
            }
            if (latestCallerName.isNotBlank()) {
                return "Llamada entrante de $latestCallerName"
            }
            return "No tienes notificaciones recientes pendientes."
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName ?: ""
        val extras = sbn.notification?.extras ?: return

        val title = extras.getString("android.title") ?: extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getString("android.text") ?: extras.getCharSequence("android.text")?.toString() ?: ""

        Log.d(TAG, "Notificación recibida de [$packageName]: $title -> $text")

        // Interceptación de Llamadas Entrantes
        if (packageName.contains("dialer") || packageName.contains("incallui") || packageName.contains("telecom") || packageName.contains("phone")) {
            if (title.isNotBlank()) {
                latestCallerName = title
            }
        }

        // Interceptación de Mensajes (WhatsApp, Telegram, SMS, Messages)
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b" || packageName.contains("mms") || packageName.contains("messaging") || packageName.contains("telegram")) {
            if (title.isNotBlank() && text.isNotBlank()) {
                latestSenderName = title
                latestMessageContent = text
                latestMessagePackage = packageName
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
