package com.jose.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
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
                "set_alarm" -> setAlarm(time = params.optString("time"), title = params.optString("title", "Alarma Jarvis"))
                "set_timer" -> setTimer(
                    seconds = params.optInt("seconds", params.optInt("minutes", 1) * 60),
                    title = params.optString("title", "Temporizador Jarvis")
                )
                "send_sms" -> sendSms(contact = params.optString("contact"), phone = params.optString("phoneNumber"), message = params.optString("message"))
                "play_youtube" -> playYouTube(params.optString("query"))
                "play_spotify" -> playSpotify(params.optString("track"))
                "open_app" -> openApp(params.optString("appName"), params.optString("packageName"))
                "close_app" -> closeApp()
                "go_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "search_web" -> searchWeb(params.optString("query"))
                "control_music" -> controlMusic(command = params.optString("command"), track = params.optString("track"))
                "read_notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "tap_text" -> tapNodeByText(json.optString("text"))
                "type_text" -> typeText(json.optString("text"))

                "take_photo" -> takePhoto()
                "open_camera" -> openApp("com.android.camera", "")
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
        val targetPhone = if (phone.isNotBlank()) phone else (MainActivity.instance?.resolveContactPhone(contact) ?: "")
        val cleanPhone = targetPhone.replace(Regex("[^0-9]"), "")

        if (cleanPhone.length >= 7) {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(whatsappPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                handler.postDelayed({ retryTapSend(6, 700) }, 2200)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Fallo Deep Link WhatsApp, realizando fallback por interfaz...", e)
            }
        }

        openApp(whatsappPkg, "")
        handler.postDelayed({
            val target = if (contact.isNotBlank()) contact else phone
            if (tapNodeByText(target)) {
                handler.postDelayed({
                    typeText(message)
                    handler.postDelayed({ retryTapSend(6, 700) }, 800)
                }, 1200)
            }
        }, 1800)
    }

    private fun makeCall(phone: String, contact: String) {
        var targetNum = phone.replace(Regex("[^0-9+]"), "")
        if (targetNum.isBlank() && contact.isNotBlank()) {
            targetNum = MainActivity.instance?.resolveContactPhone(contact) ?: ""
        }
        if (targetNum.isBlank()) {
            speak("No encontré el número de teléfono para $contact")
            return
        }

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
            speak("Llamando a ${if (contact.isNotBlank()) contact else targetNum}")
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

    private fun setTimer(seconds: Int, title: String) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            val mins = seconds / 60
            val secs = seconds % 60
            val msg = if (mins > 0) "$mins minutos" + (if (secs > 0) " con $secs segundos" else "") else "$secs segundos"
            speak("Temporizador configurado por $msg")
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando temporizador", e)
        }
    }

    private fun sendSms(contact: String, phone: String, message: String) {
        var cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        if (cleanPhone.isBlank() && contact.isNotBlank()) {
            cleanPhone = MainActivity.instance?.resolveContactPhone(contact) ?: ""
        }
        if (cleanPhone.isNotBlank()) {
            try {
                @Suppress("DEPRECATION")
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(cleanPhone, null, message, null, null)
                speak("Mensaje SMS enviado")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Error en SmsManager directo, usando intent...", e)
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
        try { startActivity(intent); speak("Buscando $track en Spotify") } catch (e: Exception) { openApp("com.spotify.music", "") }
    }

    private fun controlMusic(command: String, track: String) {
        if (track.isNotBlank()) { playSpotify(track); return }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        when (command) {
            "play", "pause", "toggle" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev", "previous" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun sendMediaKeyEvent(keyCode: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
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

    private fun openApp(appNameOrPackage: String, packageName: String) {
        val resolved = when {
            packageName.isNotBlank() && packageManager.getLaunchIntentForPackage(packageName) != null -> packageName
            else -> quickPackageShortcut(appNameOrPackage)
                ?: if (packageManager.getLaunchIntentForPackage(appNameOrPackage) != null) appNameOrPackage
                else findInstalledAppByLabel(appNameOrPackage)
        }

        if (resolved == null) {
            speak("No pude encontrar la aplicación $appNameOrPackage")
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(resolved)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    private fun closeApp() { performGlobalAction(GLOBAL_ACTION_HOME) }

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

    private fun openWifiPanel() {
        try {
            val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Aquí tienes el panel de WiFi")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo panel WiFi", e)
        }
    }

    private fun toggleBluetooth() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Aquí tienes los ajustes de Bluetooth")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo ajustes Bluetooth", e)
        }
    }

    private fun openAirplaneModeSettings() {
        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Aquí tienes los ajustes de Modo Avión")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo modo avión", e)
        }
    }

    private fun setBrightness(level: Int) {
        try {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                speak("Necesito el permiso para modificar ajustes del sistema")
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

    // Busca recursivamente un botón por texto o descripción de contenido (íconos sin texto visible,
    // como el botón "Enviar" con forma de avión de papel en WhatsApp).
    private fun tapNodeByAnyMatch(vararg keywords: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val lowerKeywords = keywords.map { it.lowercase() }
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val txt = node.text?.toString()?.lowercase() ?: ""
            if (lowerKeywords.any { desc.contains(it) || txt.contains(it) }) {
                val clickable = findClickableParent(node)
                if (clickable != null) return clickable
            }
            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val target = search(root)
        return if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } else false
    }

    // Reintenta encontrar y tocar el botón de enviar varias veces (la UI puede tardar en cargar).
    private fun retryTapSend(attemptsLeft: Int, delayMs: Long) {
        if (attemptsLeft <= 0) return
        val tapped = tapNodeByText("Enviar") || tapNodeByText("Send") || tapNodeByAnyMatch("enviar", "send")
        if (!tapped) {
            handler.postDelayed({ retryTapSend(attemptsLeft - 1, delayMs) }, delayMs)
        }
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
