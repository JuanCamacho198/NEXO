# PDF reader performance — what is actually worth adopting

Research pass, 2026-11-26. Scope: making the Android PDF reader faster and better to read, given
the current stack (pdf.js `6.2.108`, legacy build, inside a `WebView` under
`WebViewAssetLoader`).

Evidence is tagged: **[measured]** already observed in this project, **[doc]** primary
documentation or source, **[vendor]** vendor/agency blog (treat as a claim, not a benchmark),
**[inference]** my reasoning, not measured.

## 1. Executive summary

Three things would move the needle, in this order:

1. **Fix the document, not the renderer.** A scanned 20 MB / 372-page PDF is slow because every
   page is a full-page JPEG. Downsampling and re-encoding at ingestion turns it into a different
   class of file. Nothing on the client competes with that. **[inference]**, backed by the
   measurements in §4.
2. **Give the scanned PDFs a text layer.** On-device OCR (ML Kit) makes selection, search,
   highlights and translation work on documents where today there is nothing to select. This
   unlocks features already built rather than adding new ones. **[doc]**
3. **Upgrade pdf.js and finish the render lifecycle.** `6.2.108` is behind; the 6.4–6.5 line
   carries decoder and image performance work, and the render-cancel path is what the grey-frame
   defect is most likely missing. **[doc]** + **[inference]**

What is **not** worth it: switching to `androidx.pdf` (blocked by `minSdk 26`, see §5), and
chasing `enableHWA` / `maxCanvasPixels` (already tried, no effect). **[measured]**

## 2. The bottleneck, stated correctly

`WebView` on Android runs its GPU service **in-process** and keeps rendered graphics, the DOM and
the JS runtime in **native memory**, not the Java heap. A Java heap dump shows almost none of it.
**[doc]** — `developer.android.com/develop/ui/views/layout/webapps/manage-webview-memory`,
`/topic/performance/memory/guide/webview-memory`.

That matches this project's finding exactly: growth showed up as `EGL mtrack` (compositor/GPU
memory) while the pdf.js canvases held ~8 MB and the sandbox process stayed flat. **[measured]**

Consequence that changes the plan: the memory you must fight is **GPU texture memory held by the
compositor**, so the levers are (a) fewer/smaller canvases alive, (b) explicitly releasing canvas
backing stores, (c) not letting the compositor keep stale layers. Not `maxCanvasPixels`.

## 3. Levers inside pdf.js (no stack change)

