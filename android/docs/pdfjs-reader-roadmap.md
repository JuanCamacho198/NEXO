# Roadmap — Lector PDF con PDF.js (Android)

Rama: `feat/android-pdfjs-reader`. Estado: Fases 0, 1, 2 y 3a cerradas (2026-11-26). Fase 3b (paridad de resaltados con desktop) sigue abierta y la puerta de salida de la fase 3 no se cumple todavía.

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

- JS escucha `selectionchange` y envía `{pageNumber, text, rects, bounds, scale}` a Kotlin por `WebViewCompat.addWebMessageListener`, con `allowedOriginRules` limitado al origen del loader. No `addJavascriptInterface`.
  - `pageNumber` 1-based (viene de `data-page-number`; Kotlin NO lo resta en la ruta de selección).
  - `rects`: relativos a la página, escala 1, origen arriba-izquierda (formato de persistencia de Fase 3), ya divididos por `scale` en JS.
  - `bounds`: `getBoundingClientRect()` crudo = px CSS del viewport del WebView; es el único campo en espacio de vista. Ancla el menú.
  - `scale`: `viewer.currentScale || 1`.
- Kotlin alimenta los menús Compose existentes.
- ActionMode nativo suprimido sobreescribiendo `WebView.startActionMode` en una subclase que vacía el menú (`PdfJsReaderContent.kt`, `NoToolbarWebView`), no con `setCustomSelectionActionModeCallback`.

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
| 1 — Visor base | Reemplazar `ReadiumPdfReaderContent` tras un flag | Ver "Plan Fase 1" | Paridad con el visor actual |
| 2 — Selección a menús | Conectar al flujo existente | `interactionHolder.onReadiumSelection` o equivalente PDF; copiar, traducir, definir, diccionario | Mismos menús que EPUB |
| 3 — Resaltados | Resaltados completos | Crear, pintar, tocar abre menú de resaltado, editar color/nota, borrar; persistencia y sync con desktop en el formato compartido | Un resaltado hecho en Android aparece en el mismo sitio en desktop, y viceversa — **pendiente (3b)** |
| 4 — Navegación | Moverse por el documento | Índice (outline), enlaces internos, marcadores, búsqueda de texto | Cada función usable en un PDF real |
| 5 — Endurecimiento y rendimiento | Producción | CSP, `maxCanvasPixels`, PDFs grandes, liberar páginas fuera de pantalla, decisión legacy/modern, retirar el adaptador pdfium si ya no se usa | PDFs grandes fluidos sin OOM; adaptador pdfium retirado o justificado |
| Futuro (opcional) | Modo "texto" (reflow) | Convierte PDFs de texto corrido a EPUB para el lector EPUB | — |

Estado: 0 cerrada, 1 cerrada, 2 cerrada, 3a cerrada (2026-11-26). 3b (paridad con desktop) abierta: la puerta de salida de la fase 3 no se cumple. Fase 4 (navegación: índice, enlaces, marcadores, búsqueda) no depende de la 3. Los ajustes de lectura (tema, tamaño, márgenes) no están asignados a ninguna fase: PDF no los aplica hoy.

## Resultados Fase 0

- Emulador (Android 15, WebView 124 sin Play Store): el build moderno falla (`URL.parse` no existe hasta Chrome 126) → se usa el build **legacy** + polyfill de `URL.parse` en página y worker (el worker se parchea al servirlo, sin tocar archivos vendorizados).
- WebView 124 tampoco soporta CSS `round()` (se reescribe a `calc()`) y daba altura 0 al contenedor con `100%`/`100vh` (se usa `position:fixed`; causa raíz no identificada).
- Celular real (Redmi 2409BRN2CL, HyperOS, Android 16, WebView 157), PDF de texto 1 MB / 129 págs: primera página en 1–2 s, sin páginas en blanco tras recorrerlo entero, la selección llega a Kotlin, toolbar nativo suprimido (subclase de WebView que vacía el menú del ActionMode).
- Estabilidad de selección: 2 de 3 arrastres de manija correctos; 1 extendió hacia arriba ~4 líneas (no saltó al inicio de página). Pendiente medir con más intentos.
- La decisión abierta "legacy vs moderno" queda resuelta: legacy (5,4 MB de assets).

## Pendiente: PDFs escaneados (prioridad baja)

La app apunta primero a libros normales. Medido en el celular con un PDF escaneado de 19,65 MB / 372 págs:

