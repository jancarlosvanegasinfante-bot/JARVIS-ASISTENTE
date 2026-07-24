package com.jose.jarvis

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * JarvisNotificationListener
 *
 * Escucha las notificaciones del sistema Android en segundo plano para el Modo Moto / Conducción.
 * Permite a Jarvis saber quién está llamando o quién envió un mensaje por WhatsApp o SMS,
 * para poder decírselo al usuario por voz ("Jan, ¿quién llama?" / "Jan, ¿quién escribe?")
 * y responderle de inmediato sin tocar el teléfono.
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotification"

        @Volatile
        var latestCallerName: String = ""

        @Volatile
        var latestSenderName: String = ""

        @Volatile
        var latestMessageContent: String = ""

        @Volatile
        var latestMessagePackage: String = ""

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
        if (packageName == "com.whatsapp" || packageName.contains("mms") || packageName.contains("messaging") || packageName.contains("telegram")) {
            if (title.isNotBlank() && text.isNotBlank()) {
                latestSenderName = title
                latestMessageContent = text
                latestMessagePackage = packageName
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Opcional: limpiar si la notificación fue descartada
    }
}
