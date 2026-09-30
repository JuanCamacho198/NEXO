<script lang="ts">
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ReaderThemeMode } from '$lib/shared/types';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    preferredTheme: string;
    preferredFontScale: number;
    readerThemeMode: ReaderThemeMode;
    readerBrightness: number;
    readerContrast: number;
    readerEpubFontSize: number;
    readerEpubFontFamily: string;
    isSavingSettings: boolean;
    onSaveSettings: () => void;
    onOpenResetModal: () => void;
    onPreferredThemeChange: (theme: string) => void;
    onPreferredFontScaleChange: (scale: number) => void;
    onReaderThemeModeChange: (mode: ReaderThemeMode) => void;
    onReaderBrightnessChange: (value: number) => void;
    onReaderContrastChange: (value: number) => void;
    onReaderEpubFontSizeChange: (value: number) => void;
    onReaderEpubFontFamilyChange: (value: string) => void;
  };

  let {
    t,
    preferredTheme,
    preferredFontScale,
    readerThemeMode,
    readerBrightness,
    readerContrast,
    readerEpubFontSize,
    readerEpubFontFamily,
    isSavingSettings,
    onSaveSettings,
    onOpenResetModal,
    onPreferredThemeChange,
    onPreferredFontScaleChange,
    onReaderThemeModeChange,
    onReaderBrightnessChange,
    onReaderContrastChange,
    onReaderEpubFontSizeChange,
    onReaderEpubFontFamilyChange,
  }: Props = $props();

  const fontFamilyOptions = [
    { value: 'serif', label: 'Serif' },
    { value: 'sans-serif', label: 'Sans Serif' },
    { value: 'monospace', label: 'Monospace' },
  ];

  const previewStyles = $derived(() => {
    const styles: Record<string, string> = {
      light: '--preview-bg: #ffffff; --preview-text: #1a1a1a; --preview-border: #e0e0e0;',
      dark: '--preview-bg: #1a1a1a; --preview-text: #e8e8e8; --preview-border: #333333;',
      sepia: '--preview-bg: #f4ecd8; --preview-text: #5b4636; --preview-border: #d4c4a8;',
    };
    return styles[preferredTheme] || styles.light;
  });
</script>

<Panel
  title={t('settings.appearance.appTheme')}
  subtitle={t('settings.appearance.appThemeDescription')}
