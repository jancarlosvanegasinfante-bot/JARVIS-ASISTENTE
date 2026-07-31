package com.jose.jarvis

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.OutputStream

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
                "send_email" -> sendEmail(
                    to = params.optString("to"),
                    subject = params.optString("subject"),
                    body = params.optString("body")
                )
                "send_facebook" -> sendFacebookMessage(
                    contact = params.optString("contact"),
                    message = params.optString("message")
                )
                "open_app_chat" -> openAppAndChat(
                    appName = params.optString("appName"),
                    itemIndex = params.optInt("itemIndex", 0),
                    message = params.optString("message")
                )
                "get_battery_status" -> speakBatteryStatus()
                "toggle_silent_mode" -> toggleSilentMode(params.optString("mode", "silent"))
                "read_screen" -> readScreenContentAloud()
                "open_maps_directions" -> openMapsDirections(
                    destination = params.optString("destination"),
                    app = params.optString("app", "maps")
                )
                "adjust_volume" -> adjustVolume(params.optString("direction", "up"))
                "read_recent_messages" -> readRecentMessages()
                "record_video" -> recordVideo()
                "stop_video_recording" -> { tapShutterButton(); speak("Video guardado") }
                "tell_time" -> tellCurrentTimeDate()
                "toggle_gps" -> openLocationSettings()
                "lock_screen" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    else speak("Tu versión de Android no permite bloquear la pantalla automáticamente")
                }
                "clear_notifications" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    else performGlobalAction(GLOBAL_ACTION_HOME)
                }
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
                "take_screenshot" -> takeScreenshotNow()
                "start_screen_recording" -> startScreenRecording()
                "stop_screen_recording" -> stopScreenRecording()
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

    private fun sendEmail(to: String, subject: String, body: String) {
        val targetEmail = if (to.contains("@")) to else (MainActivity.instance?.resolveContactEmail(to) ?: "")
        if (targetEmail.isBlank()) {
            speak("No encontré el correo electrónico de $to")
            return
        }
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            setPackage("com.google.android.gm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(emailIntent)
            speak("Enviando correo a $to")
            // Gmail abre el borrador ya con destinatario, asunto y cuerpo listos; solo falta tocar enviar.
            handler.postDelayed({ retryTapEmailSend(8, 600) }, 1500)
        } catch (e: Exception) {
            Log.w(TAG, "Gmail no disponible, intentando cliente de correo genérico...", e)
            try {
                val generic = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(generic)
                handler.postDelayed({ retryTapEmailSend(8, 600) }, 1500)
            } catch (e2: Exception) {
                Log.e(TAG, "Error abriendo cualquier cliente de correo", e2)
            }
        }
    }

    private fun retryTapEmailSend(attemptsLeft: Int, delayMs: Long) {
        if (attemptsLeft <= 0) return
        val root = rootInActiveWindow
        val tapped = if (root != null) {
            val gmailSendId = root.findAccessibilityNodeInfosByViewId("com.google.android.gm:id/send")
            if (gmailSendId.isNotEmpty()) {
                (findClickableParent(gmailSendId[0]) ?: gmailSendId[0]).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } else {
                tapNodeByAnyMatch("enviar", "send")
            }
        } else false
        if (!tapped) {
            handler.postDelayed({ retryTapEmailSend(attemptsLeft - 1, delayMs) }, delayMs)
        }
    }

    // Abre Messenger de Facebook, busca el contacto, escribe el mensaje y lo envía automáticamente.
    private fun sendFacebookMessage(contact: String, message: String) {
        val messengerPkg = "com.facebook.orca"
        if (packageManager.getLaunchIntentForPackage(messengerPkg) == null) {
            speak("No tengo Messenger instalado para enviarle el mensaje a $contact")
            return
        }
        openApp(messengerPkg, "")
        handler.postDelayed({
            if (tapNodeByText(contact)) {
                handler.postDelayed({
                    typeText(message)
                    handler.postDelayed({ retryTapSend(8, 600) }, 800)
                }, 1400)
            } else {
                Log.w(TAG, "No se encontró el contacto '$contact' en Messenger")
            }
        }, 2000)
    }

    private fun playYouTube(query: String) {
        val searchUrl = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            startActivity(intent)
            speak("Reproduciendo $query en YouTube")
            // Toca automáticamente el primer resultado para que empiece a reproducir sin intervención.
            handler.postDelayed({ retryTapFirstYouTubeResult(8, 600) }, 1500)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo YouTube", e)
        }
    }

    // Busca el primer video en la lista de resultados de YouTube y lo toca (miniatura o su contenedor clickeable).
    private fun tapFirstYouTubeResult(): Boolean {
        val root = rootInActiveWindow ?: return false
        val idCandidates = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/video_thumbnail_container"
        )
        for (id in idCandidates) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val clickable = findClickableParent(nodes[0]) ?: nodes[0]
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        // Fallback genérico: busca el primer nodo clickeable con imagen dentro de la lista de resultados.
        fun findFirstClickableImage(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.className?.contains("ImageView") == true) {
                val clickable = findClickableParent(node)
                if (clickable != null) return clickable
            }
            for (i in 0 until node.childCount) {
                val found = findFirstClickableImage(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val fallback = findFirstClickableImage(root)
        return if (fallback != null) {
            fallback.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } else false
    }

    private fun retryTapFirstYouTubeResult(attemptsLeft: Int, delayMs: Long) {
        if (attemptsLeft <= 0) return
        if (!tapFirstYouTubeResult()) {
            handler.postDelayed({ retryTapFirstYouTubeResult(attemptsLeft - 1, delayMs) }, delayMs)
        }
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
            speak("Tomando la foto")
            // Toca automáticamente el botón de disparo de la cámara (varía según la app, se intenta por ids comunes y por descripción).
            handler.postDelayed({ retryTapShutter(10, 500) }, 1200)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara", e)
        }
    }

    private fun tapShutterButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val idCandidates = listOf(
            "com.android.camera:id/shutter_button",
            "com.android.camera2:id/shutter_button",
            "com.google.android.GoogleCamera:id/shutter_button",
            "com.miui.camera:id/btn_shutter",
        )
        for (id in idCandidates) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val clickable = findClickableParent(nodes[0]) ?: nodes[0]
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return tapNodeByAnyMatch("shutter", "capturar", "capture", "tomar foto", "photo capture")
    }

    private fun retryTapShutter(attemptsLeft: Int, delayMs: Long) {
        if (attemptsLeft <= 0) return
        if (!tapShutterButton()) {
            handler.postDelayed({ retryTapShutter(attemptsLeft - 1, delayMs) }, delayMs)
        }
    }

    private fun startScreenRecording() {
        try {
            speak("Toca Empezar ahora para comenzar a grabar la pantalla")
            val intent = Intent(this, ScreenRecordRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error solicitando grabación de pantalla", e)
        }
    }

    private fun stopScreenRecording() {
        try {
            val stopIntent = Intent(this, ScreenRecordService::class.java).apply { action = "STOP" }
            startService(stopIntent)
            speak("Grabación de pantalla guardada")
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo grabación de pantalla", e)
        }
    }

    private fun takeScreenshotNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            speak("Tu versión de Android es muy antigua para tomar capturas automáticas")
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            result.hardwareBuffer.close()
                            if (bitmap != null) {
                                saveBitmapToGallery(bitmap)
                                speak("Captura de pantalla guardada")
                            } else {
                                speak("No pude procesar la captura de pantalla")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error procesando captura de pantalla", e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Error tomando captura de pantalla, código: $errorCode")
                        speak("No pude tomar la captura de pantalla")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error solicitando captura de pantalla", e)
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "Jarvis_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        var outputStream: OutputStream? = null
        try {
            outputStream = resolver.openOutputStream(uri)
            outputStream?.let { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando captura en galería", e)
        } finally {
            outputStream?.close()
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

    // Abre cualquier app (Claude, Telegram, Instagram, etc.), opcionalmente toca el N-ésimo elemento
    // de una lista (por ejemplo la conversación #2), escribe un mensaje y lo envía. Experimental:
    // como cada app tiene su propia interfaz, puede necesitar ajustes según la app real.
    private fun openAppAndChat(appName: String, itemIndex: Int, message: String) {
        openApp(appName, "")
        handler.postDelayed({
            if (itemIndex > 0) {
                tapNthClickableItem(itemIndex)
                handler.postDelayed({ typeAndSendInCurrentScreen(message) }, 1500)
            } else {
                typeAndSendInCurrentScreen(message)
            }
        }, 2200)
    }

    private fun typeAndSendInCurrentScreen(message: String) {
        if (message.isBlank()) return
        val editable = findFirstEditableNode(rootInActiveWindow)
        if (editable != null) {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            handler.postDelayed({ retryTapSend(8, 600) }, 700)
        } else {
            Log.w(TAG, "No se encontró un campo de texto editable en la pantalla actual")
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // Recorre el árbol de accesibilidad en orden y toca el N-ésimo nodo clickeable con texto visible
    // (útil para abrir "la segunda conversación", "el tercer chat", etc.).
    private fun tapNthClickableItem(index: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        var counter = 0
        var target: AccessibilityNodeInfo? = null

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || target != null) return
            val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            if (node.isClickable && hasText) {
                counter++
                if (counter == index) {
                    target = node
                    return
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
                if (target != null) return
            }
        }
        traverse(root)
        return if (target != null) {
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } else false
    }

    private fun speakBatteryStatus() {
        try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            speak("La batería está al $level por ciento")
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo estado de batería", e)
        }
    }

    private fun toggleSilentMode(mode: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                speak("Necesito permiso de acceso a notificaciones para cambiar el modo de sonido. Actívalo y prueba de nuevo.")
                return
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mode.lowercase()) {
                "silent", "silencio" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    speak("Celular en silencio")
                }
                "vibrate", "vibrar" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    speak("Celular en vibración")
                }
                else -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    speak("Sonido del celular normalizado")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cambiando el modo de sonido", e)
        }
    }

    // Lee en voz alta el texto visible en pantalla (útil para "qué dice aquí", "léeme esto").
    private fun readScreenContentAloud() {
        val root = rootInActiveWindow
        if (root == null) {
            speak("No puedo leer la pantalla en este momento")
            return
        }
        val collected = StringBuilder()
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && collected.length < 500) {
                collected.append(text).append(". ")
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        traverse(root)
        if (collected.isEmpty()) {
            speak("No encontré texto visible en la pantalla")
        } else {
            speak(collected.toString().take(500))
        }
    }

    private fun openMapsDirections(destination: String, app: String) {
        if (destination.isBlank()) {
            speak("¿A dónde quieres que te lleve?")
            return
        }
        val encoded = Uri.encode(destination)
        if (app.lowercase().contains("waze")) {
            val wazeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=$encoded&navigate=yes")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(wazeIntent)
                speak("Abriendo ruta a $destination en Waze")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Waze no disponible, usando Google Maps...", e)
            }
        }
        val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(mapsIntent)
            speak("Abriendo ruta a $destination en Maps")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo Maps", e)
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(fallback) } catch (e2: Exception) { Log.e(TAG, "Error abriendo Maps web", e2) }
        }
    }

    private fun adjustVolume(direction: String) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val flag = android.media.AudioManager.FLAG_SHOW_UI
            when (direction.lowercase()) {
                "up", "sube", "subir", "arriba" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flag)
                "down", "baja", "bajar", "abajo" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flag)
                "mute", "silencio" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flag)
                "max", "maximo", "máximo" -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, flag)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ajustando volumen", e)
        }
    }

    private fun readRecentMessages() {
        val summary = JarvisNotificationListener.getRecentMessagesSummary()
        speak(summary)
    }

    // Abre la cámara en modo video y toca automáticamente el botón de grabar (reutiliza ids comunes de shutter/record).
    private fun recordVideo() {
        try {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak("Grabando video")
            handler.postDelayed({ retryTapShutter(10, 500) }, 1200)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara de video", e)
        }
    }

    private fun tellCurrentTimeDate() {
        val now = java.util.Calendar.getInstance()
        val formatter = java.text.SimpleDateFormat("EEEE d 'de' MMMM, h:mm a", java.util.Locale("es", "ES"))
        speak("Son las ${formatter.format(now.time)}")
    }

    private fun openLocationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo ajustes de ubicación", e)
        }
    }

    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