- Memoria de la app: ~600 MB en reposo → ~800 MB tras 15 desplazamientos rápidos → ~1–1,5 GB tras 30, y sigue subiendo ~20 s sin tocar. Lo que crece es "EGL mtrack" (memoria gráfica del compositor), no los canvas de pdf.js (solo ~8 MB vivos) ni el proceso sandbox (290–410 MB, estable).
- `maxCanvasPixels` y quitar `LAYER_TYPE_HARDWARE`: sin efecto.
- Páginas finales quedan en estado "running" (1) indefinidamente → frames grises. Causa no identificada.
- Causa probable (inferido): caché de tiles GPU del compositor o imágenes escaneadas decodificadas retenidas en GPU.
- Siguiente a probar: `pdfDocument.cleanup()` periódico, `enableHWA:false`, limitar la velocidad de flings.
- Primera página ~5 s.

## Plan Fase 1

- Hallazgo: el visor PDF actual ya tiene el progreso roto: siempre abre en la página 1 (ignora la posición guardada), `totalPdfPages` nunca se fija en producción (solo `onPdfDocumentLoaded` lo fija y no tiene llamador), por eso indicador de página, diálogo "ir a página" y `goToPdfPage` no funcionan. La Fase 1 lo arregla, no lo copia.
- Formato de progreso decidido: escribir ambos — `cfiLocation = pdfpage:N` (0-based, ya entendido por marcadores/restore) + `locatorJson` con `locations.position` (1-based). Las filas antiguas `readium:` siguen abriendo en la página 1. Cuidado con off-by-one.
- Slices:
  1. Carga + total de páginas + página actual → `onPdfDocumentLoaded`, indicador "N / total", taps de chrome con `ChromeEdgeTapZones`.
  2. Progreso hacia fuera (JS `page {page,total}` → locator + persistencia; verificar la compuerta `mayPersist` para PDF).
  3. Reanudar + navegación entrante (`goToPage(n)` desde posición guardada, `navigateToLocator`, marcadores, diálogo ir a página; resolver la página desde `locations.position`, no desde `readingOrder`).
  4. Viewport y rotación (reescalar al cambiar el ancho).
- Sin temas en Fase 1: el visor actual no aplica `readerSettings` a PDF.
- Riesgo: desktop no restaura progreso de PDF hoy (inferido); la sync de progreso PDF Android↔desktop queda fuera de Fase 1.

## Resultados Fase 1

- Slice 1: el visor informa el total de páginas (`onPdfDocumentLoaded`), así el indicador y los límites de navegación por fin conocen el total en PDF; taps de chrome en bordes como el visor viejo.
- Slice 2: progreso guardado al cambiar de página: `pdfpage:N` (0-based) + `locatorJson` con `position` N+1 y `totalProgression` (inicio de página; `percentage` usa el final de página, convención previa). Verificado en emulador (fila `pdfpage:4`, 62,5 %, position 5).
- Slice 3: reanuda en la página guardada (el loader lee `pdfpage:N` antes de crear el visor; el visor salta y solo después informa, así abrir no sobrescribe la posición). Saltos desde la barra de progreso, zonas de tap y marcadores llegan al visor por un flujo de índice de página. Verificado en emulador.
- Slice 4: rotación ya funciona sin cambios: la Activity se recrea, el WebView recarga el PDF y abre en la página guardada, ajustado al ancho. Abre al inicio de la página, no en el desplazamiento exacto (inferido). Recargar el PDF en cada rotación puede ser lento en PDFs grandes (no medido).

