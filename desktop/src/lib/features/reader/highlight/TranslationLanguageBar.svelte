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
   * untouched — other callers depend on its appearance — and all three of its
   * seams are used instead:
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
   * - `contentClass` reaches the PORTALLED panel, which the trigger's `class`
   *   cannot: it is mounted under `document.body`, outside this component's
   *   scope. Without it the panel kept `Dropdown`'s light `bg-(--color-elevated)`
   *   and rendered a white slab over the dark menu.
   *
   * `markAsFloatingMenu` is what keeps the panel ALIVE. `ReaderWorkspace`
   * dismisses every floating menu on a capture-phase `pointerdown` unless the
   * press landed inside one, recognised by the `[data-floating-menu]` marker.
   * The panel is portalled OUT of the menu that owns it, so before this marker a
   * press on one of its options read as a press outside and closed the language
   * bar — the chosen language never landed. Measured on the running app: the
   * dismissal ring gained `workspace-pointerdown` and the menu count fell to
   * zero; with the marker, the same press produced no dismissal and the menu
   * stayed up.
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
     * language the reader chose — in Settings (`preference`) or for this book
     * (`bookPreference`) — is theirs and reads as the bare language name. The
     * distinction is not cosmetic: one is a promise the app repeats back, the
     * other a guess it made on their behalf.
     */
    sourceOrigin: SourceOrigin | null;
    /** Suppresses interaction while a translation is in flight. */
    disabled?: boolean;
    /**
     * Reports which side the reader moved, and carries the pair the bar is
     * SHOWING so the caller can rebuild a complete write.
     *
     * `changed` is not decoration — it is the whole point of the field. A side
     * left automatic is `null`, and `null` is also what a side reads as when the
     * reader never touched it, so the payload alone cannot say which side moved.
     * The caller needs it because the persisted pair is a PAIR: only the touched
     * side may take its new value from here, and the untouched side has to keep
     * whatever is already stored (see `handleChangeLanguages`).
     *
     * Both sides are always populated here, and they are the RESOLVED pair — the
     * same values the bar renders. This callback is presentational: it says what
     * the reader picked and what was on screen, and the parent decides what to
     * write. It does NOT send `null` for the untouched side, because `null` is a
     * real value downstream ("automatic") and sending it there would clear a
     * preference the reader never asked to clear.
     */
    onChange: (next: {
      source: string | null;
      target: string | null;
      changed: 'source' | 'target';
    }) => void;
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
   *
   * The SOURCE bar is not the Settings pair, though, and its automatic entry is
   * a different promise: choosing it here CLEARS whatever source was chosen for
   * this book and lets the cascade work the language out again. Reusing the
   * Settings wording would have the reader believe they are picking "the
   * default", when they are actually discarding a choice they made.
   */
  const sourceAutoLabel = $derived(t('reader.translation.sourceAuto'));
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
   *   a CHOICE           -> the bare language name. `preference` was picked in
   *   (`preference`,        Settings and `bookPreference` while reading this
   *   `bookPreference`)     book; both are the reader's own word, so labelling
   *                         either "Auto" would contradict their own choice.
   *   inferred           -> "Auto (español)". The app inferred the language and
   *   (`book`, `detected`)  names the inference, so the reader can judge it.
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
    if (origin === null || origin === 'preference' || origin === 'bookPreference') {
      return languageLabel(code, translate);
    }
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
    onChange({
      source: next === AUTO_VALUE ? null : next,
      target,
      changed: 'source',
    });
  }

  function handleTargetChange(next: string): void {
    onChange({
      source,
      target: next === AUTO_VALUE ? null : next,
      changed: 'target',
    });
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

  /**
   * Dark chrome for the PORTALLED panel.
   *
   * The panel is mounted under `document.body`, so none of the consuming tree's
   * scope reaches it and `Dropdown`'s own panel class stands unless overridden
   * here. Each conflict is suffixed `!` for the same reason the trigger's are:
   * Tailwind decides two utilities for one property by stylesheet order, not by
   * class-attribute order, and `Dropdown`'s base panel already paints
   * `bg-(--color-elevated)`, which in the READER's theme is the LIGHT surface.
   * Without the suffix a white slab lands over the dark floating menu.
   *
   * The option rows are repainted too, not just the panel: `Select.Item` carries
   * its own `text-(--color-primary)` and a `data-[highlighted]:bg-(--color-surface-hover)`
   * highlight, so the selected/keyboard-active row would otherwise be
   * light-on-dark and indistinguishable from the rest. Here the panel is the
   * menu's own `--color-highlight-menu-bg`, the label is the inverse text, and
   * the active row lifts with a translucent white wash — which reads as a
   * highlight against a dark panel in BOTH themes, where a themed
   * `--color-surface-hover` would not.
   */
  const contentClass =
    'bg-(--color-highlight-menu-bg)! ring-(--color-highlight-menu-border)! ' +
    'text-(--color-text-inverse)! border border-(--color-highlight-menu-border)! ' +
    'p-1 ' +
    '**:data-[highlighted]:bg-white/15! ' +
    '**:data-[selected]:bg-white/20! ' +
    '**:data-[selected]:font-semibold!' +
    ' **:data-[selected]:text-(--color-text-inverse)!' +
    ' **:hover:bg-white/12!';
</script>

<div class="flex flex-wrap items-center gap-2">
  <Dropdown
    options={sourceOptions}
    value={sourceValue}
    onchange={(detail) => handleSourceChange(detail.value)}
    class={triggerClass}
    {contentClass}
    markAsFloatingMenu
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
    {contentClass}
    markAsFloatingMenu
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
