<script lang="ts">
  import { onMount } from 'svelte';
  import { Button } from '$lib/shared/ui';
  import Toast from '$lib/shared/ui/feedback/Toast.svelte';
  import { signInWithGoogle } from '$lib/shared/services';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { logger } from '$lib/shared/logger/Logger';
  import type { MessageKey } from '$lib/shared/i18n';

  type Translator = (key: MessageKey, params?: Record<string, string | number>) => string;

  /**
   * Default `t` that returns the key. Keeps the component self-contained for
   * callers that haven't been migrated to i18n yet (e.g. the existing
   * SettingsPanel usage). When a real `t` is passed, all visible strings
   * route through the i18n dictionary.
   */
  const defaultT: Translator = (key) => String(key);

  let { t = defaultT }: { t?: Translator } = $props();

  let isLoggingIn = $state(false);
  let showSuccessToast = $state(false);
  let mounted = $state(false);

  onMount(() => {
    // Defer the transition watcher until after the first paint so a persisted
    // session from a previous run doesn't fire the success toast on reload.
    queueMicrotask(() => {
      mounted = true;
    });
  });

  // Show a success toast only on a false→true transition AFTER mount, i.e. a
  // fresh login — not on app reload with a persisted session. Uses the
  // authenticated predicate so the anonymous fallback session never reads as a
  // successful Google login.
  let prevAuthenticated = $state(authState.isAuthenticated);
  $effect(() => {
    if (mounted && !prevAuthenticated && authState.isAuthenticated) {
      showSuccessToast = true;
    }
    prevAuthenticated = authState.isAuthenticated;
  });

  async function handleLogin(): Promise<void> {
    try {
      isLoggingIn = true;
      logger.debug('Initiating Supabase Google login...', undefined, 'app_shell');
      await signInWithGoogle();
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : String(error);
      logger.error('Login Error:', { error: msg }, 'app_shell');
      alert(t('errors.commandFailure') + ': ' + msg);
    } finally {
      isLoggingIn = false;
    }
  }
</script>

{#if authState.isAuthenticated}
  <div
    class="flex w-full items-center justify-center gap-2 rounded-md border border-(--color-success)/30 bg-(--color-success)/10 px-4 py-2 text-sm font-medium text-(--color-primary)"
    role="status"
    aria-live="polite"
  >
    <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
    </svg>
    <span
      >{t('welcome.signedInAs', { email: authState.email ?? t('welcome.signedInFallback') })}</span
    >
  </div>
{:else}
  <Button
    variant="secondary"
    class="w-full flex items-center justify-center gap-2 bg-white text-gray-900 border-gray-300 hover:bg-gray-100 px-3 py-2 text-sm rounded-xl"
    onclick={handleLogin}
    disabled={isLoggingIn}
  >
    {#if isLoggingIn}
      {t('welcome.loggingIn')}
    {:else}
      <svg
        width="18"
        height="18"
        viewBox="0 0 18 18"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
      >
        <path
          d="M17.64 9.20455C17.64 8.56636 17.5827 7.95273 17.4764 7.36364H9V10.845H13.8436C13.635 11.97 13.0009 12.9232 12.0477 13.5614V15.8195H14.9564C16.6582 14.2527 17.64 11.9455 17.64 9.20455Z"
          fill="#4285F4"
        />
        <path
          d="M9 18C11.43 18 13.4673 17.1941 14.9564 15.8195L12.0477 13.5614C11.2418 14.1014 10.2109 14.4205 9 14.4205C6.65591 14.4205 4.67182 12.8373 3.96409 10.71H0.957275V13.0418C2.43818 15.9832 5.48182 18 9 18Z"
          fill="#34A853"
        />
        <path
          d="M3.96409 10.71C3.78409 10.17 3.68182 9.59318 3.68182 9C3.68182 8.40682 3.78409 7.82999 3.96409 7.28999V4.95818H0.957275C0.347727 6.17318 0 7.54773 0 9C0 10.4523 0.347727 11.8268 0.957275 13.0418L3.96409 10.71Z"
          fill="#FBBC05"
        />
        <path
          d="M9 3.57955C10.3214 3.57955 11.5077 4.03364 12.4405 4.92545L15.0218 2.34409C13.4632 0.891818 11.4259 0 9 0C5.48182 0 2.43818 2.01682 0.957275 4.95818L3.96409 7.28999C4.67182 5.16273 6.65591 3.57955 9 3.57955Z"
          fill="#EA4335"
        />
      </svg>
      {t('welcome.continueGoogle')}
    {/if}
  </Button>
{/if}

<Toast
  type="success"
  message={t('welcome.signedInToast')}
  bind:visible={showSuccessToast}
  onDismiss={() => (showSuccessToast = false)}
/>
