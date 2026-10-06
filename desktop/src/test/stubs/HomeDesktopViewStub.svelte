<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { MessageKey } from '../../lib/i18n';

  type Props = {
    isLoadingStats?: boolean;
    statsUnavailableReason?: string | null;
    selectedBookTitle?: string | null;
    activeRoute?: 'home' | 'highlights' | 'settings';
    onNavigateHome?: () => void;
    onNavigateHighlights?: () => void;
    onNavigateSettings?: () => void;
    onNavigateLibrary?: () => void;
    onRefreshStats?: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    navbarActions?: Snippet;
    continueSection?: Snippet;
    shelfSection?: Snippet;
  };

  let {
    selectedBookTitle = null,
    onNavigateHome,
    onNavigateHighlights,
    onNavigateSettings,
    onNavigateLibrary,
    navbarActions,
    continueSection,
    shelfSection,
  }: Props = $props();

  // svelte-ignore state_referenced_locally
  void onNavigateLibrary;
</script>

<section data-testid="home-desktop-view-stub">
  <nav>
    <button type="button" onclick={onNavigateHome}>Bookshelf</button>
    <button type="button" onclick={onNavigateHighlights}>Highlights</button>
    <button type="button" onclick={onNavigateSettings}>Settings</button>
  </nav>

  <p data-testid="selected-book-title">{selectedBookTitle ?? ''}</p>

  <div>
    {@render navbarActions?.()}
  </div>

  <div data-testid="continue-section">
    {@render continueSection?.()}
  </div>

  <div data-testid="shelf-section">
    {@render shelfSection?.()}
  </div>
</section>
