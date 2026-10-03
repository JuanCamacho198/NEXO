<script lang="ts">
  import { appState } from '$lib/shared/stores/AppState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
  import { searchState } from '$lib/shared/stores/SearchDomainState.svelte';
  import { bulkImportState } from '$lib/shared/stores/BulkImportDomainState.svelte';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
  import { getNavItems, getDataNavItems } from '$lib/shared/stores/NavigationState.svelte';
  import {
    resolveRouteLayout,
    routeLayoutClass,
    routeUsesPadding,
  } from '$lib/shared/ui/layout/routeLayout';
  import { fly } from 'svelte/transition';

  import { ContinueReadingSection, AppSidebar } from '$lib/shared/ui';
  import ShelfSection from '$lib/features/library/ShelfSection.svelte';
  import GradientImportButton from '$lib/features/home/components/GradientImportButton.svelte';
  import { SettingsPanel } from '$lib/features/settings';
  import HomeDesktopView from '$lib/features/home/components/HomeDesktopView.svelte';
  import LibraryShelfScreen from '$lib/features/library/components/LibraryShelfScreen.svelte';
  import { ReaderWorkspace } from '$lib/features/reader';
  import HighlightsView from '$lib/features/highlights/components/HighlightsView.svelte';
  import { ReadingStatisticsView } from '$lib/features/stats';
  import WelcomeScreen from '$lib/features/welcome/WelcomeScreen.svelte';
  import DictionaryView from '$lib/features/dictionary/components/DictionaryView.svelte';
  import DiscoverScreen from '$lib/features/discover/DiscoverScreen.svelte';
  import AddonsScreen from '$lib/features/addons/AddonsScreen.svelte';

  const showSidebar = $derived(
    navigationState.route !== 'reader' && navigationState.route !== 'welcome',
  );

  const contentLayoutClass = $derived(routeLayoutClass(resolveRouteLayout(navigationState.route)));
  const mainUsesPadding = $derived(routeUsesPadding(navigationState.route));

  const navItems = $derived(
    getNavItems({
      onNavigateHome: () => navigationState.navigateToHome(),
      onNavigateLibrary: () => navigationState.navigateToLibrary(),
      onNavigateDiscover: () => navigationState.navigateToDiscover(),
      onNavigateAddons: () => navigationState.navigateToAddons(),
      onNavigateStats: () => navigationState.navigateToStats(),
      onNavigateHighlights: () => navigationState.navigateToHighlights(),
      onNavigateSettings: () => navigationState.navigateToSettings(),
    }),
  );

  const dataNavItems = $derived(
    getDataNavItems({
      onNavigateHome: () => navigationState.navigateToHome(),
      onNavigateLibrary: () => navigationState.navigateToLibrary(),
      onNavigateStats: () => navigationState.navigateToStats(),
      onNavigateHighlights: () => navigationState.navigateToHighlights(),
      onNavigateSettings: () => navigationState.navigateToSettings(),
      onNavigateDictionary: () => navigationState.navigateToDictionary(),
    }),
  );
</script>

