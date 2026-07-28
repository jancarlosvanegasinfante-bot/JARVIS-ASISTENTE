package com.jose.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        var instance: JarvisAccessibilityService? = null
    }

    private var flashlightOn = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Jarvis Accessibility Service activo")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun executeAction(actionJson: String) {
        try {
            Log.d(TAG, "Ejecutando acción nativa: $actionJson")
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
                "set_reminder", "set_alarm" -> setAlarm(time = params.optString("time"), title = params.optString("title", "Alarma Jarvis"))
                "send_sms" -> sendSms(contact = params.optString("contact"), phone = params.optString("phoneNumber"), message = params.optString("message"))
                "play_youtube" -> playYouTube(params.optString("query"))
                "play_spotify" -> playSpotify(params.optString("track"))
                "open_app" -> openApp(params.optString("appName"))
                "close_app" -> closeApp()
                "go_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "search_web" -> searchWeb(params.optString("query"))
                "control_music" -> controlMusic(command = params.optString("command"), track = params.optString("track"))
                "read_notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "tap_text" -> tapNodeByText(json.optString("text"))
                "type_text" -> typeText(json.optString("text"))

                // --- Tanda nueva: cámara, linterna, WiFi/Bluetooth, avión, brillo ---
                "take_photo" -> takePhoto()
                "open_camera" -> openApp("com.android.camera")
                "toggle_flashlight" -> toggleFlashlight()
                "toggle_wifi" -> openWifiPanel()
                "toggle_bluetooth" -> toggleBluetooth()
                "airplane_mode" -> openAirplaneModeSettings()
                "set_brightness" -> setBrightness(params.optInt("level", 50))

                else -> Log.w(TAG, "Acción no reconocida: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando acción nativa: $actionJson", e)
        }
    }

    private fun resolveWhatsAppPackage(): String {
        val candidates = listOf("com.whatsapp.w4b", "com.whatsapp")
        for (pkg in candidates) {
            if (packageManager.getLaunchIntentForPackage(pkg) != null) return pkg
        }
        return "com.whatsapp.w4b"
    }

    private fun sendWhatsApp(contact: String, phone: String, message: String) {
        val whatsappPkg = resolveWhatsAppPackage()
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")

        if (cleanPhone.length >= 7) {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(whatsappPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                handler.postDelayed({ tapNodeByText("Enviar") || tapNodeByText("Send") }, 2000)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al abrir Deep Link de WhatsApp, intentando fallback...", e)
            }
        }

        openApp(whatsappPkg)
        handler.postDelayed({
            val target = if (contact.isNotBlank()) contact else phone
            if (tapNodeByText(target)) {
                handler.postDelayed({
                    typeText(message)
                    handler.postDelayed({ tapNodeByText("Enviar") || tapNodeByText("Send") }, 600)
                }, 1200)
            }
        }, 1800)
    }

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
        } catch (e: Exception) {
            Log.e(TAG, "Error realizando llamada", e)
        }
    }

    private fun answerCall() {
        val answered = MainActivity.instance?.answerPhoneCall() ?: false
        if (!answered) {
            tapNodeByText("Contestar") || tapNodeByText("Responder") || tapNodeByText("Answer")
        }
    }

    private fun readLatestNotification() {
        val text = JarvisNotificationListener.getLatestNotificationSummary()
        speak(text)
    }

    private fun replyLatestMessage(message: String) {
        val senderPkg = JarvisNotificationListener.latestMessagePackage
        val senderName = JarvisNotificationListener.latestSenderName

        if (senderPkg == "com.whatsapp" || senderPkg == "com.whatsapp.w4b") {
            sendWhatsApp(contact = senderName, phone = "", message = message)
        } else {
            sendSms(contact = senderName, phone = "", message = message)
        }
    }

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
            speak("Alarma configurada a las $hour:${minute.toString().padStart(2, '0')}")
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando alarma", e)
        }
    }

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
                Log.w(TAG, "Error en SmsManager directo, intentando por Intent...", e)
            }
        }

        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(cleanPhone)}")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(smsIntent) } catch (e: Exception) { Log.e(TAG, "Error abriendo app de SMS", e) }
    }

    private fun playYouTube(query: String) {
        val searchUrl = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(intent); speak("Reproduciendo $query en YouTube") } catch (e: Exception) { Log.e(TAG, "Error abriendo YouTube", e) }
    }

    private fun playSpotify(track: String) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra("query", track)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent); speak("Buscando $track en Spotify") } catch (e: Exception) { openApp("com.spotify.music") }
    }

    private fun controlMusic(command: String, track: String) {
        if (track.isNotBlank()) { playSpotify(track); return }
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        when (command) {
            "volume_up" -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_RAISE, 0)
            "volume_down" -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_LOWER, 0)
        }
    }

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
                label == target -> 100
                label.startsWith(target) -> 80
                label.contains(target) -> 60
                target.contains(label) && label.length > 2 -> 50
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = pkg
            }
        }
        return bestMatch
    }

    private fun normalizeForSearch(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{Mn}+"), "").trim().lowercase()
    }

    private fun openApp(appNameOrPackage: String) {
        val resolved = quickPackageShortcut(appNameOrPackage)
            ?: if (packageManager.getLaunchIntentForPackage(appNameOrPackage) != null) appNameOrPackage
            else findInstalledAppByLabel(appNameOrPackage)

        if (resolved == null) return

        val launchIntent = packageManager.getLaunchIntentForPackage(resolved)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    private fun closeApp() { performGlobalAction(GLOBAL_ACTION_HOME) }

    /** Tomar una foto directamente, sin que tengas que tocar el botón */
    private fun takePhoto() {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Abriendo cámara para la foto")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara", e)
        }
    }

    /**
     * Enciende/apaga la linterna. Esta SÍ es 100% automática (no requiere
     * tocar nada), porque el flash es hardware directo, sin restricciones
     * de privacidad de Android.
     */
    private fun toggleFlashlight() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
            speak(if (flashlightOn) "Linterna encendida" else "Linterna apagada")
        } catch (e: Exception) {
            Log.e(TAG, "Error controlando la linterna", e)
        }
    }

    /**
     * IMPORTANTE: desde Android 10, NINGUNA app (ni con permisos de
     * Accesibilidad) puede prender/apagar el WiFi de forma 100% silenciosa
     * por seguridad — es una restricción de Android, no un límite del
     * código. Lo más cercano es abrirte el panel rápido de WiFi para que
     * lo actives con UN solo toque, en vez de tener que navegar por Ajustes.
     */
    private fun openWifiPanel() {
        try {
            val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Aquí tienes el panel de WiFi, actívalo con un toque")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo panel de WiFi", e)
        }
    }

    /**
     * Igual que el WiFi: desde Android 13, encender/apagar Bluetooth por
     * código directo ya no está permitido por privacidad. Se abre la
     * pantalla de ajustes de Bluetooth para que lo actives con un toque.
     */
    private fun toggleBluetooth() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Aquí tienes el Bluetooth, actívalo con un toque")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo ajustes de Bluetooth", e)
        }
    }

    /** El modo avión SIEMPRE requiere confirmación manual del usuario en Android, sin excepción */
    private fun openAirplaneModeSettings() {
        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Aquí tienes el modo avión, actívalo con un toque")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo modo avión", e)
        }
    }

    /**
     * Esta SÍ es 100% automática, sin tocar nada — pero necesita que la
     * primera vez actives manualmente el permiso "Modificar ajustes del
     * sistema" (Android lo exige como acción de seguridad única, igual
     * que el de Accesibilidad). Una vez dado, funciona siempre por voz.
     */
    private fun setBrightness(level: Int) {
        try {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                speak("Necesito que actives el permiso de ajustes del sistema, solo esta vez")
                return
            }
            val clamped = level.coerceIn(1, 100)
            val brightnessValue = (clamped * 255 / 100)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessValue)
            speak("Brillo ajustado al $clamped por ciento")
        } catch (e: Exception) {
            Log.e(TAG, "Error ajustando brillo", e)
        }
    }

    private fun searchWeb(query: String) {
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(searchIntent)
    }

    private fun speak(text: String) { MainActivity.instance?.speakNative(text) }

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
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
