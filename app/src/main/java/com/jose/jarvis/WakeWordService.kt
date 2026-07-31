package com.jose.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "jarvis_wakeword_channel"
        private const val NOTIF_ID = 42
        private val WAKE_WORDS = listOf("jan", "yan", "juan", "jean", "yean", "jarvis")
        private const val COOLDOWN_MS = 4000L
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTriggerTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPausedForCommand = false

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
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "JarvisApp:WakeUpLock"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando WakeLock", e)
        }
    }

    private fun wakeUpScreen() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(7000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encendiendo pantalla", e)
        }
    }

    private fun loadModel() {
        StorageService.unpack(this, "model-es", "model",
            { loadedModel ->
                model = loadedModel
                startListening()
            },
            { exception ->
                Log.e(TAG, "Error cargando modelo de voz Vosk", exception)
            }
        )
    }

    private fun startListening() {
        if (isPausedForCommand) return
        try {
            speechService?.stop()
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            Log.d(TAG, "Jarvis escuchando 24/7 activo con palabras clave: $WAKE_WORDS...")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escucha Vosk", e)
            scheduleRestartListening(3000)
        }
    }

    private fun scheduleRestartListening(delayMs: Long) {
        mainHandler.postDelayed({
            isPausedForCommand = false
            startListening()
        }, delayMs)
    }

    private fun checkTextForWakeWord(rawText: String) {
        if (isPausedForCommand) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < COOLDOWN_MS) return

        val text = rawText.lowercase()
        for (word in WAKE_WORDS) {
            if (text.contains(word)) {
                lastTriggerTime = now
                Log.d(TAG, "Activación por palabra clave detectada ('$word'): $text")
                onWakeWordDetected(word)
                break
            }
        }
    }

    private var lastNotifUpdate: Long = 0

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        val text = JSONObject(hypothesis).optString("partial", "")
        if (text.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastNotifUpdate > 1500) {
                lastNotifUpdate = now
                updateDebugNotification(text)
            }
        }
        checkTextForWakeWord(text)
    }

    private fun updateDebugNotification(heardText: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis escuchando...")
                .setContentText("Oí: \"$heardText\"")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando notificación de debug", e)
        }
    }

    override fun onResult(hypothesis: String?) {
        hypothesis ?: return
        val text = JSONObject(hypothesis).optString("text", "")
        checkTextForWakeWord(text)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Error en Vosk, reiniciando escucha...", exception)
        scheduleRestartListening(2000)
    }

    override fun onTimeout() {
        startListening()
    }

    private fun onWakeWordDetected(matchedWord: String) {
        isPausedForCommand = true
        speechService?.stop()

        wakeUpScreen()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("wake_word_triggered", true)
            putExtra("matched_word", matchedWord)
        }
        startActivity(intent)

        // Reiniciar escucha 24/7 automáticamente tras 6 segundos
        scheduleRestartListening(6000)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis Escuchando", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Activo 24/7")
            .setContentText("Di \"Jan\" o \"Jarvis\" para activar...")
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
