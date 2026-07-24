package com.jose.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
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

/**
 * MainActivity
 *
 * Carga la app React (build de Vite / Railway) dentro de un WebView y expone
 * un puente JS <-> Kotlin completo llamado "AndroidBridge".
 *
 * Proporciona:
 *  - Reconocimiento y síntesis de voz nativos.
 *  - Lectura de agenda de contactos reales del celular.
 *  - Obtención de la lista completa de apps instaladas.
 *  - Encendido automático de pantalla al despertar por voz ("Jan").
 *  - Respuesta de llamadas telefónicas y lectura de notificaciones para Modo Moto.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQ_CODE = 2001
    }

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureScreenWakeup()

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

        requestRuntimePermissions()
        startWakeWordServiceIfReady()
        initTextToSpeech()

        handleIntentTrigger(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureScreenWakeup()
        intent?.let { handleIntentTrigger(it) }
    }

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
    }

    private fun handleIntentTrigger(intent: Intent) {
        if (intent.getBooleanExtra("wake_word_triggered", false)) {
            Log.d(TAG, "Jarvis activado por voz en segundo plano!")
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

    /** Habla el texto usando el motor TTS nativo */
    fun speakNative(text: String) {
        if (tts == null) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    /** Inicia el reconocimiento de voz nativo y manda el resultado al WebView JS */
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

    /** Lee la lista real de contactos guardados en el teléfono */
    fun getRealContactsJson(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "[]"
        }
        val contactsArray = JSONArray()
        val contentResolver = contentResolver
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

    /** Obtiene la lista completa de aplicaciones instaladas en el dispositivo */
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

    /** Responde a una llamada entrante activa sin tocar el teléfono */
    fun answerPhoneCall(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permiso ANSWER_PHONE_CALLS denegado")
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

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQ_CODE)
        }

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

    /** Puente JavaScript expuesto en WebView */
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
        super.onDestroy()
    }
}
