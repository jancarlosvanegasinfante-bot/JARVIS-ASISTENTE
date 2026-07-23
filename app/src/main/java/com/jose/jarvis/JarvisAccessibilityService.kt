package com.jose.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * JarvisAccessibilityService
 *
 * Este es el "brazo" de Jarvis. Una vez el usuario lo activa manualmente en
 * Ajustes > Accesibilidad > Servicios instalados > Jarvis, este servicio puede:
 *  - Leer el árbol de accesibilidad de CUALQUIER app en pantalla
 *  - Abrir apps por nombre de paquete
 *  - Buscar texto/botones en pantalla y tocarlos
 *  - Escribir texto en campos de entrada
 *  - Hacer scroll, volver atrás, ir a inicio
 *
 * Recibe comandos desde MainActivity (que a su vez los recibe del backend Gemini)
 * en forma de JSON estructurado, ej:
 *   { "action": "open_app", "package": "com.whatsapp" }
 *   { "action": "tap_text", "text": "Enviar" }
 *   { "action": "type_text", "text": "Ya voy en camino" }
 *   { "action": "go_back" }
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        // Referencia estática simple para que MainActivity pueda invocar acciones.
        // Para producción real, esto se reemplaza por un bus de eventos (LocalBroadcastManager).
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Jarvis Accessibility Service conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Aquí se puede loguear qué app está en primer plano, útil para
        // que Jarvis sepa "dónde está parado" antes de ejecutar una acción.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Servicio interrumpido")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Punto de entrada: recibe el IntentResult completo que ya arma tu
     * backend Gemini (mismo JSON que ves en la tarjeta "Intención Parseada"
     * dentro del VoiceModal) y lo ejecuta de verdad en el teléfono.
     */
    fun executeAction(actionJson: String) {
        try {
            val json = JSONObject(actionJson)
            val params = json.optJSONObject("params") ?: JSONObject()

            when (json.getString("action")) {
                // --- Acciones de negocio (las que ya manda tu App.tsx) ---
                "send_whatsapp" -> sendWhatsApp(
                    contact = params.optString("contact"),
                    phone = params.optString("phoneNumber"),
                    message = params.optString("message")
                )
                "make_call" -> makeCall(params.optString("phoneNumber"))
                "open_app" -> {
                    // Puede venir como nombre visible ("Spotify") o paquete directo
                    val pkg = params.optString("appName", "")
                    openApp(resolvePackageName(pkg))
                }
                "search_web" -> searchWeb(params.optString("query"))
                "control_music" -> controlMusic(params.optString("command"))
                "read_notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

                // --- Acciones genéricas de bajo nivel (por si las necesitas directo) ---
                "tap_text" -> tapNodeByText(json.optString("text"))
                "type_text" -> typeText(json.optString("text"))
                "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "go_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "scroll_down" -> scrollForward()

                else -> Log.w(TAG, "Acción todavía no soportada: ${json.getString("action")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando acción: $actionJson", e)
        }
    }

    /**
     * Flujo real de WhatsApp: abre la app, espera a que cargue, busca el
     * contacto por nombre/teléfono, escribe el mensaje y lo envía.
     * Los delays son necesarios porque cada pantalla tarda en renderizar.
     */
    private fun sendWhatsApp(contact: String, phone: String, message: String) {
        openApp("com.whatsapp")
        handler.postDelayed({
            // Intenta tocar el contacto por nombre en la lista de chats
            val target = if (contact.isNotBlank()) contact else phone
            tapNodeByText(target)
            handler.postDelayed({
                typeText(message)
                handler.postDelayed({
                    // El botón de enviar en WhatsApp no siempre tiene texto
                    // visible ("Enviar" en algunas versiones, ícono en otras)
                    if (!tapNodeByText("Enviar")) {
                        Log.w(TAG, "No se encontró botón Enviar por texto; revisar selector")
                    }
                }, 500)
            }, 1200)
        }, 1800)
    }

    private fun makeCall(phone: String) {
        if (phone.isBlank()) return
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = android.net.Uri.parse("tel:$phone")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(callIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Falta permiso CALL_PHONE en el Manifest", e)
        }
    }

    private fun searchWeb(query: String) {
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(searchIntent)
    }

    private fun controlMusic(command: String) {
        val keyCode = when (command) {
            "play", "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volume_up" -> android.view.KeyEvent.KEYCODE_VOLUME_UP
            "volume_down" -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            else -> return
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP ->
                audioManager.adjustVolume(android.media.AudioManager.ADJUST_RAISE, 0)
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                audioManager.adjustVolume(android.media.AudioManager.ADJUST_LOWER, 0)
            else -> Log.d(TAG, "Comando de música: $command (requiere sesión multimedia activa)")
        }
    }

    /** Nombres visibles comunes -> paquete real de Android */
    private fun resolvePackageName(appName: String): String {
        return when (appName.trim().lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "spotify" -> "com.spotify.music"
            "teléfono", "telefono" -> "com.android.dialer"
            "sms", "mensajes" -> "com.android.mms"
            "notas" -> "com.google.android.keep"
            "browser", "navegador", "chrome" -> "com.android.chrome"
            else -> appName // asume que ya es un nombre de paquete válido
        }
    }

    private fun openApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Log.w(TAG, "No se encontró la app: $packageName")
        }
    }

    /** Busca un nodo (botón, texto, campo) por su texto visible y lo toca */
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

    /** Escribe texto en el campo actualmente enfocado */
    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun scrollForward() {
        val root = rootInActiveWindow ?: return
        root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /** Gesto de tap en coordenadas x,y (para casos donde no hay texto que buscar) */
    fun tapAt(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
