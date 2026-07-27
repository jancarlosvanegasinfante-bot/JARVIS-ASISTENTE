package com.jose.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQ_CODE = 2001
        var instance: MainActivity? = null
    }

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // 1. Forzar que la pantalla se encienda y desbloquee aunque esté apagada
        configureScreenWakeup()

        // 2. Mantener servicio vivo sin que el ahorro de batería de Android lo mate
        requestBatteryOptimizationExemption()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.loadUrl("https://jarvis-voz-asistente-production.up.railway.app")

        // 3. Solicitar TODOS los permisos de Android en pantalla
        requestAllRuntimePermissions()
        
        startWakeWordServiceIfReady()
        initTextToSpeech()

        intent?.let { handleIntentTrigger(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureScreenWakeup()
        handleIntentTrigger(intent)
    }

    /** Despierta y enciende la pantalla aun si el celular está bloqueado con pantalla apagada */
    private fun configureScreenWakeup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Jarvis:WakeLockTag"
            )
        }
        wakeLock?.acquire(3000) // Mantiene la pantalla encendida inmediatamente
    }

    /** Evita que el ahorro de energía nocturno o en segundo plano mate a Jarvis */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo solicitar la exención de batería", e)
                }
            }
        }
    }

    private fun handleIntentTrigger(intent: Intent) {
        if (intent.getBooleanExtra("wake_word_triggered", false)) {
            Log.d(TAG, "Activado por voz con pantalla apagada!")
            configureScreenWakeup()
            startNativeListening()
        }
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "CO")
            }
        }
    }

    fun speakNative(text: String) {
        if (tts == null) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    fun startNativeListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        runOnUiThread {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    webView.evaluateJavascript("window.onNativeListeningState && window.onNativeListeningState(true)", null)
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    val escaped = JSONObject.quote(text)
                    webView.evaluateJavascript("window.onNativeTranscript && window.onNativeTranscript($escaped, true)", null)
                    webView.evaluateJavascript("window.onNativeListeningState && window.onNativeListeningState(false)", null)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    val escaped = JSONObject.quote(text)
                    webView.evaluateJavascript("window.onNativeTranscript && window.onNativeTranscript($escaped, false)", null)
                }

                override fun onError(error: Int) {
                    webView.evaluateJavascript("window.onNativeListeningState && window.onNativeListeningState(false)", null)
                }

                override fun onEndOfSpeech() {
                    webView.evaluateJavascript("window.onNativeListeningState && window.onNativeListeningState(false)", null)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        }
    }

    fun stopNativeListening() {
        runOnUiThread {
            speechRecognizer?.stopListening()
        }
    }

    fun getRealContactsJson(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "[]"
        }
        val contactsArray = JSONArray()
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)

            val addedNames = HashSet<String>()
            while (it.moveToNext()) {
                val name = if (nameIndex >= 0) it.getString(nameIndex) else ""
                val number = if (numberIndex >= 0) it.getString(numberIndex) else ""
                val id = if (idIndex >= 0) it.getString(idIndex) else ""

                if (name.isNotBlank() && !addedNames.contains(name.lowercase())) {
                    addedNames.add(name.lowercase())
                    val obj = JSONObject().apply {
                        put("id", id)
                        put("name", name)
                        put("nickname", name)
                        put("phone", number)
                        put("hasWhatsapp", true)
                    }
                    contactsArray.put(obj)
                }
            }
        }
        return contactsArray.toString()
    }

    fun getInstalledAppsJson(): String {
        val appsArray = JSONArray()
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pkgManager = packageManager
        val resolvedList = pkgManager.queryIntentActivities(mainIntent, 0)

        for (resolveInfo in resolvedList) {
            val appName = resolveInfo.loadLabel(pkgManager).toString()
            val packageName = resolveInfo.activityInfo.packageName
            val obj = JSONObject().apply {
                put("name", appName)
                put("packageName", packageName)
            }
            appsArray.put(obj)
        }
        return appsArray.toString()
    }

    fun answerPhoneCall(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error contestando llamada", e)
            false
        }
    }

    /** Solicita TODOS Y CADA UNO de los permisos requeridos por Android */
    private fun requestAllRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQ_CODE)
        }

        // Permiso especial de Ventana flotante / Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        // Permiso especial de Accesibilidad
        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun startWakeWordServiceIfReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(this, WakeWordService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    class AndroidBridge(private val activity: MainActivity) {

        @JavascriptInterface
        fun executeAction(actionJson: String) {
            JarvisAccessibilityService.instance?.executeAction(actionJson)
        }

        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean {
            return activity.isAccessibilityServiceEnabled()
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun speak(text: String) {
            activity.speakNative(text)
        }

        @JavascriptInterface
        fun startListening() {
            activity.startNativeListening()
        }

        @JavascriptInterface
        fun stopListening() {
            activity.stopNativeListening()
        }

        @JavascriptInterface
        fun getContacts(): String {
            return activity.getRealContactsJson()
        }

        @JavascriptInterface
        fun getInstalledApps(): String {
            return activity.getInstalledAppsJson()
        }

        @JavascriptInterface
        fun getLatestNotification(): String {
            return JarvisNotificationListener.getLatestNotificationSummary()
        }

        @JavascriptInterface
        fun answerPhoneCall(): Boolean {
            return activity.answerPhoneCall()
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        instance = null
        super.onDestroy()
    }
}
