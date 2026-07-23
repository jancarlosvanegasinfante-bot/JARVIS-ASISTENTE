# Jarvis Android — proyecto nativo

Este proyecto es el "cuerpo" nativo de tu Jarvis: escucha 24/7 ("Jan"),
controla el teléfono (AccessibilityService), y muestra tu app React
(Dashboard, VoiceModal, etc.) dentro de un WebView.

## Lo que YA está listo aquí
- `JarvisAccessibilityService.kt` — abre apps, toca botones por texto, escribe texto.
- `WakeWordService.kt` — escucha 24/7 con Vosk (offline, sin cuenta) buscando "Jan".
- `MainActivity.kt` — WebView + puente `window.AndroidBridge` hacia tu React.
- `.github/workflows/build-apk.yml` — compila el APK en la nube, sin instalar nada tú.

## Pasos para dejarlo funcionando

### 1. Descargar el modelo de voz en español de Vosk
Vosk necesita un modelo local (no requiere cuenta, es descarga directa):
1. Entra a https://alphacephei.com/vosk/models
2. Descarga `vosk-model-small-es-0.42` (~50MB, el "small" es suficiente para wake-word)
3. Descomprímelo y renombra la carpeta a `model-es`
4. Cópiala dentro de `app/src/main/assets/model-es/`
5. Haz commit y push — GitHub Actions la incluirá en el build

### 2. Apuntar el WebView a tu frontend desplegado
En `MainActivity.kt`, línea:
```kotlin
webView.loadUrl("https://TU-FRONTEND-DESPLEGADO.up.railway.app")
```
Cambia esa URL por donde despliegues el `dist/` de tu Vite build (Railway, igual que Jan Sel Shop).

### 3. Conectar tu VoiceModal.tsx al puente nativo
En tu proyecto React (`jarvis-voice-assistant`), donde ya recibes la respuesta
de Gemini con la acción a ejecutar, agrega:

```typescript
// Después de recibir la acción estructurada de tu backend /api/parse-intent
declare global {
  interface Window {
    AndroidBridge?: {
      executeAction: (json: string) => void;
      isAccessibilityEnabled: () => boolean;
      openAccessibilitySettings: () => void;
    };
  }
}

function ejecutarAccion(action: object) {
  if (window.AndroidBridge) {
    window.AndroidBridge.executeAction(JSON.stringify(action));
  } else {
    console.warn("AndroidBridge no disponible (¿estás en el navegador normal?)");
  }
}
```

Y en tu backend (`server.ts`), asegúrate de que Gemini devuelva JSON con esta forma
(puedes ajustar el prompt del endpoint `/api/parse-intent` para forzar este esquema):
```json
{ "action": "open_app", "package": "com.whatsapp" }
{ "action": "tap_text", "text": "Enviar" }
{ "action": "type_text", "text": "Ya voy en camino" }
{ "action": "go_back" }
```

### 4. Subir este proyecto a tu repo
```bash
git add .
git commit -m "Proyecto nativo Jarvis Android"
git push
```

### 5. Descargar el APK compilado
1. Ve a tu repo en GitHub → pestaña **Actions**
2. Verás el workflow "Build Jarvis APK" corriendo (o dale "Run workflow" si no arrancó solo)
3. Cuando termine (~5-8 min), entra al run → sección **Artifacts** → descarga `jarvis-apk`
4. Ese `.apk` se instala directo en tu celular (activa "orígenes desconocidos" en Ajustes)

### 6. Primera vez en tu celular (permisos manuales, obligatorios por Android)
Al abrir la app te va a pedir, en este orden:
1. **Micrófono** → Permitir
2. **Servicio de Accesibilidad** → te lleva a Ajustes → Accesibilidad → busca "Jarvis" → actívalo
3. **Batería**: ve a Ajustes → Batería → Jarvis → "Sin restricciones" (para que no lo mate en background)

Después de eso, Jarvis queda escuchando "Jan" incluso con la pantalla apagada,
vas a ver la notificación fija "Jarvis activo" en la barra de notificaciones
(es obligatoria, Android no permite ocultarla).

## Ampliando las acciones disponibles
Todas las acciones que Jarvis puede ejecutar viven en
`JarvisAccessibilityService.executeAction()`. Para agregar una nueva
(ej: "subir volumen", "abrir cámara", "leer notificaciones en voz alta"),
solo agregas un nuevo `case` en el `when` y su función correspondiente —
dime cuáles quieres priorizar y te las agrego.

## Nota sobre batería
Vosk escuchando 24/7 consume más batería que en reposo normal (esperable con
cualquier wake-word engine que no sea un DSP dedicado). Si notas mucho
consumo, podemos ajustar el tamaño del buffer de audio o evaluar migrar a
un modelo aún más pequeño de Vosk.
