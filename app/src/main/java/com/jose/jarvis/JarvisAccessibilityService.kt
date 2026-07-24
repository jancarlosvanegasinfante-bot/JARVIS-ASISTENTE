package com.jose.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telephony.SmsManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * JarvisAccessibilityService
 *
 * El "brazo robótico" de Jarvis en Android.
 * Ejecuta acciones reales en el sistema:
 *  - Enviar mensajes por WhatsApp y SMS
 *  - Realizar y contestar llamadas telefónicas (Modo Moto)
 *  - Configurar alarmas nativas sin tocar el celular
 *  - Buscar y reproducir canciones en YouTube y Spotify
 *  - Abrir, navegar y cerrar aplicaciones ("Cierra la app", "Ir a inicio")
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        var instance: JarvisAccessibilityService? = null
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Jarvis Accessibility Service activo y conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Servicio interrumpido")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Punto de entrada principal ejecutado desde JS / MainActivity */
    fun executeAction(actionJson: String) {
        try {
            Log.d(TAG, "Ejecutando acción nativa recibida: $actionJson")
            val json = JSONObject(actionJson)
            val params = json.optJSONObject("params") ?: JSONObject()
            val action = json.optString("action", "")

            when (action) {
                "send_whatsapp" -> sendWhatsApp(
                    contact = params.optString("contact"),
                    phone = params.optString("phoneNumber"),
                    message = params.optString("message")
                )

                "make_call" -> makeCall(params.optString("phoneNumber"), params.optString("contact"))

                "answer_call" -> answerCall()

                "who_is_calling", "who_messaged" -> readLatestNotification()

                "reply_message" -> replyLatestMessage(params.optString("message"))

                "set_reminder", "set_alarm" -> setAlarm(
                    time = params.optString("time"),
                    title = params.optString("title", "Alarma de Jarvis")
                )

                "send_sms" -> sendSms(
                    contact = params.optString("contact"),
                    phone = params.optString("phoneNumber"),
                    message = params.optString("message")
                )

                "play_youtube" -> playYouTube(params.optString("query"))

                "play_spotify" -> playSpotify(params.optString("track"))

                "open_app" -> openApp(resolvePackageName(params.optString("appName")))

                "close_app" -> closeApp()

                "go_home" -> performGlobalAction(GLOBAL_ACTION_HOME)

                "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)

                "search_web" -> searchWeb(params.optString("query"))

                "control_music" -> controlMusic(
                    command = params.optString("command"),
                    track = params.optString("track")
                )

                "read_notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

                "tap_text" -> tapNodeByText(json.optString("text"))

                "type_text" -> typeText(json.optString("text"))

                else -> Log.w(TAG, "Acción no reconocida: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando acción nativa: $actionJson", e)
        }
    }

    /** Envío de WhatsApp con fallback inteligente a Deep Link / Accesibilidad */
    private fun sendWhatsApp(contact: String, phone: String, message: String) {
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        if (cleanPhone.length >= 7) {
            // Intent directo por API de WhatsApp
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                handler.postDelayed({
                    // Tocar automáticamente el botón de enviar en WhatsApp si aparece
                    tapNodeByText("Enviar") || tapNodeByText("Send")
                }, 2000)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al abrir Deep Link de WhatsApp, intentando por interfaz visual...", e)
            }
        }

        // Fallback por Accesibilidad visual
        openApp("com.whatsapp")
        handler.postDelayed({
            val target = if (contact.isNotBlank()) contact else phone
            if (tapNodeByText(target)) {
                handler.postDelayed({
                    typeText(message)
                    handler.postDelayed({
                        tapNodeByText("Enviar") || tapNodeByText("Send")
                    }, 600)
                }, 1200)
            }
        }, 1800)
    }

    /** Realizar llamada telefónica */
    private fun makeCall(phone: String, contact: String) {
        val targetNum = phone.ifBlank { contact }
        if (targetNum.isBlank()) return
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${Uri.encode(targetNum)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(callIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error al realizar llamada", e)
        }
    }

    /** Contestar llamada entrante por voz (Modo Moto) */
    private fun answerCall() {
        val answered = MainActivity.AndroidBridge(MainActivity()).answerPhoneCall()
        if (!answered) {
            // Fallback por botón visual de contestar
            tapNodeByText("Contestar") || tapNodeByText("Responder") || tapNodeByText("Answer")
        }
    }

    /** Narrar por voz la notificación más reciente */
    private fun readLatestNotification() {
        val text = JarvisNotificationListener.getLatestNotificationSummary()
        speak(text)
    }

    /** Responder al último mensaje recibido por voz */
    private fun replyLatestMessage(message: String) {
        val senderPkg = JarvisNotificationListener.latestMessagePackage
        val senderName = JarvisNotificationListener.latestSenderName

        if (senderPkg == "com.whatsapp") {
            sendWhatsApp(contact = senderName, phone = "", message = message)
        } else {
            sendSms(contact = senderName, phone = "", message = message)
        }
    }

    /** Configurar alarma nativa de Android sin interacción táctil */
    private fun setAlarm(time: String, title: String) {
        var hour = 7
        var minute = 0
        val parts = time.split(":")
        if (parts.size >= 2) {
            hour = parts[0].toIntOrNull() ?: 7
            minute = parts[1].toIntOrNull() ?: 0
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            speak("Alarma configurada correctamente a las $hour:${minute.toString().padStart(2, '0')}")
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando alarma", e)
        }
    }

    /** Enviar SMS nativo */
    private fun sendSms(contact: String, phone: String, message: String) {
        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        if (cleanPhone.isNotBlank()) {
            try {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(cleanPhone, null, message, null, null)
                speak("Mensaje SMS enviado a $contact")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Error usando SmsManager directo, intentando por Intent...", e)
            }
        }

        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(cleanPhone)}")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(smsIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo app de SMS", e)
        }
    }

    /** Reproducir canción o video en YouTube */
    private fun playYouTube(query: String) {
        val searchUrl = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            speak("Reproduciendo $query en YouTube")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo YouTube", e)
        }
    }

    /** Reproducir música en Spotify */
    private fun playSpotify(track: String) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager_QUERY, track)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            speak("Buscando $track en Spotify")
        } catch (e: Exception) {
            // Fallback a abrir Spotify directo
            openApp("com.spotify.music")
        }
    }

    private const val SearchManager_QUERY = "query"

    private fun controlMusic(command: String, track: String) {
        if (track.isNotBlank()) {
            playSpotify(track)
            return
        }
        val keyCode = when (command) {
            "play", "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volume_up" -> android.view.KeyEvent.KEYCODE_VOLUME_UP
            "volume_down" -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            else -> return
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_RAISE, 0)
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_LOWER, 0)
            else -> Log.d(TAG, "Comando multimedia enviado: $command")
        }
    }

    private fun resolvePackageName(appName: String): String {
        val clean = appName.trim().lowercase()
        return when {
            clean.contains("whatsapp") -> "com.whatsapp"
            clean.contains("spotify") -> "com.spotify.music"
            clean.contains("youtube") -> "com.google.android.youtube"
            clean.contains("waze") -> "com.waze"
            clean.contains("maps") || clean.contains("mapas") -> "com.google.android.apps.maps"
            clean.contains("teléfono") || clean.contains("telefono") -> "com.android.dialer"
            clean.contains("sms") || clean.contains("mensajes") -> "com.android.mms"
            clean.contains("reloj") || clean.contains("alarma") -> "com.android.deskclock"
            clean.contains("chrome") || clean.contains("navegador") -> "com.android.chrome"
            else -> appName
        }
    }

    private fun openApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Log.w(TAG, "Aplicación no encontrada: $packageName")
        }
    }

    private fun closeApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun searchWeb(query: String) {
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(searchIntent)
    }

    private fun speak(text: String) {
        MainActivity.instance?.speakNative(text)
    }

    private fun tapNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            val clickable = findClickableParent(node)
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
