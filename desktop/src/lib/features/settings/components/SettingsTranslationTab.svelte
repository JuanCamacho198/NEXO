<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import type { TranslationCacheEntryDto } from '$lib/shared/types';
  import {
    TRANSLATION_LANGUAGE_OPTIONS,
    languageLabel,
  } from '$lib/shared/services/translation/translationLanguageOptions';
  import { normalizeLanguageTag } from '$lib/shared/services/translation/translationLanguages';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    sourceLanguage: string | null;
    targetLanguage: string | null;
    history: TranslationCacheEntryDto[];
    isLoading?: boolean;
    isSaving?: boolean;
    isClearingHistory?: boolean;
    error?: string | null;
    /**
     * Renders the tab's own title and subtitle. Turned OFF when the block is
     * embedded in "Menú de selección", which owns the title for the section —
     * two headings for one block would read as two settings.
     *
     * The tabpanel wrapper is skipped with it, so the block cannot nest a
     * second `role="tabpanel"` inside the tab's own panel.
     */
    showHeader?: boolean;
    /** Saves immediately: the definition selector is not behind the pair's Save button. */
    onSourceChange: (value: string | null) => void;
    onTargetChange: (value: string | null) => void;
    /** Receives the PAIR AS EDITED, not the stored one: the stored pair is what Save is meant to replace. */
    onSave: (sourceLanguage: string | null, targetLanguage: string | null) => void;
    onClearHistory: () => void;
  };

  let {
    t,
    sourceLanguage,
    targetLanguage,
    history,
    isLoading = false,
    isSaving = false,
    isClearingHistory = false,
    error = null,
    showHeader = true,
    onSourceChange,
    onTargetChange,
    onSave,
    onClearHistory,
  }: Props = $props();

  // The "infer" option carries the empty string because Dropdown's underlying
  // Select treats `''` as the placeholder slot; it is mapped back to `null` — the
  // documented "infer" value — on the way out, so the command never sees `''`.
  const INFER_VALUE = '';

  const sourceOptions = $derived([
    { value: INFER_VALUE, label: t('settings.translation.sourceAuto') },
    ...TRANSLATION_LANGUAGE_OPTIONS.map((code) => ({
      value: code,
      label: languageLabel(code, (key) => t(key as MessageKey)),
    })),
  ]);

  const targetOptions = $derived([
    { value: INFER_VALUE, label: t('settings.translation.targetAuto') },
    ...TRANSLATION_LANGUAGE_OPTIONS.map((code) => ({
      value: code,
      label: languageLabel(code, (key) => t(key as MessageKey)),
    })),
  ]);

  // ── The draft ────────────────────────────────────────────────────────────
  // The pair the reader sees, the pair the warning describes and the pair Save
  // persists are the same two values, so the three cannot disagree — which is
  // exactly what broke while "the draft" was a `$derived` read of the props, a
  // value no reader could change: `isDirty` compared the props to a copy of the
  // props and was permanently `false`, so Save was permanently dead and the
  // change was lost when the tab unmounted.
  //
  // The draft is the stored pair OVERLAID with whatever the reader has changed
  // and not yet saved. That shape is what makes the seeding question disappear:
  // a side with no pending edit IS the prop, so a preference load landing — even
  // one that arrives after mount — needs no syncing at all, and an edit in
  // progress can never be clobbered by one, because the overlay outranks it.
  //
  // `undefined` means "no pending edit"; an explicit `null` is a pending edit to
  // "Automatic", and the two must not be confused.
  let pendingSource = $state<string | null | undefined>(undefined);
  let pendingTarget = $state<string | null | undefined>(undefined);

  /**
   * A pending edit CLEARS itself the moment the stored pair catches up: the
   * overlay is only kept while it still differs from what is stored.
   *
   * This is the whole of the save round-trip. The parent echoes back the pair it
   * was given; the stored value becomes that pair; the overlay no longer differs
   * from it and is dropped by itself. No effect, no settling command, and
   * nothing to get out of order — and a LATER external change (a preference
   * load, a rename from elsewhere) then shows through, because the overlay is
   * already gone by the time it arrives.
   */
  const effectivePendingSource = $derived(
    pendingSource !== undefined && pendingSource !== (sourceLanguage ?? null)
      ? pendingSource
      : undefined,
  );
  const effectivePendingTarget = $derived(
    pendingTarget !== undefined && pendingTarget !== (targetLanguage ?? null)
      ? pendingTarget
      : undefined,
  );

  const draftSourceValue = $derived(effectivePendingSource ?? sourceLanguage ?? null);
  const draftTargetValue = $derived(effectivePendingTarget ?? targetLanguage ?? null);

  /**
   * The dropdown contract: `''` is the placeholder slot of the underlying
   * Select, and the stored/edited value is `null`.
   */
  const draftSourceOption = $derived(draftSourceValue === null ? INFER_VALUE : draftSourceValue);
  const draftTargetOption = $derived(draftTargetValue === null ? INFER_VALUE : draftTargetValue);

  const storedSourceValue = $derived(sourceLanguage ?? null);
  const storedTargetValue = $derived(targetLanguage ?? null);

  /** True (and only true) when the reader has edited something not yet saved. */
  const isDirty = $derived(
    draftSourceValue !== storedSourceValue || draftTargetValue !== storedTargetValue,
  );

  /**
   * True when both sides name the SAME language, which is the pair the service
   * refuses (`same-language`): nothing can be translated from a language into
   * itself.
   *
   * It reads the DRAFT, so it always describes the pair on screen — a warning
   * about a pair the reader has already changed away from is a warning about
   * nothing.
   *
   * It reports the resolver's verdict, not a guess of its own, so it uses the
   * SAME normalisation the resolver uses (`normalizeLanguageTag`): `en-US` and
   * `en-GB` are one language, exactly as they are for MyMemory, which keys on
   * the primary subtag.
   *
   * "Automatic" (`null`) is NOT degenerate. An unresolved side is a statement
   * about the app's inference, not about the pair: the source may still come
   * from the book and the target from the app locale, and those are resolved
   * elsewhere. Calling that a collision would warn about a pair that may
   * translate perfectly well.
   */
  const isDegeneratePair = $derived.by(() => {
    if (draftSourceValue === null || draftTargetValue === null) return false;
    const normalizedSource = normalizeLanguageTag(draftSourceValue);
    const normalizedTarget = normalizeLanguageTag(draftTargetValue);
    if (normalizedSource === null || normalizedTarget === null) return false;
    return normalizedSource === normalizedTarget;
  });

  /**
   * The shared language, named for the reader: the normalised code maps to a
   * catalogue option, and falls back to the bare code when the pair was stored
   * with a variant the option list does not carry (`en-GB`).
   */
  const degenerateLanguageLabel = $derived.by(() => {
    if (!isDegeneratePair) return '';
    const normalized = normalizeLanguageTag(draftSourceValue);
    if (normalized === null) return '';
    return languageLabel(normalized, (key) => t(key as MessageKey));
  });

  /**
   * An edit is recorded as an OVERLAY on the stored pair, never as a copy of it:
   * that is what makes an edit survive anything the parent does, and what makes
   * an outside change visible for free on the side the reader has not touched.
   */
  function handleSourceChange(value: string): void {
    const next = value === INFER_VALUE ? null : value;
    pendingSource = next;
    onSourceChange(next);
  }

  function handleTargetChange(value: string): void {
    const next = value === INFER_VALUE ? null : value;
    pendingTarget = next;
    onTargetChange(next);
  }

  function handleSave(): void {
    onSave(draftSourceValue, draftTargetValue);
  }

  function formatDate(iso: string): string {
    const parsed = new Date(iso);
    if (Number.isNaN(parsed.getTime())) return iso;
    return parsed.toLocaleDateString();
  }
