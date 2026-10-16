// Domain subdirs — each one owns a coherent slice of the reader feature
// (a viewer, a UI subsystem, or a panel). See per-domain `index.ts` for the
// public surface. This root barrel re-exports the whole feature so external
// consumers (AppRouter, tests that go through `$lib/features/reader`) keep
// working without knowing the new layout.

// Chrome (reader layout: workspace, header, footer, controls, text settings,
// toc panel) + bookmarks state.
export {
  ReaderBottomBar,
  ReaderTextSettings,
  ReaderTocPanel,
  ReaderTopBar,
  ReaderWorkspace,
} from './chrome';
export * from './chrome/bookmarksState.svelte';

// Highlight system (toolbars, modals, popovers, color tokens).
export {
  ColorPickerPopover,
  HighlightContextMenu,
  NoteEditorModal,
  SelectionToolbar,
  TagPopover,
} from './highlight';
export * from './highlight/highlightColors';

// Secondary feature panels.
export { BookmarksPanel, SearchPanel } from './panels';

// EPUB viewer + sub-components + scripts.
export {
  EpubNativeViewer,
  EpubTocSidebar,
  IFRAME_CFI_BRIDGE_SCRIPT,
  IFRAME_HIGHLIGHT_OVERLAY_SCRIPT,
} from './viewer-epub';
export * from './viewer-epub/cfiBridge';
export * from './viewer-epub/epub';
export * from './viewer-epub/epubCache';
export * from './viewer-epub/keyboardNav';

// PDF viewer + sub-components + scripts + state.
export { PdfLoadingOverlay, PdfSelectionOverlay, PdfTocSidebar, PdfViewer } from './viewer-pdf';
export {
  scaleOptions,
  TOOLBAR_OFFSET,
  TOOLBAR_WIDTH_ESTIMATE,
  TOOLBAR_EDGE_PADDING,
  VERTICAL_SCROLL_STEP_PX,
  ZOOM_COMMIT_DELAY_MS,
  ZOOM_EPSILON,
  SELECTION_X_PADDING_PX,
  SELECTION_Y_INSET_PX,
  SELECTION_LINE_TOLERANCE_PX,
  type ReaderThemePalette,
  type SelectionOverlayRect,
  clamp,
  clampSelectionPoint,
  resolveThemePalette,
  calculateScale,
  formatPageNumber,
} from './viewer-pdf/pdfState.svelte';
export * from './viewer-pdf/pdfNavigation';
export * from './viewer-pdf/pdfSelection';
export * from './viewer-pdf/pdfStreaming';
export * from './viewer-pdf/safeTextLayer';
