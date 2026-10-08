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
    currentPercentage?: number;
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
    currentPercentage,
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
  class="flex flex-col border-b border-(--color-surface-strong) bg-(--color-bg-deep) fixed inset-x-0 top-0 z-50 transition-transform duration-300 {headerTransform}"
  class:shadow-md={isFullscreen}
>
  <!-- Top row: Biblioteca + title + tools -->
  <div class="flex h-16 shrink-0 items-center justify-between px-4 sm:px-8">
    <!-- Left: back + biblioteca -->
    <div class="flex items-center gap-2">
      <button
        type="button"
        onclick={onBackToHome}
        class="flex cursor-pointer items-center gap-2 text-(--color-text-auxiliary) hover:text-(--color-text-inverse)"
      >
        <ChevronLeft size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
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

    <!-- Right: tools -->
    <div class="flex items-center gap-3 sm:gap-6 text-(--color-text-auxiliary)">
      <button
        type="button"
        onclick={onToggleToc}
        class="flex items-center justify-center min-w-7 min-h-7 cursor-pointer transition-colors"
        class:text-(--color-accent-blue)={showTocPanel}
        class:text-(--color-text-auxiliary)={!showTocPanel}
        class:hover:text-(--color-text-inverse)={!showTocPanel}
        class:hover:brightness-125={showTocPanel}
        aria-label={showTocPanel ? t('settings.close') : t('reader.tabla_contenidos')}
      >
        <TocIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleSearch}
        class="flex items-center justify-center min-w-7 min-h-7 cursor-pointer transition-colors"
        class:text-(--color-accent-blue)={searchPanelOpen}
        class:text-(--color-text-auxiliary)={!searchPanelOpen}
        class:hover:text-(--color-text-inverse)={!searchPanelOpen}
        class:hover:brightness-125={searchPanelOpen}
        aria-label={searchPanelOpen ? t('settings.close') : t('epub.search')}
      >
        <SearchIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleTextSettings}
        class="flex items-center justify-center min-w-7 min-h-7 cursor-pointer transition-colors"
        class:text-(--color-accent-blue)={showTextSettings}
        class:text-(--color-text-auxiliary)={!showTextSettings}
        class:hover:text-(--color-text-inverse)={!showTextSettings}
        class:hover:brightness-125={showTextSettings}
        aria-label={showTextSettings ? t('settings.close') : t('reader.ajustes_texto')}
      >
        <TextSettingsIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleBookmarks}
        class="flex items-center justify-center min-w-7 min-h-7 cursor-pointer transition-colors"
        class:text-(--color-accent-blue)={showBookmarks}
        class:text-(--color-text-auxiliary)={!showBookmarks}
        class:hover:text-(--color-text-inverse)={!showBookmarks}
        class:hover:brightness-125={showBookmarks}
        aria-label={showBookmarks ? t('settings.close') : t('reader.bookmark')}
      >
        <BookmarksIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      <button
        type="button"
        onclick={onToggleFullscreen}
        class="flex items-center justify-center min-w-7 min-h-7 cursor-pointer transition-colors text-(--color-text-auxiliary) hover:text-(--color-text-inverse)"
        aria-label={isFullscreen ? t('pdf.fullscreenExit') : t('pdf.fullscreenEnter')}
      >
        <FullscreenIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </div>
  </div>

  {#if showReadingControls}
    <!-- Bottom row: status/zoom strip (paging lives in the toolbar or the edge chevrons) -->
    <div
      class="flex h-12 shrink-0 items-center justify-center gap-2 border-t border-(--color-surface-strong)/20 px-2 bg-(--color-bg-deep) sm:gap-3 sm:px-4"
    >
      <PageInput
        variant="header"
        currentPage={currentPage ?? 1}
        totalPages={totalPages ?? 1}
        {t}
        {onGoToPage}
      />
      {#if currentPercentage !== undefined}
        <span class="text-xs text-(--color-text-auxiliary) min-w-10 text-center"
          >{Math.round(currentPercentage)}%</span
        >
      {/if}
      {#if fontSizePercent !== undefined && onFontSizeChange}
        <ZoomDropdown value={fontSizePercent} onSelect={onFontSizeChange} />
      {/if}
    </div>
  {/if}
</header>
