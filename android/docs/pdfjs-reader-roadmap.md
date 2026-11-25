# Roadmap — Lector PDF con PDF.js (Android)

Rama: `feat/android-pdfjs-reader`. Estado: Fase 0 en curso (2026-11-25).

## Por qué

- El visor PDF actual (Readium 3.2.0 `PdfNavigatorFragment` + adaptador pdfium) solo implementa `VisualNavigator`, `OverflowableNavigator` y `Configurable`. No hay selección de texto ni decoraciones. Verificado con `javap` sobre el AAR.
- Consecuencia: el código de selección y resaltados en `app/src/main/java/com/nexo/presentation/screen/ReadiumPdfReaderContent.kt` (líneas ~159-230) sale en el primer cast y nunca hace nada.
- Objetivo: que PDF tenga los mismos 2 menús custom que EPUB (menú de selección y menú de resaltado), con resaltados persistentes y compatibles con desktop.

## Decisión

- PDF.js (`pdfjs-dist` 6.2.108, la misma versión que desktop en `desktop/package.json`) dentro de un WebView. Licencia Apache 2.0.
- Prior art: [GrapheneOS/PdfViewer](https://github.com/GrapheneOS/PdfViewer) — Kotlin + pdf.js 6.2.108 + WebView.

### Descartados

- **androidx.pdf**: creo que pide API 31+ (la app tiene minSdk 26) y trae su propio menú. No verificado.
- **Selección propia sobre pdfium**: manijas, hit-testing y pintura a mano. Lo más caro y frágil.
- **MuPDF**: licencia AGPL.
- **Convertir PDF a EPUB y usar el lector EPUB**: pierde maquetación, falla con escaneados sin texto y rompe la sync de resaltados con desktop.

## Arquitectura objetivo

### Viewer

- Viewer propio mínimo sobre componentes de `pdfjs-dist` (`PDFViewer`, `EventBus`, `PDFLinkService`, TextLayer). NO `web/viewer.html`.

### Carga de assets y libro

- Assets y libro servidos por `androidx.webkit.WebViewAssetLoader` (origen https `appassets.androidplatform.net`).
- El PDF llega por un `PathHandler` propio que devuelve un `InputStream` del archivo. Los bytes nunca pasan por el bridge.
- Range requests (206) solo si los PDFs grandes lo exigen.

### Selección

- JS escucha `selectionchange` y envía `{pageNumber, text, rects}` a Kotlin por `WebViewCompat.addWebMessageListener`, con `allowedOriginRules` limitado al origen del loader. No `addJavascriptInterface`.
- Kotlin alimenta los menús Compose existentes.
- ActionMode nativo suprimido con `setCustomSelectionActionModeCallback`, como en EPUB.

### Resaltados

- Capa overlay propia por página. Hit-test del tap abre el menú de resaltado.
- NO usar el editor de anotaciones de pdf.js.
- Formato = el de desktop: `pageNumber` + rects en coordenadas de página a escala 1, origen arriba-izquierda + texto.
  - Desktop: `desktop/src/lib/features/reader/viewer-pdf/usePdfSelection.svelte.ts` (~línea 186) divide los rects del text layer por la escala.
  - NO usar coordenadas PDF nativas (origen abajo-izquierda).
  - Esquema exacto a confirmar en Fase 3.

### Endurecimiento

- `allowFileAccess=false`, `allowContentAccess=false`, `blockNetworkLoads=true`.
- CSP por cabecera.
- `isEvalSupported:false`, scripting de PDF desactivado.
- `shouldOverrideUrlLoading` bloqueado.
- Manejo de `onRenderProcessGone`.

## Fases

| Fase | Objetivo | Entregable | Puerta de salida |
|------|----------|------------|------------------|
| 0 — Spike | WebView + AssetLoader + viewer mínimo | Carga un PDF real de la biblioteca; la selección llega a Kotlin (log). Activo solo en debug por una constante | Worker arranca bajo el AssetLoader; selección estable en emulador y 1-2 celulares reales; mensaje `{page,text,rects}` llega a Kotlin; PDF escaneado grande no revienta memoria |
| 1 — Visor base | Reemplazar `ReadiumPdfReaderContent` tras un flag | Scroll/páginas, pinch-zoom (escala CSS + re-render al soltar), progreso y reanudar (locator/página), temas (filtro CSS sobre canvas), taps de chrome | Paridad con el visor actual |
| 2 — Selección a menús | Conectar al flujo existente | `interactionHolder.onReadiumSelection` o equivalente PDF; copiar, traducir, definir, diccionario | Mismos menús que EPUB |
| 3 — Resaltados | Resaltados completos | Crear, pintar, tocar abre menú de resaltado, editar color/nota, borrar; persistencia y sync con desktop en el formato compartido | Un resaltado hecho en Android aparece en el mismo sitio en desktop, y viceversa |
| 4 — Navegación | Moverse por el documento | Índice (outline), enlaces internos, marcadores, búsqueda de texto | Cada función usable en un PDF real |
| 5 — Endurecimiento y rendimiento | Producción | CSP, `maxCanvasPixels`, PDFs grandes, liberar páginas fuera de pantalla, decisión legacy/modern, retirar el adaptador pdfium si ya no se usa | PDFs grandes fluidos sin OOM; adaptador pdfium retirado o justificado |
| Futuro (opcional) | Modo "texto" (reflow) | Convierte PDFs de texto corrido a EPUB para el lector EPUB | — |

## Riesgos (top 5)

1. **Selección inestable en WebView Android.** Bug conocido [mozilla/pdf.js#17912](https://github.com/mozilla/pdf.js/issues/17912): salto al inicio de página si el dedo queda entre líneas. Mitigación parcial en el commit `92b5cf6` (`.endOfContent`). Riesgo central.
2. **Worker/ESM** (`.mjs` MIME, CORS) bajo `WebViewAssetLoader`. Sin verificar.
3. **Versión mínima de WebView.** Build moderno vs legacy (legacy pide Chrome 125+ según la FAQ de pdf.js; moderno, una más reciente). Android 8-10 sin Play Store pueden tener WebView viejo.
4. **Memoria y límite de canvas** con PDFs grandes y zoom.
5. **Mapeo selección a coordenadas** correcto con rotación de página y zoom.

## Decisiones abiertas

- Soporte Android 8-10 sin WebView actualizado: build legacy (más pesado) o no.
- Empaquetado de `pdfjs-dist` en el build de Android: archivos vendorizados en assets vs copia desde un paquete en una tarea Gradle.
