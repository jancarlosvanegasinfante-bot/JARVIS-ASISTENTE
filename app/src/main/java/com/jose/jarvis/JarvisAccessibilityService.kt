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

                "open_app" -> openApp(params.optString("appName"))

                "close_app" -> closeApp()

                "go_home" -> performGlobalAction(GLOBAL_ACTION_HOME)

                "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)

                "search_web" -> searchWeb(params.optString("query"))

                "control_music" -> controlMusic(
                    command = params.optString("command"),
                    track = params.optString("track")
                )

                "read_notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

                "general_query" -> { /* No requiere acción nativa: la respuesta ya se habla en React vía TTS */ }

                "tap_text" -> tapNodeByText(json.optString("text"))

                "type_text" -> typeText(json.optString("text"))

                else -> Log.w(TAG, "Acción no reconocida: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando acción nativa: $actionJson", e)
        }
    }

    /**
     * Detecta automáticamente cuál WhatsApp tienes instalado: el normal
     * (com.whatsapp) o Business (com.whatsapp.w4b). Prueba primero Business
     * porque es el que usas tú; si no está, usa el normal.
     */
    private fun resolveWhatsAppPackage(): String {
        val candidates = listOf("com.whatsapp.w4b", "com.whatsapp")
        for (pkg in candidates) {
            if (packageManager.getLaunchIntentForPackage(pkg) != null) return pkg
        }
        return "com.whatsapp.w4b" // valor por defecto si ninguno resuelve (no debería pasar)
    }

    /** Envío de WhatsApp con fallback inteligente a Deep Link / Accesibilidad */
    private fun sendWhatsApp(contact: String, phone: String, message: String) {
        val whatsappPkg = resolveWhatsAppPackage()
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")

        if (cleanPhone.length >= 7) {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(whatsappPkg) // fuerza que abra TU WhatsApp (Business), no otro
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                handler.postDelayed({
                    tapNodeByText("Enviar") || tapNodeByText("Send")
                }, 2000)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al abrir Deep Link de WhatsApp, intentando por interfaz visual...", e)
            }
        }

        openApp(whatsappPkg)
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

    /** Realizar llamada telefónica (con fallback si falta el permiso) */
    private fun makeCall(phone: String, contact: String) {
        val targetNum = phone.ifBlank { contact }
        if (targetNum.isBlank()) return

        val hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val callIntent = Intent(action).apply {
            data = Uri.parse("tel:${Uri.encode(targetNum)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(callIntent)
            if (!hasCallPermission) {
                speak("No tengo permiso de llamadas todavía, te dejé el número listo, solo toca llamar")
                Log.w(TAG, "Falta permiso CALL_PHONE — abrí el marcador en su lugar. Actívalo en Ajustes > Apps > Jarvis > Permisos > Teléfono")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al realizar llamada", e)
        }
    }

    /** Contestar llamada entrante por voz (Modo Moto) */
    private fun answerCall() {
        val answered = MainActivity.instance?.answerPhoneCall() ?: false
        if (!answered) {
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

        if (senderPkg == "com.whatsapp" || senderPkg == "com.whatsapp.w4b") {
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
                @Suppress("DEPRECATION")
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
            putExtra("query", track)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            speak("Buscando $track en Spotify")
        } catch (e: Exception) {
            openApp("com.spotify.music")
        }
    }

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

    /**
     * Atajos rápidos para las apps más comunes (evita tener que escanear
     * todas las apps instaladas cada vez que pides algo frecuente).
     * Si el nombre no está aquí, se busca dinámicamente más abajo.
     */
    private fun quickPackageShortcut(appName: String): String? {
        val clean = appName.trim().lowercase()
        return when {
            clean.contains("whatsapp") -> resolveWhatsAppPackage()
            clean.contains("spotify") -> "com.spotify.music"
            clean.contains("youtube") -> "com.google.android.youtube"
            clean.contains("waze") -> "com.waze"
            clean.contains("maps") || clean.contains("mapas") -> "com.google.android.apps.maps"
            clean.contains("teléfono") || clean.contains("telefono") -> "com.android.dialer"
            clean.contains("sms") || clean.contains("mensajes") -> "com.android.mms"
            clean.contains("reloj") || clean.contains("alarma") -> "com.android.deskclock"
            clean.contains("chrome") || clean.contains("navegador") -> "com.android.chrome"
            else -> null
        }
    }

    /**
     * Busca CUALQUIER app instalada por su nombre visible (el que ves en el
     * launcher, ej "Instagram", "TikTok", "Play Store"), sin importar que no
     * esté en la lista de atajos rápidos. Esto es lo que permite decir
     * "abre [cualquier app]" y que funcione con lo que sea que tengas instalado.
     */
    private fun findInstalledAppByLabel(appName: String): String? {
        val target = normalizeForSearch(appName)
        if (target.isBlank()) return null

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launcherIntent, 0)

        var bestMatch: String? = null
        var bestScore = -1

        for (resolveInfo in activities) {
            val label = normalizeForSearch(resolveInfo.loadLabel(packageManager).toString())
            val pkg = resolveInfo.activityInfo.packageName

            val score = when {
                label == target -> 100                 // coincidencia exacta
                label.startsWith(target) -> 80          // "insta" -> "instagram"
                label.contains(target) -> 60            // nombre contenido
                target.contains(label) && label.length > 2 -> 50
                else -> -1
            }

            if (score > bestScore) {
                bestScore = score
                bestMatch = pkg
            }
        }

        if (bestMatch == null) {
            Log.w(TAG, "No se encontró ninguna app instalada parecida a: $appName")
        }
        return bestMatch
    }

    /** Quita tildes, mayúsculas y espacios extra para comparar nombres */
    private fun normalizeForSearch(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{Mn}+"), "").trim().lowercase()
    }

    private fun openApp(appNameOrPackage: String) {
        // 1. ¿Es un atajo rápido conocido?
        val resolved = quickPackageShortcut(appNameOrPackage)
            // 2. ¿Ya es un nombre de paquete válido tal cual (com.algo.algo)?
            ?: if (packageManager.getLaunchIntentForPackage(appNameOrPackage) != null) {
                appNameOrPackage
            } else {
                // 3. Búsqueda dinámica entre TODAS las apps instaladas
                findInstalledAppByLabel(appNameOrPackage)
            }

        if (resolved == null) {
            Log.w(TAG, "No se pudo resolver ninguna app para: $appNameOrPackage")
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(resolved)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            Log.d(TAG, "Abriendo app: $resolved")
        } else {
            Log.w(TAG, "Se encontró el paquete pero no se pudo lanzar: $resolved")
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
