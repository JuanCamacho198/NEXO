<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import Dropdown, { type Option } from '$lib/shared/ui/navigation/Dropdown.svelte';
  import {
    TRANSLATION_LANGUAGE_OPTIONS,
    languageLabel,
  } from '$lib/shared/services/translation/translationLanguageOptions';
  import type { SourceOrigin } from '$lib/shared/services/translation/translationLanguageResolution';

  /**
   * The language pair a translation ran with, as a bar the reader can fix in
   * place. A refusal that names the pair but cannot change it is a dead end;
   * this is what makes "the book has no language, choose one" actionable without
   * leaving the reader.
   *
   * Presentational only: it takes the resolved pair, reports a new pair upward
   * and owns nothing else. It does not fetch, read settings or persist — the
   * parent owns the request and the write.
   *
   * The pair is rendered through the shared `Dropdown` rather than a native
   * `<select>`. A native popup is drawn by the OPERATING SYSTEM: it opened as a
   * large white panel over this dark floating menu, ignored the reader chrome
   * entirely, and clipped the trigger to `Automático (d...`.
   *
   * `Dropdown`'s DEFAULT look is styled on the SETTINGS tokens (`--color-surface`,
   * `--color-primary`, `--color-border`), while this menu is a dark surface
   * (`--color-highlight-menu-bg`, `--color-text-inverse`). The component is left
   * untouched — other callers depend on its appearance — and both its seams are
   * used instead:
   *
   * - `trigger` / `option` snippets replace what is RENDERED. The default trigger
   *   prints `Select.Value` and a fixed 16px chevron; the default option prints
   *   the bare label in `text-(--color-primary)`, which is dark-on-dark here.
   * - `class` is merged INTO `Select.Trigger`, so the dark chrome (background,
   *   border, text colour) is applied there. Every conflict is suffixed `!`
   *   because Tailwind resolves two utilities for the same property by their
   *   order in the generated stylesheet, NOT by their order in the class
   *   attribute — without the suffix the light `bg-(--color-surface)` from the
   *   component's own base classes would win roughly half the time.
   *
   * The trigger does NOT truncate: the label is `whitespace-nowrap` and the row
   * wraps (`flex-wrap`), so a wide pair moves the target side to a second line
   * instead of clipping `Auto (español)`.
   */
  type Props = {
    /** Resolved source code, or null when unknown (automatic). */
    source: string | null;
    /** Resolved target code, or null when unknown (automatic). */
    target: string | null;
    /**
     * Where the resolved source came from, or null when nothing resolved.
     *
     * Only an INFERRED source (`book`, `detected`) may be labelled "Auto (X)": a
     * language the reader picked in Settings is theirs and reads as the bare
     * language name. The distinction is not cosmetic — one is a promise the app
     * repeats back, the other a guess it made on their behalf.
     */
    sourceOrigin: SourceOrigin | null;
    /** Suppresses interaction while a translation is in flight. */
    disabled?: boolean;
    onChange: (next: { source: string | null; target: string | null }) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { source, target, sourceOrigin, disabled = false, onChange, t }: Props = $props();

  /**
   * The "automatic" entry carries the empty string because bits-ui's `Select`
   * treats `''` as the placeholder slot; it is mapped back to `null` — the
   * documented value — on the way out, so `onChange` never sees `''`.
   */
  const AUTO_VALUE = '';

  const sourceValue = $derived(source ?? AUTO_VALUE);
  const targetValue = $derived(target ?? AUTO_VALUE);

  const translate = (key: string): string => t(key as MessageKey);

  const languageOptions = $derived(
    TRANSLATION_LANGUAGE_OPTIONS.map((code) => ({
      value: code,
      label: languageLabel(code, translate),
    })),
  );

  /**
   * The automatic label for a side: the source reads "from the book", the target
   * "app language", because which one is inferred differs.
   */
  const sourceAutoLabel = $derived(t('settings.translation.sourceAuto'));
  const targetAutoLabel = $derived(t('settings.translation.targetAuto'));

  /** The source trigger's text, from the resolved code AND where it came from. */
  const sourceLabel = $derived(resolveSideLabel(source, sourceOrigin, sourceAutoLabel));

  /**
   * The target side has no origin to consult — it is the app locale, which is
   * always the reader's own — so it stays the generic automatic label.
   */
  const targetLabel = $derived(
    target === null ? targetAutoLabel : languageLabel(target, translate),
  );

  /**
   * What one side of the bar prints.
   *
   * Three cases, and which one applies is decided by the code AND its origin:
   *
   *   no code resolved   -> the generic automatic label. The side is automatic
   *                         and the app has nothing to name.
   *   `preference`       -> the bare language name. The reader picked it in
   *                         Settings, so labelling it "Auto" would contradict
   *                         their own choice.
   *   `book` / `detected`-> "Auto (español)". The app inferred the language and
   *                         names the inference, so the reader can judge it.
   *
   * It takes `code` as a parameter rather than closing over `source` because
   * TypeScript does not narrow a `$state`/prop read across the branches of a
   * `$derived` expression; passing the narrowed local in is what makes the
   * non-null branch type-check.
   */
  function resolveSideLabel(
    code: string | null,
    origin: SourceOrigin | null,
    autoLabel: string,
  ): string {
    if (code === null) return autoLabel;
    if (origin === null || origin === 'preference') return languageLabel(code, translate);
    return t('reader.translation.autoWithLanguage', { language: languageLabel(code, translate) });
  }

  const sourceOptions = $derived([
    { value: AUTO_VALUE, label: sourceAutoLabel },
    ...languageOptions,
  ]);
  const targetOptions = $derived([
    { value: AUTO_VALUE, label: targetAutoLabel },
    ...languageOptions,
  ]);

  function handleSourceChange(next: string): void {
    onChange({ source: next === AUTO_VALUE ? null : next, target });
  }

  function handleTargetChange(next: string): void {
    onChange({ source, target: next === AUTO_VALUE ? null : next });
  }

  /** Dark-menu chrome on top of `Dropdown`'s own trigger classes. */
  const triggerClass =
    'w-full gap-1 rounded-md border-(--color-highlight-menu-border)! bg-white/10! px-2 py-1 ' +
    'text-xs text-(--color-text-inverse)! hover:bg-(--color-highlight-menu-border) ' +
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky) ' +
    'disabled:cursor-not-allowed disabled:opacity-40';

  /**
   * The option row's text colour. It lives on the snippet's own element and not
   * in a `class` prop because `Dropdown` exposes none for items: the `option`
   * snippet replaces an item's CONTENT, while `Select.Item`'s base classes
   * (`text-(--color-primary)`) still paint the row. Inheriting a light-on-light
   * colour there would make the list unreadable, so the span restates it.
   */
  const optionLabelClass = 'text-(--color-text-inverse) whitespace-nowrap';
