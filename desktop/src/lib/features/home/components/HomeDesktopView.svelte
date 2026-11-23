<script lang="ts">
  import { onMount } from 'svelte';
  import type { Snippet } from 'svelte';
  import HomeStatsGrid from './HomeStatsGrid.svelte';
  import HomeMainContent from './HomeMainContent.svelte';
  import type { MessageKey } from '$lib/i18n';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';

  type Props = {
    isLoadingStats?: boolean;
    statsUnavailableReason?: string | null;
    streakDays?: number;
    isLoadingStreak?: boolean;
    selectedBookTitle?: string | null;
    onRefreshStats?: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    activeRoute?: 'home' | 'highlights' | 'settings';
    onNavigateHome?: () => void;
    onNavigateHighlights?: () => void;
    onNavigateSettings?: () => void;
    onNavigateLibrary?: () => void;
    navbarActions?: Snippet;
    continueSection?: Snippet;
    shelfSection?: Snippet;
    continueCount?: number;
    shelfCount?: number;
    statsMinutes?: number;
  };

  let {
    isLoadingStats = false,
    statsUnavailableReason = null,
    streakDays = 0,
    isLoadingStreak = false,
    t,
    navbarActions,
    continueSection,
    shelfSection,
    onNavigateLibrary,
  }: Props = $props();

  onMount(() => {
    void statsState.loadStreak(undefined, authState.userId ?? '');
  });
</script>

<div>
  <h1 class="sr-only text-2xl">{t('home.pageTitle')}</h1>

  <section class="relative" aria-labelledby="home-active-reading-heading">
    <h2 id="home-active-reading-heading" class="sr-only">{t('home.activeReading')}</h2>

    {#if navbarActions}
      <!-- Shares the heading row of "continue reading", top-right. -->
      <div class="absolute top-0 right-0 z-10 flex items-center">
        {@render navbarActions()}
      </div>
    {/if}

    <div class="min-w-0">
      {#if continueSection}
        {@render continueSection()}
      {:else}
        <p class="text-sm text-(--color-text-muted)">{t('home.continueReadingPlaceholder')}</p>
      {/if}
    </div>
  </section>

  <div class="mt-5">
    <HomeStatsGrid
      isLoading={isLoadingStats}
      disabledReason={statsUnavailableReason}
      {streakDays}
      {isLoadingStreak}
      {t}
    />
  </div>

  <div class="mt-10">
    <HomeMainContent {t} {shelfSection} {onNavigateLibrary} />
  </div>
</div>
