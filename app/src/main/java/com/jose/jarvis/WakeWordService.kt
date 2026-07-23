package com.jose.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
 * escuchando el micrófono TODO el tiempo con Vosk (motor offline, sin
 * cuenta ni API key). En cuanto detecta la palabra "jan" en la transcripción
 * parcial, dispara MainActivity para iniciar el flujo completo de comando.
 *
 * NOTA: la primera vez, Vosk necesita el modelo de español descargado una
 * sola vez (ver README -> "Descargar modelo Vosk"). El modelo pesa ~50MB y
 * se empaqueta dentro de assets/ o se descarga en el primer arranque.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "jarvis_wakeword_channel"
        private const val NOTIF_ID = 42
        private const val WAKE_WORD = "jan"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // si Android mata el servicio, lo reinicia
    }

    private fun loadModel() {
        // Carga el modelo de español desde assets/model-es (ver README)
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
            Log.d(TAG, "Jarvis escuchando 24/7...")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escucha", e)
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        val text = JSONObject(hypothesis).optString("partial", "").lowercase()
        if (text.contains(WAKE_WORD)) {
            Log.d(TAG, "Wake word detectada: $text")
            onWakeWordDetected()
        }
    }

    override fun onResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        Log.e(TAG, "Error de reconocimiento", exception)
    }
    override fun onTimeout() {
        // Vosk a veces hace timeout en silencios largos; reiniciamos escucha
        speechService?.startListening(this)
    }

    private fun onWakeWordDetected() {
        // Lanza MainActivity al frente (o si ya está abierta, le manda un
        // evento) para que tu VoiceModal.tsx tome el control y grabe el
        // comando completo que sigue después de "Jan".
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("wake_word_triggered", true)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis escuchando", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis activo")
            .setContentText("Escuchando el comando \"Jan\"...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
