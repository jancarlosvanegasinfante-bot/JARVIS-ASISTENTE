package com.jose.jarvis

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import java.util.Locale

class FloatingWidgetService : Service() {

    companion object {
        private const val TAG = "FloatingWidgetService"
        private const val IDLE_TIMEOUT_MS = 4000L // 4 segundos para ocultarse
    }

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    // Contenedor principal y elementos visuales
    private lateinit var rootContainer: FrameLayout
    private lateinit var bubbleBg: FrameLayout
    private lateinit var micIcon: ImageView

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isDragging = false

    // Posiciones y estado de snapping
    private var screenWidth = 0
    private var screenHeight = 0
    private var widgetWidth = 0
    private var isSnappedLeft = false
    private var isMinimized = false

    // Timer de inactividad
    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { minimizeWidget() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenDimensions()

        // Inicializar Speech Recognizer
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        createFloatingWidget()
        resetIdleTimer()
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        // El widget medirá 64dp aprox (180px en pantallas estándar de alta densidad)
        widgetWidth = (64 * resources.displayMetrics.density).toInt()
    }

    private fun createFloatingWidget() {
        val density = resources.displayMetrics.density
        val size = (64 * density).toInt()

        // LayoutParams para superposición en cualquier pantalla
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            size, size,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - (16 * density).toInt() // Lado derecho inicial
            y = screenHeight / 3
        }

        // 1. Contenedor Raíz
        rootContainer = FrameLayout(this)

