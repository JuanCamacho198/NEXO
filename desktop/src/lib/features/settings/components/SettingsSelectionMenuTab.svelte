<script lang="ts">
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
    onSave: () => void;
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
</script>

<div
  role="tabpanel"
  id="tabpanel-seleccion"
  aria-labelledby="tab-seleccion"
  class="relative flex-1 overflow-y-auto p-4 flex flex-col gap-4"
>
  <section class="space-y-5 w-full max-w-none">
    <header class="flex flex-col gap-1">
      <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
        {t('settings.selectionMenu.title')}
      </h1>
      <p class="text-sm text-(--color-text-muted)">{t('settings.selectionMenu.subtitle')}</p>
    </header>

    {#if error}
      <p class="rounded-lg border border-(--color-danger) px-3 py-2 text-sm text-(--color-danger)">
        {error}
      </p>
    {/if}

    <!-- ── Option 1: Translation ──────────────────────────────────────────────
         The reused block renders its own card; only its heading is scoped to
         this tab, so the option is named in the menu's vocabulary. -->
    <div class="flex flex-col gap-2">
      <h2 class="text-xl font-semibold text-(--color-primary)">
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
      {onSave}
      {onClearHistory}
    />

    <!-- ── Option 2: Definition ───────────────────────────────────────────────
         NEXT OPTION GOES HERE: copy this card's shape, add a heading pair with
         `settings.selectionMenu.<option>.title` / `.subtitle` and the control
         itself. No other file needs to change. -->
    <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-4">
      <header class="flex flex-col gap-1">
        <h2 class="text-xl font-semibold text-(--color-primary)">
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
          class="w-full"
        />
        <p class="text-xs text-(--color-text-muted)">
          {t('settings.selectionMenu.definition.languageHint')}
        </p>
      </div>
    </div>
  </section>
</div>
