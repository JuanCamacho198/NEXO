<script lang="ts">
  import { sectionClass } from '$lib/shared/ui/emberStyles';
  import { headingMarker } from '$lib/shared/ui/emberStyles';
  /**
   * "Menú de selección" — the single settings surface for the selection menu
   * (D6).
   *
   * The menu the reader sees after selecting text (or opening a saved highlight)
   * offers several options, and this tab is where each one is configured. Today
   * it holds two:
   *
   *   1. Translation — its own save button for the pair, plus the history.
   *   2. Definition  — the language definitions are written in.
   *
   * The structure is deliberately flat: one `<section>` per option, each with a
   * title and a subtitle, so the next option (highlighting, dictionary, copy,
   * note…) is a new `<section>` here and a new `settings.selectionMenu.<option>.*`
   * key group — nothing else in the panel has to change or be re-registered.
   *
   * The translation block is `SettingsTranslationTab`, reused rather than
   * copied: it already owns its markup, the "infer" mapping and the history
   * list, and a second copy would be a second place for that logic to drift.
   */
  import type { MessageKey } from '$lib/shared/i18n';
  import type { UiLocale, TranslationCacheEntryDto } from '$lib/shared/types';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import SettingsTranslationTab from './SettingsTranslationTab.svelte';
  import {
    TRANSLATION_LANGUAGE_OPTIONS,
    languageLabel,
  } from '$lib/shared/services/translation/translationLanguageOptions';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    /** The app locale, only used to label the "app language" definition option. */
    locale?: UiLocale;
    sourceLanguage: string | null;
    targetLanguage: string | null;
    definitionLanguage: string | null;
    history: TranslationCacheEntryDto[];
    isLoading?: boolean;
    isSaving?: boolean;
    isClearingHistory?: boolean;
    error?: string | null;
    onSourceChange: (value: string | null) => void;
    onTargetChange: (value: string | null) => void;
    /** Saves immediately: this selector is not behind the pair's Save button. */
    onDefinitionLanguageChange: (value: string | null) => void;
    /**
     * The translation block hands the pair it is SHOWING, edited but not yet
     * saved: the stored pair is what Save is meant to replace, so a save that
     * read it back would persist the value the reader just left behind.
     */
    onSave: (sourceLanguage: string | null, targetLanguage: string | null) => void;
    onClearHistory: () => void;
  };

  let {
    t,
    locale = 'es',
    sourceLanguage,
    targetLanguage,
    definitionLanguage,
    history,
    isLoading = false,
    isSaving = false,
    isClearingHistory = false,
    error = null,
    onSourceChange,
    onTargetChange,
    onDefinitionLanguageChange,
    onSave,
    onClearHistory,
  }: Props = $props();

  // Same contract as the translation pair: the empty string is the placeholder
  // slot of the underlying Select, and `null` — "use the app language" — is the
  // value that actually reaches the command.
  const APP_LANGUAGE_VALUE = '';

  const definitionOptions = $derived([
    {
      value: APP_LANGUAGE_VALUE,
      label: `${t('settings.selectionMenu.definition.languageApp')} (${languageLabel(
        locale,
        (key) => t(key as MessageKey),
      )})`,
    },
    ...TRANSLATION_LANGUAGE_OPTIONS.map((code) => ({
      value: code,
      label: languageLabel(code, (key) => t(key as MessageKey)),
    })),
  ]);

  const definitionValue = $derived(definitionLanguage ?? APP_LANGUAGE_VALUE);

  function handleDefinitionChange(value: string): void {
    onDefinitionLanguageChange(value === APP_LANGUAGE_VALUE ? null : value);
  }

  /**
   * The translation block already maps its dropdowns back to `null`; it is
   * restated here because the pair crosses a component boundary on the way to
   * the caller, and that caller is the one holding the stored value.
   */
  function handlePairSave(nextSource: string | null, nextTarget: string | null): void {
    onSave(nextSource ?? null, nextTarget ?? null);
  }
</script>

<div
  role="tabpanel"
  id="tabpanel-seleccion"
  aria-labelledby="tab-seleccion"
  class="relative flex flex-1 flex-col overflow-y-auto px-8 pt-4 pb-12"
>
  <section class="w-full max-w-5xl space-y-6">
    <header class="flex flex-col gap-1">
      <div class="flex items-center gap-3">
        <div class="h-7 {headingMarker}" aria-hidden="true"></div>
        <h1 class="m-0 text-[1.75rem] font-bold tracking-[-0.02em] text-(--color-primary)">
          {t('settings.selectionMenu.title')}
        </h1>
      </div>
      <p class="text-sm text-(--color-text-muted)">{t('settings.selectionMenu.subtitle')}</p>
    </header>

    {#if error}
      <p
        class="rounded-(--radius-md) border border-(--color-danger)/45 bg-(--color-danger)/10 px-4 py-3 text-sm text-(--color-danger)"
      >
        {error}
      </p>
    {/if}

    <!-- ── Option 1: Translation ──────────────────────────────────────────────
         The reused block renders its own card; only its heading is scoped to
         this tab, so the option is named in the menu's vocabulary. -->
    <div class="{sectionClass} flex flex-col gap-1">
      <h2 class="text-base font-semibold text-(--color-primary)">
        {t('settings.selectionMenu.translation.title')}
      </h2>
      <p class="text-sm text-(--color-text-muted)">
        {t('settings.selectionMenu.translation.subtitle')}
      </p>
    </div>
    <SettingsTranslationTab
      {t}
      showHeader={false}
      {sourceLanguage}
      {targetLanguage}
      {history}
      {isLoading}
      {isSaving}
      {isClearingHistory}
      error={null}
      {onSourceChange}
      {onTargetChange}
      onSave={handlePairSave}
      {onClearHistory}
    />

    <!-- ── Option 2: Definition ───────────────────────────────────────────────
         NEXT OPTION GOES HERE: copy this card's shape, add a heading pair with
         `settings.selectionMenu.<option>.title` / `.subtitle` and the control
         itself. No other file needs to change. -->
    <div class="{sectionClass} space-y-4">
      <header class="flex flex-col gap-1">
        <h2 class="text-base font-semibold text-(--color-primary)">
          {t('settings.selectionMenu.definition.title')}
        </h2>
        <p class="text-sm text-(--color-text-muted)">
          {t('settings.selectionMenu.definition.subtitle')}
        </p>
      </header>

      <div class="flex flex-col gap-2">
        <label class="text-sm font-medium text-(--color-primary)" for="definition-language">
          {t('settings.selectionMenu.definition.languageLabel')}
        </label>
        <Dropdown
          options={definitionOptions}
          value={definitionValue}
          onchange={(detail) => handleDefinitionChange(detail.value)}
          class="w-full sm:max-w-sm"
        />
        <p class="text-xs text-(--color-text-muted)">
          {t('settings.selectionMenu.definition.languageHint')}
        </p>
      </div>
    </div>
  </section>
</div>
