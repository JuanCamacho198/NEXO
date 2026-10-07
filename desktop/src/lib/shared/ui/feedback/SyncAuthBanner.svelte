<script lang="ts">
  /**
   * SyncAuthBanner — global re-auth banner (D7, SR-3).
   *
   * Renders when `syncAlertStore.current` holds a typed AUTH_REQUIRED /
   * AUTH_EXPIRED alert from a sync/Drive path. Shows the redacted message and
   * a "Sign in with Google" CTA; dismissible. Cleared automatically when the
   * user re-authenticates (the store watches authState — D7).
   */
  import { syncAlertStore } from '$lib/shared/stores/syncAlert.svelte';
  import { signInWithGoogle } from '$lib/shared/services';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { logger } from '$lib/shared/logger/Logger';

  const alert = $derived(syncAlertStore.current);

  let isSigningIn = $state(false);

  // D7: a fresh authenticated session (SIGNED_IN/TOKEN_REFRESHED — both flip
  // `authState.isAuthenticated` to true) clears the banner so re-auth resumes
  // sync. The DA-3 anonymous fallback session is not a re-auth and must not
  // clear the banner. Runs only when the tracked accessToken changes — a Drive
  // AUTH_REQUIRED while still signed in does NOT re-trigger it (the banner stays).
  $effect(() => {
    if (authState.isAuthenticated) syncAlertStore.clear();
  });

  async function handleReAuth(): Promise<void> {
    if (isSigningIn) return;
    isSigningIn = true;
    try {
      await signInWithGoogle();
    } catch (e) {
      logger.error(
        'Sync re-auth failed:',
        { error: e instanceof Error ? e.message : String(e) },
        'sync',
      );
    } finally {
      isSigningIn = false;
    }
  }
</script>

{#if alert}
  <div
    data-testid="sync-auth-banner"
    role="alert"
    aria-live="assertive"
    tabindex="-1"
    class="fixed top-0 left-0 right-0 z-40 border-b border-red-300 bg-red-50 text-red-900 shadow-sm"
  >
    <div class="mx-auto flex max-w-5xl items-center gap-3 px-4 py-2.5 lg:px-6">
      <!-- Icon -->
      <div class="shrink-0 text-red-600" aria-hidden="true">
        <svg class="size-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            d="M12 9v2m0 4h.01M5.07 19h13.86c1.54 0 2.5-1.67 1.73-3L13.73 4c-.77-1.33-2.69-1.33-3.46 0L3.34 16c-.77 1.33.19 3 1.73 3z"
          />
        </svg>
      </div>

      <!-- Message -->
      <div class="min-w-0 flex-1">
        <p class="m-0 truncate text-sm font-semibold" title={alert.message}>
          {alert.message}
        </p>
      </div>

      <!-- Re-auth CTA (SR-3.1) -->
      <button
        type="button"
        class="shrink-0 rounded-md bg-red-600 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-600/40 disabled:opacity-60"
        onclick={handleReAuth}
        disabled={isSigningIn}
      >
        {isSigningIn ? 'Signing in…' : 'Sign in with Google'}
      </button>

      <!-- Dismiss -->
      <button
        type="button"
        class="shrink-0 rounded-md p-1 transition-colors hover:bg-black/5 focus:outline-none focus:ring-2 focus:ring-current/30"
        onclick={() => syncAlertStore.clear()}
        aria-label="Dismiss auth alert"
      >
        <svg
          class="size-4"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          stroke-width="2.25"
          aria-hidden="true"
        >
          <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
    </div>
  </div>
{/if}
