<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import {
    TRANSLATION_LANGUAGE_OPTIONS,
    languageLabel,
  } from '$lib/shared/services/translation/translationLanguageOptions';

  /**
   * The language pair a translation ran with, as a bar the reader can fix in
   * place. A refusal that names the pair but cannot change it is a dead end;
   * this is what makes "the book has no language, choose one" actionable without
   * leaving the reader.
   *
   * Presentational only: it takes the resolved pair, reports a new pair upward
   * and owns nothing else. It does not fetch, read settings or persist — the
   * parent owns the request and the write.
   */
  type Props = {
    /** Resolved source code, or null when unknown (automatic). */
    source: string | null;
    /** Resolved target code, or null when unknown (automatic). */
    target: string | null;
    /** Suppresses interaction while a translation is in flight. */
    disabled?: boolean;
    onChange: (next: { source: string | null; target: string | null }) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { source, target, disabled = false, onChange, t }: Props = $props();

  /**
   * The "automatic" entry carries the empty string because a native `select`
   * cannot hold a null value; it is mapped back to `null` — the documented
   * value — on the way out, so `onChange` never sees `''`.
   */
  const AUTO_VALUE = '';

  const sourceValue = $derived(source ?? AUTO_VALUE);
  const targetValue = $derived(target ?? AUTO_VALUE);

  const languageOptions = $derived(
    TRANSLATION_LANGUAGE_OPTIONS.map((code) => ({
      value: code,
      label: languageLabel(code, (key) => t(key as MessageKey)),
    })),
  );

  /** The automatic label for a side: the source reads "from the book", the
   * target "app language", because which one is inferred differs. */
  const sourceAutoLabel = $derived(t('settings.translation.sourceAuto'));
  const targetAutoLabel = $derived(t('settings.translation.targetAuto'));

  function handleSourceChange(event: Event): void {
    const value = (event.currentTarget as HTMLSelectElement).value;
    onChange({ source: value === AUTO_VALUE ? null : value, target });
  }

  function handleTargetChange(event: Event): void {
    const value = (event.currentTarget as HTMLSelectElement).value;
    onChange({ source, target: value === AUTO_VALUE ? null : value });
  }

  const selectClass =
    'min-w-0 grow cursor-pointer rounded-md border border-(--color-highlight-menu-border) ' +
    'bg-white/10 px-2 py-1 text-xs text-(--color-text-inverse) ' +
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky) ' +
    'disabled:cursor-not-allowed disabled:opacity-40';
</script>

<div class="flex items-center gap-2">
  <label class="sr-only" for="translation-source-language">
    {t('settings.translation.sourceLabel')}
  </label>
  <select
    id="translation-source-language"
    class={selectClass}
    value={sourceValue}
    {disabled}
    onchange={handleSourceChange}
  >
    <option value={AUTO_VALUE}>{sourceAutoLabel}</option>
    {#each languageOptions as option (option.value)}
      <option value={option.value}>{option.label}</option>
    {/each}
  </select>

  <span class="shrink-0 text-xs text-(--color-text-auxiliary)" aria-hidden="true">→</span>

  <label class="sr-only" for="translation-target-language">
    {t('settings.translation.targetLabel')}
  </label>
  <select
    id="translation-target-language"
    class={selectClass}
    value={targetValue}
    {disabled}
    onchange={handleTargetChange}
  >
    <option value={AUTO_VALUE}>{targetAutoLabel}</option>
    {#each languageOptions as option (option.value)}
      <option value={option.value}>{option.label}</option>
    {/each}
  </select>
</div>