## Resultados Fase 2
- El payload completo llega a Kotlin: texto, `rects` (página, escala 1), `bounds` (px CSS de viewport), `scale` y `pageNumber`. Antes solo sobrevivían `empty`, `pageNumber` y el largo del texto.
- La selección entra por `SelectionManager.onPdfSelection`, que ya existía y estaba testeada pero no tenía llamador. `PdfSelectionAnchor.pageNumber` queda **1-based**, fijado por el test `onPdfSelection keeps the page number 1-based`; el índice 0-based existe solo dentro del dispatch del bridge (`page - 1`). Fase 3 debe respetarlo.
- El overlay se renderiza en la rama PDF.js como hermano del visor dentro del mismo `Box`, así que el origen del viewport del WebView ES el origen de colocación y no hace falta trasladar. Medido en `dev_clean` (1080×2400, 420 dpi): `window.devicePixelRatio` = 2.625 = `DisplayMetrics.density` = `LocalDensity.density`; el WebView empieza en y=128 por el inset de la barra de estado.
- Anclaje: sin factor de densidad el menú quedaba sobre la palabra seleccionada (y=280 del content box para una palabra en 415–445). Con densidad ×2.625 queda 31 px debajo. Dos tests fijan la conversión.
- Los cuatro menús funcionan en un PDF real, verificado en emulador: definir (tarjeta de lookup sobre la palabra), traducir (panel sobre la palabra), copiar (portapapeles real), diccionario ("added to your dictionary"). Descartar la selección y tocar fuera cierran el menú. La rama `Existing` (resaltado tocado) no se alcanza para PDF: no hay locator.
- Bug preexistente encontrado y corregido en el camino: "Copiar" leía `selectedText` **después** de que `onCopySelectedText()` cerrara el menú, y cerrar lo pone a null → el portapapeles quedaba vacío mientras el snackbar decía "Copied to clipboard". Afectaba también a EPUB y a resaltados tocados. Corregido leyendo antes de la llamada.
- Defecto abierto (nuevo, fuera de alcance): tras copiar o añadir al diccionario el menú se cierra, pero la selección DOM y las manijas siguen en pantalla.

## Resultados Fase 3a

Resaltados PDF cerrados en Android: crear desde una selección, pintar, tocar para abrir el menú, recolorear, anotar y borrar, con persistencia real. Verificado en `dev_clean` sobre `spike-test.pdf` (8 páginas) con la app reinstalada desde el build de esta fase.

- **P3-T6.** El camino de anotar construía su propio `Highlight`, así que una nota nacida de una selección PDF se guardaba con un `cfiRange` `pdfpage:<n>` derivado de la página actual pero **sin `page` y sin rects**: una fila que nunca podría pintarse. Ahora ambos caminos comparten `pdfHighlight` (`PdfHighlightFactory.kt`), que devuelve el objeto ya armado — el de color lo persiste, el de anotar además lo promueve a `ExistingHighlight` y abre el modal. EPUB sigue escribiendo `readium:…` con `locatorJson` y sin geometría. Tests: `annotating a PDF selection keeps the anchor page, rects and a 0-based pdfpage cfi` y `annotating an EPUB selection still writes the readium row without geometry`.
- **Medición de pintura.** `dev_clean` 1080×2400, densidad 2.625, WebView con origen en y=128 px. `viewer.currentScale` = 0,51932776 (A4, caja de página 412 × 583,03 CSS px); en scroll 0 el `pageRect` es (0,095; 0). Pintura = `(pageRect + rect × scale) × density`:
  - «amet»: rect (231,54; 90,63; 37,56; 21,91) → calculado 315,6 / 251,6 px, 51,2 × 29,9 px; pintado **(316,252)-(366,280)** = 51 × 29 px.
  - «labore»: rect (372,4; 450,66; 47,8; 21,91) → calculado 507,6 / 742,4 px, 65,1 × 29,9 px; pintado **(508,743)-(572,771)** = 65 × 29 px.
  - Diferencia 0 px; lo que sobra es el redondeo al píxel del borde.

Las cinco comprobaciones de P3-T7, con lo observado:

1. **Persistencia.** Force-stop (`am force-stop com.nexo`), relanzar, reabrir el mismo PDF, scroll a 0: el mismo cuadro con el mismo recuento de píxeles — **(316,252)-(366,280), 951 píxeles** antes y después. El verde no se movió: (508,743)-(572,771), 1285.
2. **Recolorear.** Tocar el resaltado abre el menú; el swatch actual abre la paleta; elegir azul deja **0 píxeles amarillos y 951 azules en el mismo bbox**: el tinte cambia y la geometría no se mueve ni desaparece. En Room: `color = #3B82F6`, `rect_json` idéntico.
3. **Nota.** El lápiz del menú abre el modal con el texto seleccionado («amet»); se guardó «phase3a note». Tras force-stop y reapertura, tocar el resaltado y abrir el modal muestra la nota otra vez: sigue adjunta.
4. **Borrar.** La papelera quita el tinte (**0 píxeles azules**), la fila queda con `deleted_at` (soft delete, `note` y `rect_json` intactos) y sigue ausente tras force-stop y reapertura. «labore» queda intacto (1285 píxeles) durante todo el ciclo.
5. **Sin regresión.** Una selección nueva (rango del DOM sobre «consectetur») sigue abriendo el menú de selección con sus cinco colores y sus acciones. La pintura no invade el chrome: desplazando 649,9 px el cuadro de «labore» cae en y 93–121, por encima del origen del WebView (128), y **no se pinta ni un píxel de tinte** (0 en la banda y<128 y 0 en el resto); al volver a scroll 0 reaparece exacto en (508,743).

