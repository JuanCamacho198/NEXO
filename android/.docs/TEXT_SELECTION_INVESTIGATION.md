# Text Selection Investigation — EPUB & PDF in Android WebView

> **Propósito**: Documentar todos los approaches probados, resultados, y preguntas abiertas para que puedas continuar la investigación.

---

## 1. El problema

Al seleccionar texto en el lector EPUB (y PDF), el menú default de Android (Copiar/Compartir/Seleccionar todo/⋯) se superpone al menú personalizado de NEXO (color picker de 5 colores + Copy). El objetivo es **suprimir el menú default** y mostrar **solo el menú personalizado**.

---

## 2. Approaches probados

### 2.1 ActionMode.Callback (❌ No funciona)

```kotlin
setCustomSelectionActionModeCallback(object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
    // ...
})
```

**Resultado**: No suprime el *floating toolbar* moderno (API 23+). El método `setCustomSelectionActionModeCallback` está deprecado desde API 26 y el WebView ignora este callback para `FloatingActionMode`.

**Referencia**: https://developer.android.com/reference/android/webkit/WebView#setCustomSelectionActionModeCallback(android.view.ActionMode.Callback)

### 2.2 ActionMode.Callback2 (⚠️ Parcial)

```kotlin
object : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
    override fun onGetContentRect(mode: ActionMode, view: View?, outRect: Rect?) {
        outRect?.set(0, 0, 0, 0)
    }
}
```

**Resultado**: Funciona en algunos dispositivos/WebViews pero NO en todos. `Callback2` es la versión moderna (API 23+) que maneja `FloatingActionMode`. El `onGetContentRect()` devolviendo rect vacío debería evitar que el floating toolbar se muestre, pero en algunos WebViews Chromium lo ignora.

**Estado actual**: Implementado en `EpubWebView.kt` (commit `fix(reader): suppress default Android selection toolbar via ActionMode.Callback2`). En el dispositivo del usuario NO funciona.

**Referencia**: https://developer.android.com/reference/android/view/ActionMode.Callback2

### 2.3 CSS `user-select: none` + JS programmatic selection (❌ Rompe selección)

```css
body { -webkit-user-select: none; user-select: none; }
```

Y en JS:
```javascript
// Long-press handler
var longPressTimer = setTimeout(function() {
    var range = document.createRange();
    range.selectNodeContents(target);
    sel.addRange(range);
}, 400);
```

**Resultado**: Rompe la selección de texto completamente en EPUB. El `user-select: none` impide que el usuario seleccione texto de forma nativa. El long-press JS intenta seleccionar programáticamente pero:
- No permite seleccionar texto parcial (solo nodos completos)
- No respeta el gesto nativo de selección (doble tap, drag handles)
- La UI de selección nativa (handles azules) no aparece
- Mala UX comparado con la selección nativa

**Conclusión**: Este approach no es viable para producción. Es mejor mantener la selección nativa.

### 2.4 `selectionchange` event (✅ Funcional, pero toolbar default aparece)

```javascript
document.addEventListener('selectionchange', function() {
    var sel = window.getSelection();
    if (sel && !sel.isCollapsed) {
        var rect = range.getBoundingClientRect();
        NexoBridge.onTextSelectionEvent(sel.toString(), rect.left, rect.top, rect.right, rect.bottom);
    }
});
```

> **Nota de vigencia (NEXO):** el símbolo `NexoBridge` **no existe** en el
> repositorio: no hay `addJavascriptInterface` en `android/` ni ninguna clase
> llamada `NexoBridge`, y `EpubWebView.kt` tampoco existe. El fragmento anterior
> es ilustrativo e histórico, no una descripción del código actual. La selección
> de texto se resuelve hoy sin inyectar un objeto JS: `ReadiumSelectionBridge`
> consulta la selección con `evaluateJavascript`, y los tests
> `ReaderViewModelAnnotationTest` y `ReaderViewModelFinalSweepTest` fallan si se
> reintroduce el delegate `onTextSelectionEvent` en el ViewModel.

**Resultado**: La selección de texto funciona correctamente. El evento `selectionchange` detecta cuando el usuario selecciona texto y envía las coordenadas al bridge de Kotlin. El ViewModel muestra el `TextSelectionMenu` (color picker).

**Problema**: El menú default de Android aparece simultáneamente (porque `ActionMode.Callback2` no lo suprime en este dispositivo).

**Estado actual**: Es lo que está implementado ahora en `EpubWebView.kt` tras el revert.

---

## 3. Investigación sobre apps reales

### BookFusion
- Usa **CSS `user-select: none` + JS overlay** para manejo de selección
- El menú contextual es completamente custom (no usa ActionMode)
- Renderiza EPUB con su propio motor (no WebView estándar)
- PDF: usa **PDF.js en WebView** con su propio handler de selección

### ReadEra
- Usa **motor nativo** (no WebView) para EPUB y PDF
- Text selection es 100% nativa
- No tiene este problema porque no usa WebView

### Moon+ Reader
- Usa **WebView** para EPUB con CSS injection
- Text selection: usa **JavaScript** para detectar selección y **custom popup** en Compose/Native
- Suprime el ActionMode WebView con **reflection** (approach similar al nuestro, sin Callback2)

### Librera (MuPDF)
- Usa **MuPDF** (motor C con JNI) — no WebView
- Sin problemas de selección

---

## 4. PDF.js en WebView — Problemas de renderizado

