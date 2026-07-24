package com.jose.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * MainActivity
 *
 * Carga tu app React (build de Vite) dentro de un WebView, y expone un
 * puente JS <-> Kotlin llamado "AndroidBridge".
 *
 * IMPORTANTE: el WebView de Android NO soporta las APIs de voz del
 * navegador (SpeechRecognition ni speechSynthesis) — por eso aquí se
 * implementan con las APIs NATIVAS de Android (SpeechRecognizer y
 * TextToSpeech) y se exponen al JS por el mismo puente.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "CO")
            }
        }
    }

    /** Habla el texto usando el motor TTS nativo de Android */
    private fun speakNative(text: String) {
        if (tts == null) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    /** Inicia el reconocimiento de voz nativo y manda los resultados al JS */
    private fun startNativeListening() {
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
                    val escaped = org.json.JSONObject.quote(text)
                    webView.evaluateJavascript("window.onNativeTranscript && window.onNativeTranscript($escaped, true)", null)
                    webView.evaluateJavascript("window.onNativeListeningState && window.onNativeListeningState(false)", null)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    val escaped = org.json.JSONObject.quote(text)
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

    private fun stopNativeListening() {
        runOnUiThread {
            speechRecognizer?.stopListening()
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001)
        }

        // Servicio de Accesibilidad: no se puede pedir por código, hay que
        // llevar al usuario a Ajustes la primera vez.
        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
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

    /** Puente expuesto a JavaScript dentro del WebView */
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
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
