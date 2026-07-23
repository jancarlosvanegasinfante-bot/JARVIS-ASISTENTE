package com.jose.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * MainActivity
 *
 * Carga tu app React (build de Vite) dentro de un WebView, y expone un
 * puente JS <-> Kotlin llamado "AndroidBridge" para que tu VoiceModal.tsx
 * pueda mandar acciones directo al AccessibilityService.
 *
 * Desde tu React app, tras recibir la respuesta de Gemini con la acción a
 * ejecutar, se llama así:
 *
 *   if (window.AndroidBridge) {
 *     window.AndroidBridge.executeAction(JSON.stringify(action));
 *   }
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = object : WebChromeClient() {
            // Autoriza el acceso al micrófono que pide el propio HTML/JS
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        // Carga el frontend ya compilado (ver README para dónde apuntar esto:
        // local en assets/ para modo offline, o tu URL de Railway/Vercel)
        webView.loadUrl("https://jarvis-voz-asistente-production.up.railway.app")

        requestRuntimePermissions()
        startWakeWordServiceIfReady()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
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
    }
}