{#if !appState.isInitialized}
  <!--
    Brief loader: shown while the persisted auth cache is read. Prevents
    a flash of the welcome screen for returning users. ~150ms on a Tauri
    cold start; imperceptible.
  -->
  <main
    id="main-content"
    tabindex="-1"
    class="flex-1 overflow-y-auto relative flex items-center justify-center bg-(--color-background)"
  >
    <div
      role="status"
      aria-live="polite"
      class="flex flex-col items-center gap-3 text-(--color-text-muted)"
    >
      <div
        class="size-10 rounded-full border-2 border-(--color-border) border-t-(--color-primary) animate-spin"
        aria-hidden="true"
      ></div>
      <p class="m-0 text-sm">{appState.t('app.start')}...</p>
    </div>
  </main>
{:else if navigationState.route === 'welcome'}
  <!--
    Standalone welcome screen — no sidebar, no main wrapper, no fly
    transition. The screen is itself the entire viewport.
  -->
  <WelcomeScreen t={appState.t} />
{:else if showSidebar}
  <div class="flex h-full flex-1 min-w-0">
    <AppSidebar
      activeRoute={navigationState.route}
      {navItems}
      {dataNavItems}
      t={appState.t}
      onNavigateSettings={() => navigationState.navigateToSettings()}
    />
    <main
      id="main-content"
      tabindex="-1"
      class="flex-1 overflow-y-auto relative flex flex-col min-h-0"
      class:p-4={mainUsesPadding}
      class:md:p-6={mainUsesPadding}
    >
      <!-- aria-live region for screen reader announcements of dynamic content -->
      <div aria-live="polite" aria-atomic="true" class="sr-only">
        {#if bulkImportState.importProgress}
          {bulkImportState.importProgress.message}
        {/if}
        {#if libraryState.readerError}
          {libraryState.readerError}
        {/if}
      </div>
      <div class={contentLayoutClass}>
        {#if libraryState.readerError}
          <p class="mb-3 rounded-lg border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-900">
            {libraryState.readerError}
          </p>
        {/if}

        {#key navigationState.route}
          {#if navigationState.route === 'home'}
            {@const previewBook = libraryState.getBookById(navigationState.previewBookId)}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <HomeDesktopView
                stats={statsState.stats}
                isLoadingStats={statsState.isLoadingStats}
                statsUnavailableReason={navigationState.statsUnavailableReason}
                streakDays={statsState.streakDays}
                isLoadingStreak={statsState.isLoadingStreak}
                selectedBookTitle={previewBook?.title ?? null}
                continueCount={libraryState.continueReadingBooks.length}
                shelfCount={libraryState.myShelfBooks.length}
                statsMinutes={statsState.stats?.totalMinutesRead ?? 0}
                activeRoute="home"
                onNavigateHome={() => navigationState.navigateToHome()}
                onNavigateHighlights={() => navigationState.navigateToHighlights()}
                onNavigateSettings={() => navigationState.navigateToSettings()}
                onRefreshStats={() => {
                  void statsState.loadStats(navigationState.previewBookId ?? undefined);
                  void statsState.loadStreak();
                }}
                t={appState.t}
              >
                {#snippet navbarActions()}
                  <GradientImportButton
                    label={appState.t('home.greetingImport')}
                    onclick={() => bulkImportState.openBulkImportModal()}
                  />
                {/snippet}

                {#snippet continueSection()}
                  <ContinueReadingSection />
                {/snippet}

                {#snippet shelfSection()}
                  <ShelfSection
                    shelfQueryState={libraryState.shelfQueryState}
                    shelfBooks={libraryState.shelfBooks}
                    myShelfBooks={libraryState.myShelfBooks}
                    collections={libraryState.collections}
                    previewBookId={navigationState.previewBookId}
                    selectedShelfBook={libraryState.books.find(
                      (b) => b.id === navigationState.shelfDetailsBookId,
                    ) ?? null}
                    shelfTabOptions={libraryState.SHELF_TAB_OPTIONS}
                    shelfSortOptions={libraryState.SHELF_SORT_OPTIONS}
                    t={appState.t}
                    onSetTab={(key) => libraryState.setShelfTab(key as never)}
                    onSetSort={(key) => libraryState.setShelfSort(key as never)}
                    onSetViewMode={(mode) => libraryState.setShelfViewMode(mode)}
                    onShelfQueryInput={(event) => libraryState.handleShelfQueryInput(event)}
                    onClearShelfQuery={() => libraryState.clearShelfQuery()}
                    onOpenDetails={(book) => navigationState.openShelfDetails(book.id)}
                    onStartReading={(book) => void appState.startReading(book)}
                    onEditBook={(book) => libraryState.handleEditBook(book)}
                    onRemoveBook={(book) => {
                      libraryState.pendingRemoveBook = book;
                    }}
                    onToggleFavorite={(book) => libraryState.handleToggleFavorite(book)}
                    onStatusChange={(book, status) =>
                      libraryState.handleStatusChange(
                        book,
                        status as 'to_read' | 'reading' | 'completed',
                      )}
                    onDeleteCover={(book) => libraryState.handleDeleteCover(book)}
                    onSaveEdit={(dto) => libraryState.handleSaveEditedBook(dto as never)}
                    onCloseDetails={() => navigationState.closeShelfDetails()}
                    onCoverUpdated={(bookId, path) => {
                      const found = libraryState.books.find((b) => b.id === bookId);
                      if (found) found.coverPath = path;
                    }}
                  />
                {/snippet}
              </HomeDesktopView>
            </div>
          {:else if navigationState.route === 'library'}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <LibraryShelfScreen
                books={libraryState.books}
                isImporting={bulkImportState.isImporting}
                t={appState.t}
                onImportBook={() => bulkImportState.handleImportFile()}
                onOpenBook={(book: Parameters<typeof appState.startReading>[0]) => {
                  void appState.startReading(book);
                }}
                onContinueReading={(book: Parameters<typeof appState.startReading>[0]) => {
                  void appState.startReading(book);
                }}
                onToggleFavorite={(
                  book: Parameters<typeof libraryState.handleToggleFavorite>[0],
                ) => {
                  void libraryState.handleToggleFavorite(book);
                }}
                onStatusChange={(
                  book: Parameters<typeof libraryState.handleStatusChange>[0],
                  status: string,
                ) => {
                  void libraryState.handleStatusChange(
                    book,
                    status as 'to_read' | 'reading' | 'completed',
                  );
                }}
                onViewDetails={(book) => navigationState.openShelfDetails(book.id)}
                onRemoveBook={(book) => {
                  libraryState.pendingRemoveBook = book;
                }}
                onDownloaded={() => {
                  void appState.loadLibrary();
                }}
              />
            </div>
          {:else if navigationState.route === 'discover'}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <DiscoverScreen
                t={appState.t}
                onOpenBook={(book) => void appState.startReading(book)}
              />
            </div>
          {:else if navigationState.route === 'addons'}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <AddonsScreen t={appState.t} />
            </div>
          {:else if navigationState.route === 'stats'}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <ReadingStatisticsView {appState} t={appState.t} />
            </div>
          {:else if navigationState.route === 'highlights'}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <HighlightsView books={libraryState.books} t={appState.t} />
            </div>
          {:else if navigationState.route === 'dictionary'}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <DictionaryView t={appState.t} />
            </div>
          {:else if navigationState.route === 'storage' || navigationState.route === 'sync'}
            <div
              transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}
              class="w-full h-full flex-1 flex flex-col min-h-0"
            >
              <section class="w-full h-full flex-1 flex flex-col min-h-0">
                <SettingsPanel
                  isOpen={true}
                  mode="page"
                  initialTab={navigationState.route === 'storage'
                    ? 'almacenamiento'
                    : 'sincronizacion'}
                  onRequestClose={() => navigationState.navigateToHome()}
                  t={appState.t}
                  locale={settingsState.locale}
                  onLocaleChange={settingsState.handleLocaleChange}
                  books={libraryState.books.map((b) => ({ id: b.id, title: b.title }))}
                />
              </section>
            </div>
          {:else if navigationState.route === 'settings'}
            <div
              transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}
              class="w-full h-full flex-1 flex flex-col min-h-0"
            >
              <section class="w-full h-full flex-1 flex flex-col min-h-0">
                <SettingsPanel
                  isOpen={true}
                  mode="page"
                  onRequestClose={() => navigationState.navigateToHome()}
                  t={appState.t}
                  locale={settingsState.locale}
                  onLocaleChange={settingsState.handleLocaleChange}
                  books={libraryState.books.map((b) => ({ id: b.id, title: b.title }))}
                />
              </section>
            </div>
          {:else}
            <div transition:fly={{ x: 0, y: 20, duration: 200, opacity: 0 }}>
              <ReaderWorkspace
                activeReadingBook={libraryState.getBookById(readerState.activeReadingBookId)}
                readerSettings={settingsState.readerSettings}
                percentage={readerState.percentage}
                searchResponse={searchState.searchResponse}
                searchTargetLocator={searchState.searchTargetLocator}
                isSearching={searchState.isSearching}
                searchUnavailableReason={searchState.searchUnavailableReason}
                preloadedBytes={readerState.preloadedBytes}
                t={appState.t}
                onBackToHome={() => navigationState.backToHome()}
                onPdfPageChange={appState.handlePdfPageChange}
                onPdfSessionProgress={appState.handlePdfSessionProgress}
                onEpubLocationChange={appState.handleEpubLocationChange}
                onReaderLocationContext={appState.handleReaderLocationContext}
                onSearch={(query: string, page: number) => void appState.handleSearch(query, page)}
                onSearchJump={(target: unknown) =>
                  void searchState.handleSearchJump(
                    target as Parameters<typeof searchState.handleSearchJump>[0],
                  )}
              />
            </div>
          {/if}
        {/key}
      </div>
    </main>
  </div>
{:else}
  <main id="main-content" tabindex="-1" class="flex-1 overflow-y-auto relative">
    <ReaderWorkspace
      activeReadingBook={libraryState.getBookById(readerState.activeReadingBookId)}
      readerSettings={settingsState.readerSettings}
      percentage={readerState.percentage}
      searchResponse={searchState.searchResponse}
      searchTargetLocator={searchState.searchTargetLocator}
      isSearching={searchState.isSearching}
      searchUnavailableReason={searchState.searchUnavailableReason}
      preloadedBytes={readerState.preloadedBytes}
      t={appState.t}
      onBackToHome={() => navigationState.backToHome()}
      onPdfPageChange={appState.handlePdfPageChange}
      onPdfSessionProgress={appState.handlePdfSessionProgress}
      onEpubLocationChange={appState.handleEpubLocationChange}
      onReaderLocationContext={appState.handleReaderLocationContext}
      onSearch={(query: string, page: number) => void appState.handleSearch(query, page)}
      onSearchJump={(target: unknown) =>
        void searchState.handleSearchJump(
          target as Parameters<typeof searchState.handleSearchJump>[0],
        )}
    />
  </main>
{/if}