Límites del entorno, no del producto:

- El long-press real no dispara la selección en este AVD (bug 1 de `odd/tasks/reader-bugs.md`); las selecciones se produjeron con un `Range` del DOM por CDP, que emite **el mismo payload** que el visor (`{pageNumber, text, rects, bounds, pageSize, scale}`).
- Arranque en frío: mientras corrían las tareas de Gradle de esta fase, el sistema marcó ANR «failed to complete startup» (40,8 s hasta el primer frame); con la máquina libre el mismo APK arranca sin ANR. No es una regresión de este cambio.
- `uiautomator dump` y `android layout` no exponen nodos de la app en este build (solo SystemUI), así que los toques se ubicaron con capturas y medición de píxeles.
- El menú de selección PDF no ofrece acción de nota (`TextSelectionMenu` no pasa `onAnnotate`), así que el camino de anotar **no se pudo ejercitar en dispositivo**: queda cubierto por los dos tests unitarios.

**3b sigue abierta y la puerta de salida de la fase 3 no se cumple.** Desktop guarda `page` y **una sola caja** (`rect_left/right/top/bottom` en `desktop/src-tauri/migrations/0003_highlights.sql`), sin `rect_json`; su overlay por rects lee `data.rects`, que tras recargar no tiene de dónde salir. Un resaltado de Android con varios rects no viaja al formato de desktop, y uno de desktop pierde sus rects al reabrir. Mientras eso no cambie, «aparece en el mismo sitio en desktop y viceversa» no está medido ni cumplido.

## Hallazgos fuera de alcance (no corregidos)

- El diálogo "Ir a página" no se puede abrir: nada en `app/src/main` pone `showGoToPageDialog` a true (afecta a toda la app, no solo PDF).
- El visor PDF viejo (Readium, el de release) ya no recibe saltos de página: ahora van por el flujo de índice que solo escucha PDF.js. Antes solo funcionaban para la página 1 (`readingOrder.getOrNull(pageIndex)` en un PDF de un solo enlace), así que la pérdida es mínima y desaparece cuando PDF.js reemplace al viejo.
- Desktop no restaura el progreso de PDF (inferido); la sync de posición PDF Android↔desktop sigue pendiente.

## Riesgos (top 5)

1. **Selección inestable en WebView Android.** Bug conocido [mozilla/pdf.js#17912](https://github.com/mozilla/pdf.js/issues/17912): salto al inicio de página si el dedo queda entre líneas. Mitigación parcial en el commit `92b5cf6` (`.endOfContent`). Riesgo central.
2. **Worker/ESM** bajo WebViewAssetLoader — resuelto: funciona sirviendo `.mjs` como `text/javascript`.
3. **Versión mínima de WebView** — resuelto con el build legacy + polyfills (ver Resultados Fase 0).
4. **Memoria y límite de canvas** con PDFs grandes y zoom.
5. **Mapeo selección a coordenadas** correcto con rotación de página y zoom.

## Decisiones abiertas

- Empaquetado de `pdfjs-dist` en el build de Android: archivos vendorizados en assets vs copia desde un paquete en una tarea Gradle.

## Rendimiento
Estudio aparte: `docs/pdf-performance-research.md`. Resumen de lo que cambia decisiones:
- La memoria que crece es del compositor del WebView (GPU service en proceso, memoria nativa). Eso explica por qué `maxCanvasPixels` y quitar `LAYER_TYPE_HARDWARE` no hicieron nada.
- Dos pruebas baratas sin intentar: cancelar renders en scroll rápido y poner `canvas.width = 0` al destruir la página (libera la textura sin esperar al GC).
- Lo que de verdad acelera un escaneado es reencodear las imágenes en origen (~150 dpi), no el cliente.
- `androidx.pdf` queda descartado: exige API 31 + SDK extension 13 y el `minSdk` es 26.
- OCR on-device (ML Kit) daría capa de texto a los escaneados, con lo que selección, búsqueda, resaltados y traducción empezarían a funcionar en ellos.
