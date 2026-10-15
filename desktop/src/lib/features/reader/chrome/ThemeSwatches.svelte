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

<div class="flex items-center justify-between px-1">
  {#each THEME_PRESETS as theme}
    <button
      type="button"
      class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full transition-transform hover:scale-110"
      class:ring-2={readerSettings.themeMode === theme.name}
      class:ring-(--color-accent-blue)={readerSettings.themeMode === theme.name}
      style="background-color: {theme.bg};"
      onclick={() => onSettingsChange({ ...readerSettings, themeMode: theme.name })}
      aria-label={t(labelMap[theme.name])}
    >
      <span
        class="text-micro font-normal"
        class:text-white={darkSwatch(theme.name)}
        class:text-black={!darkSwatch(theme.name)}
      >
        Aa
      </span>
    </button>
  {/each}
</div>
