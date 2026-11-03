<script lang="ts">
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import type { MessageKey } from '$lib/shared/i18n';
  import { HIGHLIGHT_COLORS } from '$lib/features/reader/highlight/highlightColors';
  import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
  import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';
  import type { NoTranslationReason } from '$lib/shared/services/translation/translationLanguageResolution';

  /**
   * One message per refusal reason. Each names the fix rather than the failure:
   * "the book has no language, choose one in Settings" is actionable, a bare
   * "no translation available" is not.
   */
  const TRANSLATION_REFUSAL_KEYS: Record<NoTranslationReason, MessageKey> = {
    'same-language': 'reader.translationSameLanguage',
    'unknown-source': 'reader.translationUnknownSource',
    'invalid-source': 'reader.translationInvalidSource',
    'invalid-target': 'reader.translationInvalidTarget',
  };

  // The full data we need to persist a highlight. The parent captures this at
  // selection time and passes it back to us as a prop so that the click on a
  // color can forward it directly to the parent's handler. This avoids a race
  // with the browser's `selectionchange` event: even if the parent's global
  // selection state is cleared before our click handler runs, the data the
  // handler needs is already in the argument we pass.
  export type SelectionData = {
    text: string;
    bounds: { left: number; top: number; right: number; bottom: number };
    rects: Array<{ left: number; top: number; width: number; height: number }>;
    pageNumber: number;
    cfi: string | null;
    /** EPUB evidence: the containing paragraph, or null when none was found. */
    quote?: string | null;
    /** EPUB evidence: the chapter the selection came from. */
    chapterTitle?: string | null;
  };

  type Props = {
    // Kept in the contract for callers that only have the raw string; the
    // toolbar itself reads `selectionData`, which carries the same text plus
    // the EPUB evidence the capture path needs.
    selectedText: string;
    selectionBounds: { left: number; top: number; right: number; bottom: number };
    containerRect: { left: number; top: number; width: number; height: number };
    selectionData: SelectionData | null;
    onCopy: () => void;
    onAddToDictionary: (data: SelectionData) => Promise<DictionaryCaptureFeedback>;
    onTranslate: (data: SelectionData) => Promise<TranslationOutcome>;
    onColorSelect: (color: string, data: SelectionData) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let {
    selectionBounds,
    containerRect,
    selectionData,
    onCopy,
    onAddToDictionary,
    onTranslate,
    onColorSelect,
    t,
  }: Props = $props();

  let selectedColor = $state(HIGHLIGHT_COLORS[0].hex);
  let copyFeedback = $state<string | null>(null);
  let dictionaryFeedback = $state<string | null>(null);
  let copyFeedbackTimer: ReturnType<typeof setTimeout> | null = null;
  let dictionaryFeedbackTimer: ReturnType<typeof setTimeout> | null = null;
  let translation = $state<TranslationOutcome | null>(null);
  let translating = $state(false);
  const translationActive = $derived(translation !== null ? 'bg-white/10' : '');

  const TOOLBAR_HEIGHT_ESTIMATE = 56;
  const TOOLBAR_WIDTH_ESTIMATE = 260;
  const TOOLBAR_EDGE_PADDING = 16;
  const TOOLBAR_OFFSET = 16;

  // One localized message per capture outcome (REQ-DRE-014). `error` reuses the
  // app's generic failure copy: the reader has no capture-specific error the
  // user can act on, and inventing one would add a key no requirement names.
  const DICTIONARY_FEEDBACK_KEYS: Record<DictionaryCaptureFeedback, MessageKey> = {
    created: 'reader.addedToDictionary',
    'evidence-updated': 'reader.dictionaryEvidenceUpdated',
    'already-in-dictionary': 'reader.dictionaryAlreadyInDictionary',
    'no-match': 'reader.dictionaryNoMatch',
    ambiguous: 'reader.dictionarySeveralMatches',
    error: 'error.somethingWrong',
  };

  const selectionCenterX = $derived((selectionBounds.left + selectionBounds.right) / 2);
  const viewerAnchorX = $derived(
    Math.max(
      TOOLBAR_EDGE_PADDING + TOOLBAR_WIDTH_ESTIMATE / 2,
      Math.min(
        selectionCenterX,
        containerRect.width - TOOLBAR_EDGE_PADDING - TOOLBAR_WIDTH_ESTIMATE / 2,
      ),
    ),
  );
  const viewerToolbarX = $derived(Math.max(0, viewerAnchorX - TOOLBAR_WIDTH_ESTIMATE / 2));
  const viewerToolbarY = $derived(
    selectionBounds.top > TOOLBAR_HEIGHT_ESTIMATE + TOOLBAR_OFFSET
      ? selectionBounds.top - TOOLBAR_HEIGHT_ESTIMATE - TOOLBAR_OFFSET
      : selectionBounds.bottom + TOOLBAR_OFFSET,
  );

  const toolbarX = $derived(containerRect.left + viewerToolbarX);
  const toolbarY = $derived(containerRect.top + viewerToolbarY);

  function selectColor(hex: string): void {
    selectedColor = hex;
    // Forward the selection data captured at mount time so the parent's
    // handler can persist the highlight even if the browser's
    // selectionchange has already cleared its global selection state.
    if (selectionData) {
      onColorSelect(hex, selectionData);
    }
  }

  function handleCopy(): void {
    onCopy();
    copyFeedback = t('reader.copiedToClipboard');
    if (copyFeedbackTimer) clearTimeout(copyFeedbackTimer);
    copyFeedbackTimer = setTimeout(() => {
      copyFeedback = null;
      copyFeedbackTimer = null;
    }, 1500);
  }

  async function handleAddToDictionary(): Promise<void> {
    // The parent resolves the selection and writes at most one entry, so the
    // evidence captured with the selection (quote + chapter) travels in the
    // argument instead of the trimmed word string it replaced.
    if (!selectionData) return;
    if (!selectionData.text.trim()) return;

    const feedback = await onAddToDictionary(selectionData);
    dictionaryFeedback = t(DICTIONARY_FEEDBACK_KEYS[feedback] ?? 'reader.addedToDictionary');
    if (dictionaryFeedbackTimer) clearTimeout(dictionaryFeedbackTimer);
    dictionaryFeedbackTimer = setTimeout(() => {
      dictionaryFeedback = null;
      dictionaryFeedbackTimer = null;
    }, 1500);
  }

  /**
   * Translate toggles a result panel. A second click (or the panel's close
   * button) clears it, so the selection stays usable while the result is up.
   * The request itself lives in the parent: the toolbar is presentational and
   * owns no port.
   */
  async function handleTranslate(): Promise<void> {
    if (translation) {
      translation = null;
      return;
    }
    if (!selectionData || !selectionData.text.trim()) return;

    translating = true;
    try {
      translation = await onTranslate(selectionData);
    } finally {
      translating = false;
    }
  }
</script>

<div
  class="selection-toolbar fixed z-50"
  style="left: {toolbarX}px; top: {toolbarY}px;"
  role="presentation"
  in:scale={{ duration: 140, start: 0.92, easing: cubicOut }}
  out:scale={{ duration: 100, start: 0.95, easing: cubicOut }}
  onmouseup={(e) => e.stopPropagation()}
>
  <!-- Tip arrow pointing to selection -->
  <div
    class="mx-auto h-2.5 w-5"
    style="clip-path: polygon(50% 100%, 0 0, 100% 0); background: var(--color-highlight-menu-bg);"
  ></div>

  <!-- Main toolbar -->
  <div
    class="flex items-center gap-3 rounded-full border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) px-4 py-2 shadow-xl"
    role="toolbar"
    aria-label={t('highlight.menuAriaLabel')}
  >
    <!-- Color circles -->
    {#each HIGHLIGHT_COLORS as color}
      <button
        type="button"
        class="h-6 w-6 cursor-pointer rounded-full transition-transform hover:scale-110"
        class:ring-2={selectedColor === color.hex}
        class:ring-white={selectedColor === color.hex}
        style="background-color: {color.hex};"
        onclick={() => selectColor(color.hex)}
        aria-label={t('highlight.selectColor', { color: t(color.i18nKey) })}
      ></button>
    {/each}

    <!-- Separator -->
    <span class="text-base font-normal text-(--color-text-auxiliary)">|</span>

    <!-- Copy -->
    <div class="group relative">
      <button
        type="button"
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
        onclick={handleCopy}
        aria-label={t('reader.copiar')}
        title={t('reader.copiar')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
        </svg>
      </button>
      <span
        class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100"
        role="tooltip"
      >
        {t('reader.copiar')}
      </span>
    </div>

    <!-- Add to Dictionary -->
    <div class="group relative">
      <button
        type="button"
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
        onclick={handleAddToDictionary}
        aria-label={t('reader.addToDictionary')}
        title={t('reader.addToDictionary')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"></path>
          <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"></path>
          <path d="M6 8h2"></path>
          <path d="M6 12h2"></path>
          <path d="M16 8h2"></path>
          <path d="M16 12h2"></path>
        </svg>
      </button>
      <span
        class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100"
        role="tooltip"
      >
        {t('reader.addToDictionary')}
      </span>
    </div>

    <!-- Translate -->
    <div class="group relative">
      <button
        type="button"
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky) {translationActive}"
        onclick={handleTranslate}
        disabled={translating}
        aria-label={t('reader.translate')}
        title={t('reader.translate')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="M4 5h12"></path>
          <path d="M10 5v2c0 4-2.5 7-6 9"></path>
          <path d="M6 11c1.5 2.5 4 4 7 4.5"></path>
          <path d="M14 21l4-9 4 9"></path>
          <path d="M15.5 18h5"></path>
        </svg>
      </button>
      <span
        class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100"
        role="tooltip"
      >
        {t('reader.translate')}
      </span>
    </div>
  </div>

  <!-- Action feedback (copy / dictionary) -->
  {#if copyFeedback || dictionaryFeedback}
    <div
      class="mt-2 rounded-lg bg-(--color-highlight-menu-bg) px-3 py-1 text-center text-xs text-(--color-text-inverse) shadow-lg"
      role="status"
      aria-live="polite"
    >
      {copyFeedback ?? dictionaryFeedback}
    </div>
  {/if}

  <!-- Translation result. `no-result` (echo / entity / untranslatable) and
       `unavailable` (offline / quota / HTTP) are shown apart on purpose: one
       is the service answering, the other is the service failing. -->
  {#if translating || translation}
    <div
      class="mt-2 max-w-80 rounded-lg bg-(--color-highlight-menu-bg) px-3 py-2 text-(--color-text-inverse) shadow-lg"
      role="status"
      aria-live="polite"
    >
      {#if translating}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translating')}</p>
      {:else if translation?.status === 'ok'}
        <div class="flex items-start gap-2">
          <p class="min-w-0 grow text-sm wrap-break-word">{translation.text}</p>
          <button
            type="button"
            class="shrink-0 cursor-pointer rounded text-xs text-(--color-text-auxiliary) hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
            onclick={() => (translation = null)}
            aria-label={t('reader.dismissTranslation')}
            title={t('reader.dismissTranslation')}
          >
            ✕
          </button>
        </div>
      {:else if translation?.status === 'no-result'}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translationNoResult')}</p>
      {:else if translation?.status === 'unavailable'}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translationUnavailable')}</p>
      {:else if translation?.status === 'refused'}
        <p class="text-xs text-(--color-text-auxiliary)">
          {t(TRANSLATION_REFUSAL_KEYS[translation.reason])}
        </p>
      {/if}
    </div>
  {/if}
</div>