| Lever | What it does | Cost | Confidence |
|---|---|---|---|
| `renderTask.cancel()` + re-render on visibility | Kills in-flight renders during fast flings; the classic source of pages stuck at state 1 ("running") with grey frames | Small | **[doc]** + **[inference]** — matches the unexplained defect |
| Zero the canvas on teardown (`canvas.width = 0; canvas.height = 0`) | Releases the backing store immediately instead of waiting for GC, which on Android frees the GPU texture | Tiny | **[inference]**, cheap to test, and the single most likely fix for `EGL mtrack` growth |
| Cap the page buffer | pdf.js' viewer keeps rendered pages in a `PDFPageViewBuffer`; a smaller buffer means fewer live canvases | Small | **[doc]** |
| `page.cleanup()` / `pdfDocument.cleanup()` periodically | Drops per-page caches (fonts, images, operator lists) | Small | **[doc]**; already on the roadmap's list |
| `OffscreenCanvas` + `transferToImageBitmap` | Renders off the main thread; the browser transfers an `ImageBitmap` zero-copy instead of a pixel copy | Medium | **[vendor]** Nutrient; **[doc]** MDN; used by the pdf.js viewer's own worker pool |
| `useWasm` (default `true` since 6.x) | Faster image decoding (JPX/JBIG2) | None — just don't disable it | **[doc]** api.js |
| `enableHWA` | **Removed** from viewer components in the 6.5 line (PR #20849); was default `true` since 5.6 | — | **[doc]** pdf.js releases |
| `maxCanvasPixels` | Default on mobile is 5 MP (`app_options.js` compat params), 32 MP desktop | — | **[doc]**; already tried here with no effect **[measured]** |

The two that have not been tried and are cheap: **cancel-on-scroll** and **zero-the-canvas on
teardown**. Run those before anything bigger.

Also relevant: the whole-page canvas model. Tile-based rendering is what native viewers do and
what makes zoom cheap; pdf.js can do it only with a custom layer. That is a large change and the
wrong next step while Phase 3 is open. **[inference]**

## 4. Fix the file (ingestion), where the real win is

A scanned PDF is a wrapper around one image per page. Nothing a client does to a 20 MB scan beats
re-encoding it. **[inference]**

- **Downsample and re-encode page images.** A 372-page scan at ~300 dpi is far beyond what a phone
  screen resolves; ~150 dpi JPEG q=80 is visually equivalent on a 6–7" screen and typically cuts
  the file by 70–90%. Tools: `mutool clean -gggg -d` (MuPDF CLI, AGPL — the *tool* is fine, the
  library is what we avoid), `qpdf`, Ghostscript `-dPDFSETTINGS=/ebook`, `ocrmypdf
  --optimize 3 --jpeg-quality 80`. **[doc]** tool docs.
- **Linearize** (`qpdf --linearize`) puts page one and the index first in the byte stream, so a
  range-request reader can paint page one before the rest arrives. Only pays off if the viewer
  streams over range requests — worth adding together, not separately. **[doc]**
- **Object streams / garbage collection** (`mutool clean -gggg`) shrink metadata overhead. Minor
  next to image recompression. **[doc]**
- **Where to run it.** Client-side is wrong: it needs CPU the phone doesn't have to spare and it
  mutates the user's file. Options: a one-shot offline pass over the library, or upload-side.
  Decision needed — this is the one item on this list with a product cost, not just an engineering
  cost.

Measured context for the ceiling: the same 19.65 MB scan took ~5 s to first page and grew memory
from ~600 MB to 1–1.5 GB after 30 flings. **[measured]**

## 5. `androidx.pdf` — real, but blocked

Google's `androidx.pdf:pdf-viewer` is framework-backed and does exactly what a phone needs: renders
only visible pages and **releases the bitmaps of off-screen pages**; the docs call that out as the
memory strategy. **[doc]** `developer.android.com/develop/ui/views/layout/pdf/implement-pdf-viewer`.

Blockers:

- Requires **API 31+ and SDK extension level 13** at runtime for `PdfViewerFragment`. The library's
  `minSdk` was backported to 28, but the fragment itself still throws below the SDK-extension
  requirement. This project's `minSdk` is **26**. **[doc]** + roadmap's own unverified suspicion,
  now confirmed.
- Reported performance problems above **250 MB**. **[doc]** release notes.
- Adopting it means replacing the pdf.js viewer, i.e. discarding Phases 0–2 and inheriting a
  different (or non-existent) annotation model that must still sync with desktop. **[inference]**

Verdict: not a drop-in, and not a reason to stop Phase 3. Revisit only if the app ever raises
`minSdk` to 31 and the PDF surface needs to become "native viewer quality". Android 15+ also ships
a system PDF viewer through MediaProvider — useful as an external-open target, not as an embedded
reader with sync. **[doc]**

Commercial SDKs (Nutrient/PSPDFKit, Apryse, ComPDF, Foxit) are the paid path to native-grade
performance, annotation and OCR. They are viable and expensive; the comparison pages are all
vendor-authored, so their benchmarks are not evidence. **[vendor]**

## 6. Reflow, crop and the reading experience

- **Auto-crop margins.** Most PDFs waste 20–30% of the width on margins. Cropping to the text box
  is a large readability win on a phone and works on scanned pages too. `k2pdfopt` is the reference
  implementation (open source, and available compiled to WASM); the cheap version is a per-page
  crop box computed from the text layer. **[doc]** `k2pdfopt.com`
- **Reflow / text mode.** `k2pdfopt -tm` re-flows text for small screens, including scanned pages.
  The roadmap already lists "modo texto" as an optional future phase; this is the tool that makes
  it credible. Quality is variable on multi-column and math-heavy documents. **[doc]** +
  **[inference]**
- **Dark theme.** Only three options exist for a canvas-rendered page: chrome-only, `filter:
  invert()` (inverts images too), or per-page pixel recolor (costly, still harms images). Font size
  and spacing cannot be changed at all — it is fixed layout.
- **On-device OCR** (ML Kit Text Recognition v2, bundled or downloaded model, runs offline, API
  21+). Produces a text layer for scanned PDFs, which is what makes selection, search, highlights
  and translation work on them. **[doc]** `developers.google.com/ml-kit/vision/text-recognition/v2/android`.
  The one caveat that matters: ML Kit returns text blocks with boxes, so a synthetic invisible text
  layer is buildable but needs its own alignment work — the desktop `textLayer` alignment spec
  (memory `#697`) is the cautionary tale.

## 7. What I would do, in order

1. **Cancel-on-scroll + zero-the-canvas on teardown** and re-measure `EGL mtrack` on the 19.65 MB
   scan. Two hours, and it either kills the memory defect or rules out the cheapest explanation.
2. **Decide the ingestion question** (§4). If scanned PDFs are a real use case, downsampling is the
   whole ballgame.
3. **Add the OCR text layer for scanned PDFs** — it converts an unsupported document into one where
   every feature built in Phases 2–4 already works.
4. **Upgrade pdf.js to the 6.5 line** when Phase 3 closes, not during it.
5. Reflow/crop as a Phase 6 conversation, not now.

## 8. Sources

- pdf.js API (`useWasm`, `maxCanvasPixels`, `enableHWA`), `src/display/api.js` v6.5.438 —
  `github.com/mozilla/pdf.js/blob/v6.5.438/src/display/api.js`
- pdf.js mobile canvas limit, `web/app_options.js` — 5 MP on Android/iOS
- pdf.js releases (incl. `enableHWA` removal, PR #20849; decoder work in 6.4/6.5) —
  `github.com/mozilla/pdf.js/releases`; version context: Wikipedia "PDF.js" (stable 6.5)
- WebView memory model — `developer.android.com/topic/performance/memory/guide/webview-memory`;
  `.../layout/webapps/manage-webview-memory`
- `androidx.pdf` requirements and off-screen bitmap release —
  `developer.android.com/develop/ui/views/layout/pdf/implement-pdf-viewer`;
  `developer.android.com/jetpack/androidx/releases/pdf`
- OffscreenCanvas + worker rendering — `developer.mozilla.org/en-US/docs/Web/API/OffscreenCanvas`;
  Nutrient, "Advanced PDF.js loading: streaming, range requests, workers" **[vendor]**
- Linearization — qpdf / mutool / ocrmypdf documentation; general "fast web view" literature
- k2pdfopt — `k2pdfopt.com`, `willus.com/k2pdfopt`
- ML Kit text recognition — `developers.google.com/ml-kit/vision/text-recognition/v2/android`
- Commercial SDK comparison — `nutrient.io/sdk/vs/*`, `apryse.com/alternatives/nutrient`,
  `compdf.com/blog/*` **[vendor]**

## 9. What is unverified

- Whether cancel-on-scroll or canvas zeroing actually reduces `EGL mtrack` here. Not measured.
- Whether the 6.5 line still runs on the emulator's WebView 124 legacy build. Must be tested; the
  legacy build exists precisely for that.
- The real speedup from downsampling this project's actual scans. The 70–90% figure is typical, not
  measured on these files.
- ML Kit's alignment quality against this app's existing highlight rects. The desktop spec suggests
  it is the hard part.
