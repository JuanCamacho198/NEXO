<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ReaderSettings, ReaderThemeMode } from '$lib/shared/types';
  import { THEME_PRESETS } from './readerTextPresets';

  type Props = {
    readerSettings: ReaderSettings;
    onSettingsChange: (settings: ReaderSettings) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { readerSettings, onSettingsChange, t }: Props = $props();

  const labelMap: Record<ReaderThemeMode, MessageKey> = {
    paper: 'reader.themePaper',
    sepia: 'reader.themeSepia',
    night: 'reader.themeNight',
    oled: 'reader.themeOled',
  };

  /** Light lettering on the dark surfaces, dark lettering on the light ones. */
  const darkSwatch = (theme: ReaderThemeMode): boolean => theme === 'night' || theme === 'oled';
</script>

<div class="grid grid-cols-4 gap-2">
  {#each THEME_PRESETS as theme}
    {@const isActive = readerSettings.themeMode === theme.name}
    <button
      type="button"
      class="group flex cursor-pointer flex-col items-center gap-1.5 rounded-(--radius-md) py-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
      onclick={() => onSettingsChange({ ...readerSettings, themeMode: theme.name })}
      aria-label={t(labelMap[theme.name])}
      aria-pressed={isActive}
    >
      <span
        class="flex size-11 items-center justify-center rounded-full border transition-transform group-hover:scale-105 {isActive
          ? 'border-transparent ring-2 ring-(--color-brand) ring-offset-2 ring-offset-(--color-reader-chrome)'
          : 'border-(--color-edge)'}"
        style="background-color: {theme.bg};"
      >
        <span
          class="font-serif text-sm font-medium"
          class:text-white={darkSwatch(theme.name)}
          class:text-black={!darkSwatch(theme.name)}
        >
          Aa
        </span>
      </span>
      <span
        class="text-xs {isActive
          ? 'font-semibold text-(--color-brand-text)'
          : 'text-(--color-text-muted)'}"
        aria-hidden="true"
      >
        {t(labelMap[theme.name])}
      </span>
    </button>
  {/each}
</div>
