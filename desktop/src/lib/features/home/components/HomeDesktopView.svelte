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

<div class="space-y-5">
  <h1 class="sr-only text-2xl">{t('home.pageTitle')}</h1>

  {#if navbarActions}
    <div class="flex items-center justify-end">
      {@render navbarActions()}
    </div>
  {/if}

  <section aria-labelledby="home-active-reading-heading">
    <h2 id="home-active-reading-heading" class="sr-only">{t('home.activeReading')}</h2>

    <div
      class="min-w-0 rounded-(--radius-xl) border border-(--color-border) bg-(--color-panel-accent) p-4"
    >
      {#if continueSection}
        {@render continueSection()}
      {:else}
        <p class="text-sm text-(--color-text-muted)">{t('home.continueReadingPlaceholder')}</p>
      {/if}
    </div>
  </section>

  <HomeStatsGrid
    isLoading={isLoadingStats}
    disabledReason={statsUnavailableReason}
    {streakDays}
    {isLoadingStreak}
    {t}
  />

  <HomeMainContent {t} {shelfSection} {onNavigateLibrary} />
</div>
