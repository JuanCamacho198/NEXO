<script lang="ts">
  import { onMount } from 'svelte';
  import { debugState } from './lib/shared/debug/debugState.svelte';
  import DebugToggle from '$lib/shared/debug/DebugToggle.svelte';
  import DebugPanel from '$lib/shared/debug/DebugPanel.svelte';
  import { appState } from '$lib/shared/stores/AppState.svelte';
  import AppRouter from '$lib/shared/ui/layout/AppRouter.svelte';
  import AppModals from '$lib/shared/ui/layout/AppModals.svelte';
  import ImportProgressBanner from '$lib/shared/ui/feedback/ImportProgressBanner.svelte';
  import SyncAuthBanner from '$lib/shared/ui/feedback/SyncAuthBanner.svelte';
  import CustomTitlebar from '$lib/shared/ui/layout/CustomTitlebar.svelte';
  import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
  import { titlebarState } from '$lib/stores/titlebarState.svelte';
  import { isCustomTitlebarPlatform } from '$lib/shared/utils/platform';
  import { installGlobalShortcuts, ShortcutHelpModal, CommandPalette } from '$lib/shared/shortcuts';
  import { type as osType } from '@tauri-apps/plugin-os';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
  import {
    defaultUpdateCheckDeps,
    resolveUpdateFeedUrl,
    runStartupUpdateCheck,
  } from '$lib/features/settings/update/updateChecker';

  onMount(() => {
    try {
      titlebarState.isCustomTitlebar = isCustomTitlebarPlatform(osType());
    } catch {
      titlebarState.isCustomTitlebar = false;
    }
  });

  onMount(() => {
    appState.init();

    // Global error handler (replaces Svelte 5's missing ErrorBoundary)
    const handleError = (event: ErrorEvent | PromiseRejectionEvent): void => {
      const message =
        event instanceof PromiseRejectionEvent
          ? (event.reason?.message ?? event.reason?.toString() ?? 'Unhandled Promise rejection')
          : event.message;
      console.error('[App] Uncaught error:', event);
      try {
        pushToast('error', message);
      } catch {}
      if (event instanceof PromiseRejectionEvent && typeof event.preventDefault === 'function') {
        try {
          event.preventDefault();
        } catch {}
      }
    };

    window.addEventListener('error', handleError);
    window.addEventListener('unhandledrejection', handleError);
    const uninstallShortcuts = installGlobalShortcuts();

    return () => {
      window.removeEventListener('error', handleError);
      window.removeEventListener('unhandledrejection', handleError);
      uninstallShortcuts();
    };
  });

  $effect(() => {
    debugState.currentRoute = navigationState.route;
  });

  // Startup update check: runs once per launch, whether or not the user ever
  // opens Settings → About. A verified newer version surfaces a toast so the
  // notice is actually delivered; the actionable update flow stays in About.
  // The module guard inside runStartupUpdateCheck keeps it to one check.
  onMount(() => {
    const settleTimer = setTimeout(() => {
      void (async () => {
        const state = await runStartupUpdateCheck(defaultUpdateCheckDeps(resolveUpdateFeedUrl()));
        if (state?.status === 'available') {
          pushToast('info', appState.t('update.availableToast', { version: state.feedVersion }));
        }
      })();
    }, 1500);
    return () => clearTimeout(settleTimer);
  });

  // Track viewport for debug panel
  $effect(() => {
    if (typeof window === 'undefined') return;
    debugState.updateViewport();
    const ro = new ResizeObserver(() => debugState.updateViewport());
    ro.observe(document.body);
    return () => ro.disconnect();
  });

  // Dynamically update HTML lang attribute when locale changes
  $effect(() => {
    document.documentElement.lang = settingsState.locale;
  });
</script>

<div class="flex flex-col h-screen bg-(--color-background) text-(--color-primary)">
  {#if titlebarState.isCustomTitlebar}
    <CustomTitlebar hidden={readerState.isFullscreen} />
  {/if}
  <main class="flex flex-1 overflow-hidden">
    <!-- Skip link: visible only on keyboard focus for keyboard/screen reader users -->
    <a
      href="#main-content"
      class="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-1/2 focus:-translate-x-1/2 focus:z-100 focus:px-4 focus:py-2.5 focus:rounded-lg focus:bg-(--color-accent-blue) focus:text-(--color-background) focus:text-sm focus:font-semibold focus:shadow-lg focus:outline-none focus:ring-2 focus:ring-white/50"
    >
      {appState.t('app.skipToContent')}
    </a>
    <AppRouter />
    <AppModals />
    <ImportProgressBanner />
    <SyncAuthBanner />
    <DebugToggle />
    <DebugPanel />
    <ShortcutHelpModal t={appState.t} />
    <CommandPalette t={appState.t} />
  </main>
</div>