</script>

<div class="flex flex-wrap items-center gap-2">
  <Dropdown
    options={sourceOptions}
    value={sourceValue}
    onchange={(detail) => handleSourceChange(detail.value)}
    class={triggerClass}
    {disabled}
  >
    {#snippet trigger()}
      <span class="sr-only">{t('settings.translation.sourceLabel')}</span>
      <span class="min-w-0 grow text-left whitespace-nowrap">{sourceLabel}</span>
      <svg class="h-3 w-3 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
      </svg>
    {/snippet}
    {#snippet option(opt: Option)}
      <span class={optionLabelClass}>{opt.label}</span>
    {/snippet}
  </Dropdown>

  <span class="shrink-0 text-xs text-(--color-text-auxiliary)" aria-hidden="true">→</span>

  <Dropdown
    options={targetOptions}
    value={targetValue}
    onchange={(detail) => handleTargetChange(detail.value)}
    class={triggerClass}
    {disabled}
  >
    {#snippet trigger()}
      <span class="sr-only">{t('settings.translation.targetLabel')}</span>
      <span class="min-w-0 grow text-left whitespace-nowrap">{targetLabel}</span>
      <svg class="h-3 w-3 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
      </svg>
    {/snippet}
    {#snippet option(opt: Option)}
      <span class={optionLabelClass}>{opt.label}</span>
    {/snippet}
  </Dropdown>
</div>