>
  <div class="rounded-lg border p-3 mb-4 transition-all duration-300" style={previewStyles()}>
    <div
      class="flex items-center gap-2 pb-2 border-b mb-2"
      style="border-color: var(--preview-border)"
    >
      <span class="opacity-70" style="color: var(--preview-text)">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          ><line x1="3" y1="12" x2="21" y2="12" /><line x1="3" y1="6" x2="21" y2="6" /><line
            x1="3"
            y1="18"
            x2="21"
            y2="18"
          /></svg
        >
      </span>
      <span class="text-xs font-semibold" style="color: var(--preview-text)">Nexo</span>
    </div>
    <div style="color: var(--preview-text)">
      <p class="text-xs m-1">Sample text preview</p>
      <p class="text-micro opacity-70">Secondary text</p>
    </div>
  </div>

  <div class="mb-4">
    <div class="grid grid-cols-4 gap-2">
      <button
        type="button"
        class="flex flex-col items-center gap-2 py-3 px-2 rounded-xl border-2 transition-all duration-200 cursor-pointer bg-(--color-surface)"
        class:border-(--color-primary)={preferredTheme === 'light'}
        class:border-(--color-border)={preferredTheme !== 'light'}
        class:theme-icon-btn={true}
        style="--icon-bg: linear-gradient(to bottom right, white, #f3f4f6);"
        onclick={() => onPreferredThemeChange('light')}
        title={t('settings.theme.light')}
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          aria-hidden="true"
          class="theme-icon"
          ><circle cx="12" cy="12" r="5" /><line x1="12" y1="1" x2="12" y2="3" /><line
            x1="12"
            y1="21"
            x2="12"
            y2="23"
          /><line x1="4.22" y1="4.22" x2="5.64" y2="5.64" /><line
            x1="18.36"
            y1="18.36"
            x2="19.78"
            y2="19.78"
          /><line x1="1" y1="12" x2="3" y2="12" /><line x1="21" y1="12" x2="23" y2="12" /><line
            x1="4.22"
            y1="19.78"
            x2="5.64"
            y2="18.36"
          /><line x1="18.36" y1="5.64" x2="19.78" y2="4.22" /></svg
        >
        <span class="text-2xs font-medium text-(--color-text-muted)"
          >{t('settings.theme.light')}</span
        >
      </button>
      <button
        type="button"
        class="flex flex-col items-center gap-2 py-3 px-2 rounded-xl border-2 transition-all duration-200 cursor-pointer bg-(--color-surface)"
        class:border-(--color-primary)={preferredTheme === 'dark'}
        class:border-(--color-border)={preferredTheme !== 'dark'}
        class:theme-icon-btn={true}
        style="--icon-bg: linear-gradient(to bottom right, #3f3f46, #18181b);"
        onclick={() => onPreferredThemeChange('dark')}
        title={t('settings.theme.dark')}
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          aria-hidden="true"
          class="theme-icon"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" /></svg
        >
        <span class="text-2xs font-medium text-(--color-text-muted)"
          >{t('settings.theme.dark')}</span
        >
      </button>
      <button
        type="button"
        class="flex flex-col items-center gap-2 py-3 px-2 rounded-xl border-2 transition-all duration-200 cursor-pointer bg-(--color-surface)"
        class:border-(--color-primary)={preferredTheme === 'sepia'}
        class:border-(--color-border)={preferredTheme !== 'sepia'}
        class:theme-icon-btn={true}
        style="--icon-bg: linear-gradient(to bottom right, #fef3c7, #fde68a);"
        onclick={() => onPreferredThemeChange('sepia')}
        title={t('settings.theme.sepia')}
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          aria-hidden="true"
          class="theme-icon"
          ><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path
            d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"
          /></svg
        >
        <span class="text-2xs font-medium text-(--color-text-muted)"
          >{t('settings.theme.sepia')}</span
        >
      </button>
      <button
        type="button"
        class="flex flex-col items-center gap-2 py-3 px-2 rounded-xl border-2 transition-all duration-200 cursor-pointer bg-(--color-surface)"
        class:border-(--color-primary)={preferredTheme === 'system'}
        class:border-(--color-border)={preferredTheme !== 'system'}
        class:theme-icon-btn={true}
        style="--icon-bg: linear-gradient(to bottom right, #e0e7ff, #c7d2fe);"
        onclick={() => onPreferredThemeChange('system')}
        title={t('settings.theme.system')}
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          aria-hidden="true"
          class="theme-icon"
          ><rect x="2" y="3" width="20" height="14" rx="2" ry="2" /><line
            x1="8"
            y1="21"
            x2="16"
            y2="21"
          /><line x1="12" y1="17" x2="12" y2="21" /></svg
        >
        <span class="text-2xs font-medium text-(--color-text-muted)"
          >{t('settings.theme.system')}</span
        >
      </button>
    </div>
  </div>

  <div class="mb-4">
    <label class="mb-1 block text-xs text-(--color-text-muted)" for="app-font-scale"
      >{t('settings.fontScale')}: {preferredFontScale}%</label
    >
    <input
      type="range"
      id="app-font-scale"
      min="80"
      max="140"
      value={preferredFontScale}
      oninput={(e) => onPreferredFontScaleChange(Number((e.target as HTMLInputElement).value))}
      class="w-full h-1.5 appearance-none bg-(--color-border) rounded-full outline-none slider-thumb"
    />
    <div
      class="flex items-center justify-center h-12 bg-(--color-background) border border-(--color-border) rounded-lg text-(--color-primary) font-medium"
      style="font-size: {preferredFontScale * 0.14}px"
    >
      Aa
    </div>
  </div>
</Panel>

<Panel
  title={t('settings.appearance.reader')}
  subtitle={t('settings.appearance.readerDescription')}