### 4.1 Approaches probados para cargar PDF.js

| Approach | Resultado | Causa |
|----------|-----------|-------|
| `loadDataWithBaseURL` + `https://` | ⚠️ Blank (fetch CORS) | `loadDataWithBaseURL` crea origen `null`, `fetch()` a `https://` es cross-origin. Chromium elimina headers CORS del `WebResourceResponse` |
| `loadUrl` + `https://` virtual domain | ❌ `ERR_INVALID_RESPONSE` | `shouldInterceptRequest` NO intercepta navegación principal en algunos WebViews |
| `loadDataWithBaseURL` + `http://` | ❌ Mismo CORS | El origen sigue siendo `null` con `loadDataWithBaseURL`, aunque el scheme sea `http://` |
| ✅ **Base64 chunk injection** | ✅ **Funciona** | El PDF se lee en Kotlin, se divide en chunks de 512KB, se codifica en Base64 y se inyecta vía `evaluateJavascript`. Sin `fetch()`, sin CORS, sin WebViewAssetLoader para el PDF |

### 4.2 Estado actual de PDF
- **Renderizado**: ✅ Funciona via Base64 chunk injection
- **Selección de texto**: El menú custom (`SelectionOverlay`) está conectado en `ReaderScreen.kt`
- **Long-press en PDF**: `index.html` usa long-press JS (400ms) porque PDF.js tiene `user-select: none` en su text layer por defecto

---

## 5. Preguntas abiertas para investigar

### Prioridad alta
1. **¿Cómo suprimir el floating toolbar de Android en WebView para API 33+?**
   - Investigar `MenuProvider` / `onProvideMenuItems` en WebView
   - Probar `ACTION_PROCESS_TEXT` Intent para interceptar la acción de selección
   - Investigar si hay forma de acceder al `FloatingActionMode` interno del WebView

2. **¿Cómo hacen BookFusion para suprimir el toolbar en WebView?**
   - BookFusion tiene el mismo stack (PDF.js + WebView). Revisar su open-source o hacer reverse engineering de su APK.
   - Posiblemente usan `onTouchEvent` override para interceptar antes de que el WebView maneje la selección

3. **¿Funciona `ACTION_PROCESS_TEXT` como alternativa?**
   - `ACTION_PROCESS_TEXT` permite agregar acciones al menú contextual de selección de texto
   - No suprime el menú default, pero permite agregar nuestra acción de highlight
   - Implementación: https://developer.android.com/guide/topics/text/action-process-text

### Prioridad media
4. **¿Es viable migrar EPUB reader a un motor nativo (MuPDF, folio)?**
   - MuPDF tiene binding para Android (mupdf-android-viewer)
   - folio: https://github.com/vinaysshenoy/folio
   - readium-kotlin: https://github.com/readium/kotlin-toolkit

5. **¿Es viable implementar un `SelectionOverlay` nativo que cubra el toolbar de Android?**
   - Usar `Dialog` con `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
   - Posicionar sobre el toolbar default

### Prioridad baja
6. **Probar en diferentes versiones de WebView/Android**
   - El problema puede ser específico del WebView del dispositivo actual
   - Probar en WebView 120+, Android 14+

---

## 6. Código relevante

| Archivo | Propósito |
|---------|-----------|
| `app/src/main/java/com/nexo/ui/components/molecules/EpubWebView.kt` | EPUB reader WebView con `selectionchange` + `ActionMode.Callback2` |
| `app/src/main/java/com/nexo/ui/components/molecules/PdfWebView.kt` | PDF reader con Base64 chunk injection |
| `app/src/main/assets/pdfjs/index.html` | PDF.js HTML con long-press handler + chunk loading |
| `app/src/main/java/com/nexo/ui/components/molecules/TextSelectionMenu.kt` | Color picker bar (5 colores + copy) |
| `app/src/main/java/com/nexo/ui/components/molecules/FloatingContextMenu.kt` | Menú contextual expandido |
| `app/src/main/java/com/nexo/ui/components/molecules/SelectionOverlay.kt` | Shared overlay component para EPUB y PDF |
| `app/src/main/java/com/nexo/presentation/screen/ReaderScreen.kt` | Reader screen que orquesta EPUB y PDF |
| `app/src/main/java/com/nexo/presentation/viewmodel/ReaderViewModel.kt` | ViewModel con lógica de selección de texto |

---

## 7. Commits relacionados

```
fix(reader): suppress default Android selection toolbar via ActionMode.Callback2
fix(pdf): load PDF.js via http:// scheme to fix fetch() CORS issues
fix(pdf): replace fetch+WebViewAssetLoader with Base64 chunk injection via JS bridge
fix(reader): add shared SelectionOverlay composable for EPUB/PDF color picker
fix(epub): revert user-select:none and long-press, restore selectionchange
```

---

## 8. Recomendación personal

El approach más prometedor para el futuro:

1. **Para EPUB**: Investigar `ACTION_PROCESS_TEXT` — agregar la acción de highlight al menú default de Android en vez de reemplazarlo. Es más estándar y funciona en todos los dispositivos.

2. **Para PDF**: El Base64 chunk injection funciona. El long-press en JS también. Si el toolbar default aparece en PDF, misma solución que EPUB.

3. **Largo plazo**: Migrar a **Readium Kotlin Toolkit** (mantenido por la Readium Foundation, usado por apps comerciales). Maneja EPUB, PDF, text selection, DRM. Es el estándar de la industria para apps de lectura.