</script>

{#if showHeader}
  <div
    role="tabpanel"
    id="tabpanel-traduccion"
    aria-labelledby="tab-traduccion"
    class="relative flex-1 overflow-y-auto p-4 flex flex-col gap-4"
  >
    <section class="space-y-5 w-full max-w-none">
      <header class="flex flex-col gap-1">
        <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
          {t('settings.translation.title')}
        </h1>
        <p class="text-sm text-(--color-text-muted)">{t('settings.translation.subtitle')}</p>
      </header>

      {#if error}
        <p
          class="rounded-lg border border-(--color-danger) px-3 py-2 text-sm text-(--color-danger)"
        >
          {error}
        </p>
      {/if}

      {@render body()}
    </section>
  </div>
{:else}
  {@render body()}
{/if}

{#snippet body()}
  <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-4">
    <div class="flex flex-col gap-2">
      <label class="text-sm font-medium text-(--color-primary)" for="translation-source">
        {t('settings.translation.sourceLabel')}
      </label>
      <Dropdown
        options={sourceOptions}
        value={draftSourceOption}
        onchange={(detail) => handleSourceChange(detail.value)}
        class="w-full"
      />
      <p class="text-xs text-(--color-text-muted)">{t('settings.translation.sourceHint')}</p>
    </div>

    <div class="flex flex-col gap-2">
      <label class="text-sm font-medium text-(--color-primary)" for="translation-target">
        {t('settings.translation.targetLabel')}
      </label>
      <Dropdown
        options={targetOptions}
        value={draftTargetOption}
        onchange={(detail) => handleTargetChange(detail.value)}
        class="w-full"
      />
      <p class="text-xs text-(--color-text-muted)">{t('settings.translation.targetHint')}</p>
    </div>

    {#if isDegeneratePair}
      <p
        data-testid="translation-same-language-warning"
        class="rounded-lg border border-(--color-warning)/40 bg-(--color-warning)/10 px-3 py-2 text-sm text-(--color-primary)"
      >
        {t('settings.translation.sameLanguageWarning', { language: degenerateLanguageLabel })}
      </p>
    {/if}

    <Button variant="primary" loading={isSaving} onclick={handleSave} disabled={!isDirty}>
      {t('settings.translation.save')}
    </Button>
  </div>

  <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-4">
    <header class="flex items-center justify-between gap-3">
      <h2 class="text-xl font-semibold text-(--color-primary)">
        {t('settings.translation.historyTitle')}
      </h2>
      <Button
        variant="danger"
        size="sm"
        loading={isClearingHistory}
        disabled={history.length === 0}
        onclick={onClearHistory}
      >
        {t('settings.translation.clearHistory')}
      </Button>
    </header>

    {#if isLoading}
      <p class="text-sm text-(--color-text-muted)">{t('settings.translation.loading')}</p>
    {:else if history.length === 0}
      <p class="text-sm text-(--color-text-muted)">{t('settings.translation.historyEmpty')}</p>
    {:else}
      <ul class="flex flex-col divide-y divide-(--color-border)">
        {#each history as entry (entry.cacheKey)}
          <li class="flex flex-col gap-1 py-3">
            <div class="flex items-center gap-2 text-xs text-(--color-text-muted)">
              <span class="font-medium uppercase">{entry.sourceLanguage}</span>
              <span aria-hidden="true">→</span>
              <span class="font-medium uppercase">{entry.targetLanguage}</span>
              <span aria-hidden="true">·</span>
              <time datetime={entry.createdAt}>{formatDate(entry.createdAt)}</time>
            </div>
            <p class="text-sm text-(--color-primary) wrap-break-word">{entry.originalText}</p>
            <p class="text-sm text-(--color-text-muted) wrap-break-word">
              {entry.translatedText}
            </p>
          </li>
        {/each}
      </ul>
    {/if}
  </div>
{/snippet}
