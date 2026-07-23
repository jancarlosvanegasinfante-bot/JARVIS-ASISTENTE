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

    /** Punto de entrada: recibe una acción en JSON y la ejecuta */
    fun executeAction(actionJson: String) {
        try {
            val json = JSONObject(actionJson)
            when (json.getString("action")) {
                "open_app" -> openApp(json.getString("package"))
                "tap_text" -> tapNodeByText(json.getString("text"))
                "type_text" -> typeText(json.getString("text"))
                "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "go_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "scroll_down" -> scrollForward()
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                else -> Log.w(TAG, "Acción desconocida: ${json.getString("action")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando acción: $actionJson", e)
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
