<script lang="ts">
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import Expand from 'lucide-svelte/icons/expand';
  import Menu from 'lucide-svelte/icons/menu';
  import Search from 'lucide-svelte/icons/search';
  import Settings from 'lucide-svelte/icons/settings';
  import Shrink from 'lucide-svelte/icons/shrink';
  import X from 'lucide-svelte/icons/x';
  import { CHROME_DIVIDER, CHROME_ICON_ACTION, chromeIconButton } from './chromeControl';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    title: string;
    showTocPanel: boolean;
    searchPanelOpen: boolean;
    showTextSettings: boolean;
    isFullscreen: boolean;
    visible: boolean;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onBackToHome: () => void;
    onToggleToc: () => void;
    onToggleSearch: () => void;
    onToggleTextSettings: () => void;
    onToggleFullscreen: () => void;
  };

  let {
    title,
    showTocPanel,
    searchPanelOpen,
    showTextSettings,
    isFullscreen,
    visible,
    t,
    onBackToHome,
    onToggleToc,
    onToggleSearch,
    onToggleTextSettings,
    onToggleFullscreen,
  }: Props = $props();

  const barTransform = $derived(visible ? 'translate-y-0' : '-translate-y-full');

  const TocIcon = $derived(showTocPanel ? X : Menu);
  const SearchIcon = $derived(searchPanelOpen ? X : Search);
  const TextSettingsIcon = $derived(showTextSettings ? X : Settings);
  const FullscreenIcon = $derived(isFullscreen ? Shrink : Expand);
</script>

<!-- The single top bar. Bookmarks left this row for the bottom bar; row 2 is
  gone entirely, so this is the only header in both modes. -->
<header
  class="flex shrink-0 items-center justify-between border-b border-(--color-border) bg-(--color-reader-chrome) px-4 transition-transform duration-300 sm:px-8 {isFullscreen
    ? 'fixed inset-x-0 top-0 z-50 h-16'
    : 'h-16'} {barTransform}"
  class:shadow-md={isFullscreen}
>
  <!-- Left: back + biblioteca -->
  <div class="flex items-center gap-2">
    <button type="button" onclick={onBackToHome} class={CHROME_ICON_ACTION}>
      <ChevronLeft size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      <span class="font-inter text-sm text-(--color-text-muted)">{t('reader.biblioteca')}</span>
    </button>
  </div>

  <!-- Center: book title -->
  <span
    class="font-inter text-sm text-(--color-text-muted) min-w-0 flex-1 truncate px-2 text-center max-[480px]:hidden"
  >
    {title}
  </span>

  <!-- Right: panels, then a quiet divider, then window mode.
    Grouping is the signal: the three panel toggles act on the reader content,
    the last one changes the window. The search toggle stays here by design. -->
  <div class="flex items-center gap-2 sm:gap-4">
    <div class="flex items-center gap-1">
      <button
        type="button"
        onclick={onToggleToc}
        class={chromeIconButton('toggle', showTocPanel)}
        aria-label={showTocPanel ? t('settings.close') : t('reader.tabla_contenidos')}
        title={showTocPanel ? t('settings.close') : t('reader.tabla_contenidos')}
      >
        <TocIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleSearch}
        class={chromeIconButton('toggle', searchPanelOpen)}
        aria-label={searchPanelOpen ? t('settings.close') : t('search.title')}
        title={searchPanelOpen ? t('settings.close') : t('search.title')}
      >
        <SearchIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleTextSettings}
        class={chromeIconButton('toggle', showTextSettings)}
        aria-label={showTextSettings ? t('settings.close') : t('reader.ajustes_texto')}
        title={showTextSettings ? t('settings.close') : t('reader.ajustes_texto')}
      >
        <TextSettingsIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
    <div class={CHROME_DIVIDER} aria-hidden="true"></div>
    <button
      type="button"
      onclick={onToggleFullscreen}
      class={CHROME_ICON_ACTION}
      aria-label={isFullscreen ? t('pdf.fullscreenExit') : t('pdf.fullscreenEnter')}
      title={isFullscreen ? t('pdf.fullscreenExit') : t('pdf.fullscreenEnter')}
    >
      <FullscreenIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
    </button>
  </div>
</header>
