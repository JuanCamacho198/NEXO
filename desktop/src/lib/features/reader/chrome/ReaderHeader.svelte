<script lang="ts">
  import Bookmark from 'lucide-svelte/icons/bookmark';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import Expand from 'lucide-svelte/icons/expand';
  import Menu from 'lucide-svelte/icons/menu';
  import Search from 'lucide-svelte/icons/search';
  import Settings from 'lucide-svelte/icons/settings';
  import Shrink from 'lucide-svelte/icons/shrink';
  import X from 'lucide-svelte/icons/x';
  import ZoomDropdown from './ZoomDropdown.svelte';
  import PageInput from './PageInput.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    title: string;
    showTocPanel: boolean;
    searchPanelOpen: boolean;
    showTextSettings: boolean;
    showBookmarks: boolean;
    isFullscreen: boolean;
    format: 'pdf' | 'epub';
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onBackToHome: () => void;
    onToggleToc: () => void;
    onToggleSearch: () => void;
    onToggleTextSettings: () => void;
    onToggleBookmarks: () => void;
    onToggleFullscreen: () => void;
    // Reading controls (for immersive unified header)
    currentPage?: number;
    totalPages?: number;
    fontSizePercent?: number;
    onGoToPage?: (page: number) => Promise<boolean>;
    onFontSizeChange?: (size: number) => void;
  };

  type HeaderVisibleProps = {
    headerVisible?: boolean;
  };

  let {
    title,
    showTocPanel,
    searchPanelOpen,
    showTextSettings,
    showBookmarks,
    isFullscreen,
    format,
    t,
    onBackToHome,
    onToggleToc,
    onToggleSearch,
    onToggleTextSettings,
    onToggleBookmarks,
    onToggleFullscreen,
    headerVisible = true,
    currentPage,
    totalPages,
    fontSizePercent,
    onGoToPage,
    onFontSizeChange,
  }: Props & HeaderVisibleProps = $props();

  const headerTransform = $derived(headerVisible ? 'translate-y-0' : '-translate-y-full');
  const showReadingControls = $derived(
    isFullscreen && currentPage !== undefined && totalPages !== undefined && totalPages > 0,
  );

  const TocIcon = $derived(showTocPanel ? X : Menu);
  const SearchIcon = $derived(searchPanelOpen ? X : Search);
  const TextSettingsIcon = $derived(showTextSettings ? X : Settings);
  const BookmarksIcon = $derived(showBookmarks ? X : Bookmark);
  const FullscreenIcon = $derived(isFullscreen ? Shrink : Expand);
</script>

<header
  class="flex flex-col shrink-0 border-b border-(--color-surface-strong) bg-(--color-bg-deep) transition-transform duration-300 {isFullscreen
    ? 'fixed inset-x-0 top-0 z-50'
    : ''} {headerTransform}"
  class:shadow-md={isFullscreen}
>
  <!-- Top row: Biblioteca + title + tools -->
  <div class="flex h-16 shrink-0 items-center justify-between px-4 sm:px-8">
    <!-- Left: back + biblioteca -->
    <div class="flex items-center gap-2">
      <button
        type="button"
        onclick={onBackToHome}
        class="flex min-h-11 cursor-pointer items-center gap-2 text-(--color-text-auxiliary) hover:text-(--color-text-inverse)"
      >
        <ChevronLeft size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
        <span class="font-inter text-sm font-medium text-(--color-text-auxiliary)"
          >{t('reader.biblioteca')}</span
        >
      </button>
    </div>

    <!-- Center: book title -->
    <span
      class="font-inter text-sm font-medium text-(--color-text-auxiliary) min-w-0 flex-1 truncate px-2 text-center max-[480px]:hidden"
    >
      {title}
    </span>

    <!-- Right: panels, then a quiet divider, then window mode.
      Grouping is the signal: the four panel toggles act on the reader content,
      the last one changes the window. Same shape for all five used to make them
      read as one undifferentiated row of icons. -->
    <div class="flex items-center gap-2 sm:gap-4">
      <div class="flex items-center gap-1">
        <button
          type="button"
          onclick={onToggleToc}
          class="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-lg transition-colors hover:bg-(--color-surface-hover)"
          class:text-(--color-accent-blue)={showTocPanel}
          class:bg-(--color-surface-hover)={showTocPanel}
          class:text-(--color-text-inverse)={!showTocPanel}
          aria-label={showTocPanel ? t('settings.close') : t('reader.tabla_contenidos')}
          title={showTocPanel ? t('settings.close') : t('reader.tabla_contenidos')}
        >
          <TocIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
        </button>
        <button
          type="button"
          onclick={onToggleSearch}
          class="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-lg transition-colors hover:bg-(--color-surface-hover)"
          class:text-(--color-accent-blue)={searchPanelOpen}
          class:bg-(--color-surface-hover)={searchPanelOpen}
          class:text-(--color-text-inverse)={!searchPanelOpen}
          aria-label={searchPanelOpen ? t('settings.close') : t('search.title')}
          title={searchPanelOpen ? t('settings.close') : t('search.title')}
        >
          <SearchIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
        </button>
        <button
          type="button"
          onclick={onToggleTextSettings}
          class="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-lg transition-colors hover:bg-(--color-surface-hover)"
          class:text-(--color-accent-blue)={showTextSettings}
          class:bg-(--color-surface-hover)={showTextSettings}
          class:text-(--color-text-inverse)={!showTextSettings}
          aria-label={showTextSettings ? t('settings.close') : t('reader.ajustes_texto')}
          title={showTextSettings ? t('settings.close') : t('reader.ajustes_texto')}
        >
          <TextSettingsIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
        </button>
        <button
          type="button"
          onclick={onToggleBookmarks}
          class="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-lg transition-colors hover:bg-(--color-surface-hover)"
          class:text-(--color-accent-blue)={showBookmarks}
          class:bg-(--color-surface-hover)={showBookmarks}
          class:text-(--color-text-inverse)={!showBookmarks}
          aria-label={showBookmarks ? t('settings.close') : t('reader.bookmark')}
          title={showBookmarks ? t('settings.close') : t('reader.bookmark')}
        >
          <BookmarksIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
        </button>
      </div>
      <div class="h-5 w-px bg-(--color-surface-strong)" aria-hidden="true"></div>
      <button
        type="button"
        onclick={onToggleFullscreen}
        class="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-lg text-(--color-text-inverse) transition-colors hover:bg-(--color-surface-hover)"
        aria-label={isFullscreen ? t('pdf.fullscreenExit') : t('pdf.fullscreenEnter')}
        title={isFullscreen ? t('pdf.fullscreenExit') : t('pdf.fullscreenEnter')}
      >
        <FullscreenIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  </div>

  {#if showReadingControls}
    <!-- Bottom row: where you are, plus the one control this format needs.
      The percentage is gone: it restates the page position and an on-screen
      progress bar already carries it. The zoom dropdown is PDF-only, where it
      is the scale of the page - in EPUB the same value is the font size, which
      the text settings panel owns with proper context. -->
    <div
      class="flex h-12 shrink-0 items-center justify-center gap-3 border-t border-(--color-surface-strong)/20 px-2 bg-(--color-bg-deep) sm:px-4"
    >
      <PageInput
        variant="header"
        currentPage={currentPage ?? 1}
        totalPages={totalPages ?? 1}
        {t}
        {onGoToPage}
      />
      {#if format === 'pdf' && fontSizePercent !== undefined && onFontSizeChange}
        <ZoomDropdown value={fontSizePercent} onSelect={onFontSizeChange} />
      {/if}
    </div>
  {/if}
</header>
