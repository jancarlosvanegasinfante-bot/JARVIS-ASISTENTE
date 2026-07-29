package com.jose.jarvis

import android.content.Context
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
        @Volatile var proactiveModeEnabled: Boolean = false

        private var lastAnnouncedKey: String = ""
        private var lastAnnouncedTime: Long = 0

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

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        proactiveModeEnabled = prefs.getBoolean("proactive_mode", false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName ?: ""
        val extras = sbn.notification?.extras ?: return

        val title = extras.getString("android.title") ?: extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getString("android.text") ?: extras.getCharSequence("android.text")?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return
        Log.d(TAG, "Notificación recibida de [$packageName]: $title -> $text")

        // Interceptación de Llamadas Entrantes
        if (packageName.contains("dialer") || packageName.contains("incallui") || packageName.contains("telecom") || packageName.contains("phone")) {
            if (title.isNotBlank()) {
                latestCallerName = title
                speakProactive("Te está llamando $title")
            }
        }

        // Interceptación de Mensajes (WhatsApp, Telegram, SMS)
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b" || packageName.contains("mms") || packageName.contains("messaging") || packageName.contains("telegram")) {
            if (title.isNotBlank() && text.isNotBlank() && !text.contains("Buscando nuevos mensajes") && !text.contains("WhatsApp Web")) {
                latestSenderName = title
                latestMessageContent = text
                latestMessagePackage = packageName

                speakProactive("Mensaje de $title: $text")
            }
        }
    }

    private fun speakProactive(message: String) {
        val now = System.currentTimeMillis()
        if (proactiveModeEnabled && (message != lastAnnouncedKey || now - lastAnnouncedTime > 5000)) {
            lastAnnouncedKey = message
            lastAnnouncedTime = now
            MainActivity.instance?.speakNative(message)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