        // 2. Fondo de la Burbuja Circular con Borde Brillante (Glow Estilo Web)
        bubbleBg = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = getThemeDrawable(
                solidColor = 0xFA0E1017.toInt(), // Deep Dark Indigo
                strokeColor = 0x8800F2FF.toInt(), // Cyan semi-transparente
                strokeWidthDp = 2
            )
        }

        // 3. Icono del Micrófono / Asistente
        micIcon = ImageView(this).apply {
            val iconSize = (28 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            // Usamos el micrófono estándar de Android que siempre existe
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(0xFF00F2FF.toInt()) // Icono color Cian
        }

        bubbleBg.addView(micIcon)
        rootContainer.addView(bubbleBg)

        // Registrar Gestos táctiles, arrastre y toques rápidos
        setupTouchListener()

        windowManager.addView(rootContainer, layoutParams)
    }

    // Genera formas circulares estilizadas dinámicamente sin requerir XMLs de drawable
    private fun getThemeDrawable(solidColor: Int, strokeColor: Int, strokeWidthDp: Int): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(solidColor)
            setStroke((strokeWidthDp * density).toInt(), strokeColor)
        }
    }

    private fun setupTouchListener() {
        rootContainer.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        restoreWidgetFromMinimization()
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        stopIdleTimer()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        // Umbral para considerarse arrastre
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true
                        }

                        if (isDragging) {
                            layoutParams.x = initialX + deltaX
                            layoutParams.y = initialY + deltaY
                            windowManager.updateViewLayout(rootContainer, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            snapToEdge()
                        } else {
                            // Si fue un toque corto y rápido, activa la escucha sin abrir la app
                            toggleListeningState()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // Alternar el estado de escucha nativa de Jarvis en segundo plano
    private fun toggleListeningState() {
        if (isListening) {
            stopSpeechRecognition()
        } else {
            startSpeechRecognition()
        }
    }

    private fun startSpeechRecognition() {
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer no está disponible en este dispositivo.")
            return
        }

        isListening = true
        stopIdleTimer()

        // Cambiar diseño visual a: "Modo Escuchando" (Verde esmeralda / Cian brillante)
        bubbleBg.background = getThemeDrawable(
            solidColor = 0xFF052214.toInt(), // Deep Dark Emerald
            strokeColor = 0xFF10B981.toInt(), // Emerald Green
            strokeWidthDp = 3
        )
        micIcon.setColorFilter(0xFF10B981.toInt())

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // Notificar a la webview (si existe) que empezó a escuchar
                MainActivity.instance?.let { activity ->
                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript("window.onNativeListeningState && window.onNativeListeningState(true)", null)
                    }
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.e(TAG, "Error en reconocimiento nativo: $error")
                setToIdleState()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                
                if (text.isNotBlank()) {
                    // Pasar a estado "Procesando" (Ámbar)
                    bubbleBg.background = getThemeDrawable(
                        solidColor = 0xFF241705.toInt(), // Dark Amber
                        strokeColor = 0xFFF59E0B.toInt(), // Golden Amber
                        strokeWidthDp = 3
                    )
                    micIcon.setColorFilter(0xFFF59E0B.toInt())

                    // Inyectar el texto directamente en el cerebro de Jarvis (WebView activa)
                    MainActivity.instance?.let { activity ->
                        activity.runOnUiThread {
                            val escaped = JSONObject.quote(text)
                            activity.webView.evaluateJavascript("window.onNativeTranscript && window.onNativeTranscript($escaped, true)", null)
                        }
                    }
                }
                
                // Regresar a inactivo tras procesar
                handler.postDelayed({ setToIdleState() }, 1000)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    MainActivity.instance?.let { activity ->
                        activity.runOnUiThread {
                            val escaped = JSONObject.quote(text)
                            activity.webView.evaluateJavascript("window.onNativeTranscript && window.onNativeTranscript($escaped, false)", null)
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun stopSpeechRecognition() {
        speechRecognizer?.stopListening()
        setToIdleState()
    }

    private fun setToIdleState() {
        isListening = false
        bubbleBg.background = getThemeDrawable(
            solidColor = 0xFA0E1017.toInt(), // Deep Dark Indigo
            strokeColor = 0x8800F2FF.toInt(), // Cyan
            strokeWidthDp = 2
        )
        micIcon.setColorFilter(0xFF00F2FF.toInt())
        
        // Notificar a la webview que dejó de escuchar
        MainActivity.instance?.let { activity ->
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("window.onNativeListeningState && window.onNativeListeningState(false)", null)
            }
        }
        
        resetIdleTimer()
    }

    // Animación de Snapping suave al soltar el botón
    private fun snapToEdge() {
        updateScreenDimensions()
        val currentX = layoutParams.x
        val midPoint = screenWidth / 2
        val targetX = if (currentX + (widgetWidth / 2) < midPoint) {
            isSnappedLeft = true
            0
        } else {
            isSnappedLeft = false
            screenWidth - widgetWidth
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 350
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                try {
                    windowManager.updateViewLayout(rootContainer, layoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "Error actualizando vista al soltar", e)
                }
            }
        }
        animator.start()
        resetIdleTimer()
    }

    // Efecto de minimizado elegante e inactividad (Se desvanece y se esconde a la mitad)
    private fun minimizeWidget() {
        if (isListening || isDragging) return
        isMinimized = true

        val currentX = layoutParams.x
        // Si está a la izquierda, lo esconde -32dp. Si está a la derecha, lo esconde +32dp.
        val hideOffset = (28 * resources.displayMetrics.density).toInt()
        val targetX = if (isSnappedLeft) -hideOffset else screenWidth - widgetWidth + hideOffset

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 400
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                try {
                    windowManager.updateViewLayout(rootContainer, layoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "Error actualizando vista al minimizar", e)
                }
            }
        }

        // Cambiar la opacidad al 35%
        rootContainer.alpha = 0.35f
        animator.start()
    }

    // Restauración inmediata al tocarlo
    private fun restoreWidgetFromMinimization() {
        if (!isMinimized) return
        isMinimized = false

        val currentX = layoutParams.x
        val targetX = if (isSnappedLeft) 0 else screenWidth - widgetWidth

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 250
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                try {
                    windowManager.updateViewLayout(rootContainer, layoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "Error actualizando vista al restaurar", e)
                }
            }
        }

        // Opacidad de regreso al 100%
        rootContainer.alpha = 1.0f
        animator.start()
    }

    private fun resetIdleTimer() {
        stopIdleTimer()
        handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private fun stopIdleTimer() {
        handler.removeCallbacks(idleRunnable)
    }

    override fun onDestroy() {
        stopIdleTimer()
        try {
            speechRecognizer?.destroy()
            windowManager.removeView(rootContainer)
        } catch (e: Exception) {
            Log.e(TAG, "Error removiendo vista en onDestroy", e)
        }
        super.onDestroy()
    }
}
