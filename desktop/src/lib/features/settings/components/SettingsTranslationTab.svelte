<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import type { TranslationCacheEntryDto } from '$lib/shared/types';
  import {
    TRANSLATION_LANGUAGE_OPTIONS,
    languageLabel,
  } from '$lib/shared/services/translation/translationLanguageOptions';

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
    onSourceChange: (value: string | null) => void;
    onTargetChange: (value: string | null) => void;
    onSave: () => void;
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

  const draftSource = $derived(sourceLanguage ?? INFER_VALUE);
  const draftTarget = $derived(targetLanguage ?? INFER_VALUE);

  const isDirty = $derived(
    (sourceLanguage ?? null) !== (draftSource === INFER_VALUE ? null : draftSource) ||
      (targetLanguage ?? null) !== (draftTarget === INFER_VALUE ? null : draftTarget),
  );

  function handleSourceChange(value: string): void {
    onSourceChange(value === INFER_VALUE ? null : value);
  }

  function handleTargetChange(value: string): void {
    onTargetChange(value === INFER_VALUE ? null : value);
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
        value={draftSource}
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
        value={draftTarget}
        onchange={(detail) => handleTargetChange(detail.value)}
        class="w-full"
      />
      <p class="text-xs text-(--color-text-muted)">{t('settings.translation.targetHint')}</p>
    </div>

    <Button variant="primary" loading={isSaving} onclick={onSave} disabled={!isDirty}>
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
