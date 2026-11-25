<script lang="ts">
  import ALargeSmall from 'lucide-svelte/icons/a-large-small';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import Maximize from 'lucide-svelte/icons/maximize';
  import Minimize from 'lucide-svelte/icons/minimize';
  import Search from 'lucide-svelte/icons/search';
  import TableOfContents from 'lucide-svelte/icons/table-of-contents';
  import { CHROME_DIVIDER, CHROME_ICON_TOGGLE_IDLE, chromeIconButton } from './chromeControl';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    title: string;
    /** The chapter being read: the TOC button says where you are. */
    chapterTitle?: string | null;
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
    chapterTitle = null,
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

  // A panel's button keeps its own glyph while the panel is open: the ember
  // wash says "open", and three identical X's no longer said which was which.
  // The text-settings glyph is "Aa", what reading apps use for type, not a
  // gear that reads as the app's settings.
  const tocLabel = $derived(
    chapterTitle
      ? `${t('reader.tabla_contenidos')} · ${chapterTitle}`
      : t('reader.tabla_contenidos'),
  );
  const FullscreenIcon = $derived(isFullscreen ? Minimize : Maximize);
  const fullscreenLabel = $derived(
    isFullscreen ? t('pdf.fullscreenExit') : t('pdf.fullscreenEnter'),
  );
</script>

<!-- The single top bar. Bookmarks left this row for the bottom bar; row 2 is
  gone entirely, so this is the only header in both modes. -->
<header
  class="grid shrink-0 grid-cols-[1fr_auto_1fr] items-center border-b border-(--color-edge) bg-(--color-reader-chrome) px-4 transition-transform duration-300 sm:px-8 {isFullscreen
    ? 'fixed inset-x-0 top-0 z-50 h-16'
    : 'h-16'} {barTransform}"
  class:shadow-md={isFullscreen}
>
  <!-- Left: back + biblioteca -->
  <div class="flex items-center gap-2 justify-self-start">
    <button
      type="button"
      onclick={onBackToHome}
      class="{CHROME_ICON_TOGGLE_IDLE} gap-1.5 pr-4 pl-3"
    >
      <ChevronLeft size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      <span class="font-inter text-sm font-medium">{t('reader.biblioteca')}</span>
    </button>
  </div>

  <!-- Center: book title. It is centred by the grid's two equal 1fr rails, not
    by `flex-1 text-center`: that centred it within the leftover space, which
    moves with the width of the two side groups. -->
  <span
    class="font-inter text-sm font-semibold text-(--color-primary) min-w-0 truncate px-2 text-center max-[480px]:hidden"
  >
    {title}
  </span>

  <!-- Right: panels, then a quiet divider, then window mode.
    Grouping is the signal: the three panel toggles act on the reader content,
    the last one changes the window. The search toggle stays here by design. -->
  <div class="flex items-center gap-2 justify-self-end sm:gap-4">
    <div class="flex items-center gap-1">
      <button
        type="button"
        onclick={onToggleToc}
        class={chromeIconButton('toggle', showTocPanel)}
        aria-label={tocLabel}
        title={tocLabel}
        aria-pressed={showTocPanel}
      >
        <TableOfContents size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleSearch}
        class={chromeIconButton('toggle', searchPanelOpen)}
        aria-label={t('search.title')}
        title={t('search.title')}
        aria-pressed={searchPanelOpen}
        aria-keyshortcuts="Control+F"
      >
        <Search size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleTextSettings}
        class={chromeIconButton('toggle', showTextSettings)}
        aria-label={t('reader.ajustes_texto')}
        title={t('reader.ajustes_texto')}
        aria-pressed={showTextSettings}
      >
        <ALargeSmall size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
    <div class={CHROME_DIVIDER} aria-hidden="true"></div>
    <button
      type="button"
      onclick={onToggleFullscreen}
      class={chromeIconButton('toggle', isFullscreen)}
      aria-label={fullscreenLabel}
      title={fullscreenLabel}
      aria-pressed={isFullscreen}
      aria-keyshortcuts={isFullscreen ? 'Escape' : 'F'}
    >
      <FullscreenIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
    </button>
  </div>
</header>
