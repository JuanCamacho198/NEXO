/**
 * Handler manifest — the only place a shortcut row may point at.
 *
 * Every `ShortcutEntry.handlerKey` must be a key of `WIRED_HANDLER_SOURCES`.
 * The value names the code that actually performs the action; it is the
 * evidence the coherence test (`shortcutRegistry.test.ts`) checks against. A
 * new row that names a handler which is not in this manifest fails the test,
 * so the list cannot document a shortcut the app does not implement.
 *
 * The three `app.*` handlers are functions the app-level dispatcher really
 * invokes (see `globalShortcuts.ts`); the rest are wired inside their own
 * feature and cross-checked by file:line here.
 */
export const WIRED_HANDLER_SOURCES = {
  // App-level dispatcher (registry-driven): globalShortcuts.ts
  'app.toggleTheme': 'shared/shortcuts/globalShortcuts.ts',
  'app.fullscreen': 'shared/shortcuts/globalShortcuts.ts',
  'app.toggleHelp': 'shared/shortcuts/globalShortcuts.ts',
  // Library search — features/library/components/LibraryShelfScreen.svelte:70
  // and features/highlights/components/HighlightsView.svelte:112
  'library.search': 'features/library/components/LibraryShelfScreen.svelte:70',
  // Reader search — features/reader/chrome/useReaderShortcuts.svelte.ts
  'reader.search': 'features/reader/chrome/useReaderShortcuts.svelte.ts',
  // Reader fullscreen (immersive) — useImmersiveChrome.svelte.ts:115
  'reader.toggleFullscreen': 'features/reader/chrome/useImmersiveChrome.svelte.ts:115',
  // Reader zoom — useReaderZoom.svelte.ts:128 (also EpubNativeViewer.svelte:377)
  'reader.zoomIn': 'features/reader/chrome/useReaderZoom.svelte.ts:128',
  'reader.zoomOut': 'features/reader/chrome/useReaderZoom.svelte.ts:128',
  // Reader navigation — EpubNativeViewer.svelte:375-376, PdfViewer.svelte:326-334
  'reader.prev': 'features/reader/viewer-epub/EpubNativeViewer.svelte:375',
  'reader.next': 'features/reader/viewer-epub/EpubNativeViewer.svelte:376',
  // Vertical scroll — PDF only, viewer focused: PdfViewer.svelte:336-343
  'reader.scrollUp': 'features/reader/viewer-pdf/PdfViewer.svelte:336',
  'reader.scrollDown': 'features/reader/viewer-pdf/PdfViewer.svelte:341',
  // Dialogs and menus — bits-ui Dialog (shared/ui/layout/Modal.svelte) plus the
  // local handlers; the settings overlay closes on Escape at SettingsPanel.
  'dialog.close': 'shared/ui/layout/Modal.svelte',
} as const;

export type ShortcutHandlerKey = keyof typeof WIRED_HANDLER_SOURCES;

export const WIRED_HANDLER_KEYS: ReadonlySet<string> = new Set(Object.keys(WIRED_HANDLER_SOURCES));