>
  <div class="flex gap-2 justify-stretch mb-4">
    <button
      type="button"
      class="flex-1 py-3 px-2 rounded-lg border-2 transition-all duration-200 flex items-center justify-center text-2xs font-medium cursor-pointer"
      class:border-(--color-primary)={readerThemeMode === 'paper'}
      class:border-[--preview-border,#e0e0e0]={readerThemeMode !== 'paper'}
      style="--preview-bg: #fafafa; --preview-text: #1a1a1a; --preview-border: #e0e0e0; background: var(--preview-bg); color: var(--preview-text);"
      onclick={() => onReaderThemeModeChange('paper')}
    >
      {t('settings.reader.themeMode.paper')}
    </button>
    <button
      type="button"
      class="flex-1 py-3 px-2 rounded-lg border-2 transition-all duration-200 flex items-center justify-center text-2xs font-medium cursor-pointer"
      class:border-(--color-primary)={readerThemeMode === 'sepia'}
      class:border-[--preview-border,#d4c4a8]={readerThemeMode !== 'sepia'}
      style="--preview-bg: #f4ecd8; --preview-text: #5b4636; --preview-border: #d4c4a8; background: var(--preview-bg); color: var(--preview-text);"
      onclick={() => onReaderThemeModeChange('sepia')}
    >
      {t('settings.reader.themeMode.sepia')}
    </button>
    <button
      type="button"
      class="flex-1 py-3 px-2 rounded-lg border-2 transition-all duration-200 flex items-center justify-center text-2xs font-medium cursor-pointer"
      class:border-(--color-primary)={readerThemeMode === 'night'}
      class:border-[--preview-border,#333333]={readerThemeMode !== 'night'}
      style="--preview-bg: #1a1a1a; --preview-text: #e8e8e8; --preview-border: #333333; background: var(--preview-bg); color: var(--preview-text);"
      onclick={() => onReaderThemeModeChange('night')}
    >
      {t('settings.reader.themeMode.night')}
    </button>
  </div>

  <div class="space-y-4">
    <div class="mb-2">
      <label class="mb-1 block text-xs text-(--color-text-muted)" for="reader-brightness"
        >{t('settings.reader.brightness')}: {readerBrightness}%</label
      >
      <input
        type="range"
        id="reader-brightness"
        min="50"
        max="150"
        value={readerBrightness}
        oninput={(e) => onReaderBrightnessChange(Number((e.target as HTMLInputElement).value))}
        class="w-full h-1.5 appearance-none bg-(--color-border) rounded-full outline-none slider-thumb"
      />
    </div>
    <div class="mb-2">
      <label class="mb-1 block text-xs text-(--color-text-muted)" for="reader-contrast"
        >{t('settings.reader.contrast')}: {readerContrast}%</label
      >
      <input
        type="range"
        id="reader-contrast"
        min="50"
        max="150"
        value={readerContrast}
        oninput={(e) => onReaderContrastChange(Number((e.target as HTMLInputElement).value))}
        class="w-full h-1.5 appearance-none bg-(--color-border) rounded-full outline-none slider-thumb"
      />
    </div>
    <div class="mb-2">
      <label class="mb-1 block text-xs text-(--color-text-muted)" for="reader-font-size"
        >{t('settings.reader.epub.fontSize')}: {readerEpubFontSize}%</label
      >
      <input
        type="range"
        id="reader-font-size"
        min="80"
        max="200"
        value={readerEpubFontSize}
        oninput={(e) => onReaderEpubFontSizeChange(Number((e.target as HTMLInputElement).value))}
        class="w-full h-1.5 appearance-none bg-(--color-border) rounded-full outline-none slider-thumb"
      />
    </div>
    <div class="mb-2">
      <label class="mb-1 block text-xs text-(--color-text-muted)" for="reader-font-family"
        >{t('settings.reader.epub.fontFamily')}</label
      >
      <Dropdown
        options={fontFamilyOptions}
        value={readerEpubFontFamily}
        class="w-full"
        onchange={({ value }) => onReaderEpubFontFamilyChange(value)}
      />
    </div>
    <div class="flex gap-2 mt-4">
      <Button onclick={onSaveSettings} disabled={isSavingSettings} size="sm">
        {isSavingSettings ? t('settings.saving') : t('settings.savePreferences')}
      </Button>
      <Button onclick={onOpenResetModal} variant="danger" size="sm">
        {t('settings.resetDefaults')}
      </Button>
    </div>
  </div>
</Panel>

<style>
  .theme-icon-btn {
    position: relative;
  }
  .theme-icon-btn::before {
    content: '';
    position: absolute;
    top: 0.75rem;
    left: 50%;
    transform: translateX(-50%);
    width: 2.5rem;
    height: 2.5rem;
    border-radius: 10px;
    background: var(--icon-bg);
    z-index: 0;
  }
  .theme-icon {
    position: relative;
    z-index: 1;
  }
</style>
