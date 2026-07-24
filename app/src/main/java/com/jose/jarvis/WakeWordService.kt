package com.jose.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import org.json.JSONObject

/**
 * WakeWordService
 *
 * Corre en primer plano (con notificación fija, obligatoria por Android)
 * escuchando el micrófono TODO el tiempo con Vosk (motor offline).
 * En cuanto detecta cualquiera de las variantes fonéticas ("jan", "yan", "juan", "jean", "yean", "jarvis"),
 * enciende la pantalla automáticamente (WakeLock) y dispara MainActivity para iniciar la escucha de comando.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "jarvis_wakeword_channel"
        private const val NOTIF_ID = 42
        private val WAKE_WORDS = listOf("jan", "yan", "juan", "jean", "yean", "jarvis")
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        initWakeLock()
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun initWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "JarvisApp:WakeUpLock"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando WakeLock", e)
        }
    }

    private fun wakeUpScreen() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(5000) // Mantener encendida 5 segundos
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al encender pantalla", e)
        }
    }

    private fun loadModel() {
        StorageService.unpack(this, "model-es", "model",
            { loadedModel ->
                model = loadedModel
                startListening()
            },
            { exception ->
                Log.e(TAG, "Error cargando modelo Vosk", exception)
            }
        )
    }

    private fun startListening() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            Log.d(TAG, "Jarvis escuchando 24/7 con palabras clave: $WAKE_WORDS...")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escucha Vosk", e)
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        val text = JSONObject(hypothesis).optString("partial", "").lowercase()
        for (word in WAKE_WORDS) {
            if (text.contains(word)) {
                Log.d(TAG, "Coincidencia de activación fonética detectada ('$word'): $text")
                onWakeWordDetected(word)
                break
            }
        }
    }

    override fun onResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        Log.e(TAG, "Error de reconocimiento Vosk", exception)
    }

    override fun onTimeout() {
        speechService?.startListening(this)
    }

    private fun onWakeWordDetected(matchedWord: String) {
        wakeUpScreen()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("wake_word_triggered", true)
            putExtra("matched_word", matchedWord)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis Escuchando", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Activo 24/7")
            .setContentText("Di \"Jan\", \"Yan\", \"Juan\" o \"Jarvis\" para activar...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
