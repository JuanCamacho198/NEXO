<script lang="ts">
  import { authState, setLocalUser } from '$lib/shared/stores/AuthState.svelte';
  import { savePersistedAuth } from '$lib/shared/stores/authPersistence';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { GoogleLoginButton } from '$lib/features/library';
  import type { MessageKey } from '$lib/shared/i18n';
  import { Button } from '$lib/shared/ui';
  import Hand from 'lucide-svelte/icons/hand';
  import LocalUserForm from './components/LocalUserForm.svelte';
  import type { LocalUserProfile } from '$lib/shared/stores/authPersistence';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();

  let showLocalForm = $state(false);
  let localFormError = $state<string | null>(null);
  let isCreatingLocal = $state(false);

  const DEV_NAME = 'Dev';
  const DEV_EMAIL = 'dev@local';

  /**
   * Dev escape hatch: in `import.meta.env.DEV`, the "Continuar en local"
   * button immediately seeds a mock local user and routes home. The
   * inline form is skipped — keeps dev iteration fast.
   */
  const isDev = import.meta.env.DEV;

  async function handleLocalDev(): Promise<void> {
    if (isCreatingLocal) return;
    isCreatingLocal = true;
    try {
      const profile: LocalUserProfile = {
        name: DEV_NAME,
        email: DEV_EMAIL,
        avatarUrl: null,
        localOnly: true,
      };
      setLocalUser(profile);
      await savePersistedAuth({ kind: 'local', profile });
      navigationState.navigateToHome();
    } finally {
      isCreatingLocal = false;
    }
  }

  async function handleLocalProdSubmit(name: string, email: string | null): Promise<void> {
    if (isCreatingLocal) return;
    const trimmedName = name.trim();
    if (trimmedName.length === 0) {
      localFormError = t('welcome.localNameRequired');
      return;
    }

    isCreatingLocal = true;
    try {
      const profile: LocalUserProfile = {
        name: trimmedName,
        email: email?.trim() ? email.trim() : null,
        avatarUrl: null,
        localOnly: true,
      };
      setLocalUser(profile);
      await savePersistedAuth({ kind: 'local', profile });
      navigationState.navigateToHome();
    } finally {
      isCreatingLocal = false;
    }
  }

  function toggleLocalForm(): void {
    showLocalForm = !showLocalForm;
    localFormError = null;
  }

  // ─── Supabase auth → navigate home ───
  // The Supabase session is already persisted by TauriStorage adapter
  // (supabase-session.json in appDataDir). When a real login completes and
  // authState.isAuthenticated becomes true, navigate to home. The DA-3
  // anonymous fallback session is NOT a login — it must not skip the gate.
  $effect(() => {
    if (authState.isAuthenticated && authState.accessToken) {
      navigationState.navigateToHome();
    }
  });
</script>

<div
  class="flex h-full w-full flex-col overflow-hidden text-(--color-primary)"
  style="background:
    radial-gradient(circle at 15% 10%, rgba(45, 212, 191, 0.15), transparent 50%),
    radial-gradient(circle at 85% 90%, rgba(79, 140, 255, 0.12), transparent 45%),
    linear-gradient(180deg, #0b1120 0%, #0f172a 100%)"
>
  <!-- Main: 2-column layout — brand left, glass login card right.
       lg:flex-row → side-by-side at ≥1024px, stacked below. min-h-0 lets
       flex items shrink so the whole layout fits the viewport. -->
  <main
    class="flex-1 min-h-0 flex flex-col lg:flex-row lg:justify-center gap-10 lg:gap-14 px-5 sm:px-8 lg:px-12 py-5 lg:py-6 overflow-hidden"
  >
    <!-- Left: branding + features. No internal scroll and no page scrollbar:
         the screen is a fixed, overflow-hidden layout that fits the viewport
         (tight spacing below keeps it fitting on short windows). -->
    <section
      class="flex w-full lg:flex-1 lg:max-w-xl min-w-0 flex-col gap-3 my-auto"
      aria-labelledby="welcome-headline"
    >
      <img src="./nexo-horizontal-dark.svg" alt="NEXO" class="h-14 w-auto" />

      <p class="m-0 text-2xs font-semibold uppercase tracking-wider text-(--color-accent-blue)">
        {t('welcome.eyebrow')}
      </p>
      <h1
        id="welcome-headline"
        class="m-0 text-4xl sm:text-5xl font-extrabold leading-tight tracking-tight whitespace-pre-line"
      >
        {t('welcome.headline')}
      </h1>
      <p class="m-0 text-base text-(--color-text-muted) leading-normal">
        {t('welcome.subtitle')}
      </p>

      <ul id="features" class="m-0 p-0 list-none flex flex-col gap-5 mt-0">
        <li class="flex items-start gap-4 min-w-0">
          <div
            class="flex size-12 shrink-0 items-center justify-center rounded-xl bg-(--welcome-feature-blue-bg) text-(--welcome-feature-blue-fg)"
            aria-hidden="true"
          >
            <svg
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path
                d="M12 7v14m0 0H7.5a2.5 2.5 0 0 1 0-5H12m0-7V3a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v5a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1V3m0 0h5.5a2.5 2.5 0 0 1 0 5H12"
              />
            </svg>
          </div>
          <div class="min-w-0">
            <p class="m-0 text-base font-semibold">{t('welcome.feature1Label')}</p>
            <p class="mt-0.5 mb-0 text-sm text-(--color-text-muted) leading-snug">
              {t('welcome.feature1Description')}
            </p>
          </div>
        </li>
        <li class="flex items-start gap-4 min-w-0">
          <div
            class="flex size-12 shrink-0 items-center justify-center rounded-xl bg-(--welcome-feature-green-bg) text-(--welcome-feature-green-fg)"
            aria-hidden="true"
          >
            <svg
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path
                d="M16.862 4.487l1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L6.832 19.82a4.5 4.5 0 0 1-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 0 1 1.13-1.897L16.863 4.487z"
              />
            </svg>
          </div>
          <div class="min-w-0">
            <p class="m-0 text-base font-semibold">{t('welcome.feature2Label')}</p>
            <p class="mt-0.5 mb-0 text-sm text-(--color-text-muted) leading-snug">
              {t('welcome.feature2Description')}
            </p>
          </div>
        </li>
        <li class="flex items-start gap-4 min-w-0">
          <div
            class="flex size-12 shrink-0 items-center justify-center rounded-xl bg-(--welcome-feature-purple-bg) text-(--welcome-feature-purple-fg)"
            aria-hidden="true"
          >
            <svg
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M3 3v18h18M7 16l4-4 4 4 6-6" />
            </svg>
          </div>
          <div class="min-w-0">
            <p class="m-0 text-base font-semibold">{t('welcome.feature3Label')}</p>
            <p class="mt-0.5 mb-0 text-sm text-(--color-text-muted) leading-snug">
              {t('welcome.feature3Description')}
            </p>
          </div>
        </li>
        <li class="flex items-start gap-4 min-w-0">
          <div
            class="flex size-12 shrink-0 items-center justify-center rounded-xl bg-(--welcome-feature-cyan-bg) text-(--welcome-feature-cyan-fg)"
            aria-hidden="true"
          >
            <svg
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9z" />
            </svg>
          </div>
          <div class="min-w-0">
            <p class="m-0 text-base font-semibold">{t('welcome.feature4Label')}</p>
            <p class="mt-0.5 mb-0 text-sm text-(--color-text-muted) leading-snug">
              {t('welcome.feature4Description')}
            </p>
          </div>
        </li>
      </ul>
    </section>

    <!-- Right: login card. The resolved height chain stays the same as the
         previous layout: App h-screen → main flex-1 overflow-hidden →
         section max-h-full → card max-h-full overflow-y-auto, so the card
         becomes the sole scroll container on short viewports. -->
    <section
      class="relative flex flex-col min-w-0 min-h-0 max-h-full items-center justify-center w-full max-w-md my-auto mx-auto lg:mx-0"
      aria-label={t('welcome.signInAria')}
    >
      <!-- Decorative blue orb behind the card -->
      <div
        class="pointer-events-none absolute -top-14 -right-6 size-64 rounded-full bg-(--welcome-card-orb) blur-[56px]"
        aria-hidden="true"
      ></div>

      <div
        data-testid="welcome-login-card"
        class="relative z-10 w-full max-h-full overflow-y-auto rounded-[24px] border border-(--welcome-card-border) bg-(--welcome-card-bg) p-5 lg:p-8 shadow-(--welcome-card-shadow) backdrop-blur-[21px]"
      >
        <h2 class="m-0 mb-1 flex items-center justify-center gap-2 text-2xl font-bold">
          <span>{t('welcome.cardTitle')}</span>
          <Hand size={20} strokeWidth={1.8} class="shrink-0 -rotate-6 text-(--color-accent-blue)" />
        </h2>
        <p class="m-0 mb-6 text-center text-sm text-(--color-text-muted)">
          {t('welcome.cardSubtitle')}
        </p>

        <!-- Google sign-in: reuses the existing GoogleLoginButton with the
             i18n `t` prop. The button shows "Continue with Google" because
             the user is not yet signed in. -->
        <GoogleLoginButton {t} />

        <!-- Divider -->
        <div class="my-4 flex items-center gap-3" aria-hidden="true">
          <div class="h-px flex-1 bg-(--color-border)"></div>
          <span class="text-xs uppercase tracking-wider text-(--color-text-muted)">
            {t('welcome.divider')}
          </span>
          <div class="h-px flex-1 bg-(--color-border)"></div>
        </div>

        <!-- Local sign-in: dev escape vs prod inline form -->
        {#if isDev}
          <div class="flex flex-col gap-2">
            <Button
              onclick={() => void handleLocalDev()}
              variant="secondary"
              disabled={isCreatingLocal}
              class="w-full border-gray-600 bg-[#1e293b] hover:bg-[#263449]"
            >
              {t('welcome.continueLocal')}
            </Button>
            <p class="m-0 text-center text-2xs text-(--color-text-muted)">
              {t('welcome.devSkipHint')}
            </p>
          </div>
        {:else}
          {#if !showLocalForm}
            <Button
              onclick={toggleLocalForm}
              variant="secondary"
              disabled={isCreatingLocal}
              class="w-full border-gray-600 bg-[#1e293b] hover:bg-[#263449]"
            >
              {t('welcome.continueLocal')}
            </Button>
          {:else}
            <LocalUserForm
              {t}
              error={localFormError}
              isSubmitting={isCreatingLocal}
              onCancel={toggleLocalForm}
              onSubmit={(name, email) => handleLocalProdSubmit(name, email)}
            />
          {/if}
        {/if}
      </div>

      <!-- Create account link below the card -->
      <button
        type="button"
        class="mt-5 cursor-pointer border-none bg-transparent p-0 text-sm text-(--color-text-muted) hover:text-(--color-primary) transition-colors"
      >
        {t('welcome.cardCreateAccount')}
      </button>
    </section>
  </main>

  <!-- Footer: 3 trust items -->
  <footer
    id="trust"
    class="flex flex-col lg:flex-row items-start lg:items-center justify-center gap-6 lg:gap-24 px-6 py-4 shrink-0"
  >
    <div class="flex items-center gap-3">
      <svg
        width="20"
        height="20"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        class="shrink-0 text-(--welcome-footer-icon)"
        aria-hidden="true"
      >
        <path
          d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z"
        />
      </svg>
      <div class="flex flex-col">
        <span class="text-sm font-medium">{t('welcome.footer.item1')}</span>
        <span class="text-xs text-(--color-text-muted)">{t('welcome.footer.item1Desc')}</span>
      </div>
    </div>
    <div class="flex items-center gap-3">
      <svg
        width="20"
        height="20"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        class="shrink-0 text-(--welcome-footer-icon)"
        aria-hidden="true"
      >
        <path
          d="M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3.6 9h16.8M3.6 15h16.8M12 3c2.21 2.37 3.46 5.36 3.6 8.5-.14 3.14-1.39 6.13-3.6 8.5-2.21-2.37-3.46-5.36-3.6-8.5.14-3.14 1.39-6.13 3.6-8.5z"
        />
      </svg>
      <div class="flex flex-col">
        <span class="text-sm font-medium">{t('welcome.footer.item2')}</span>
        <span class="text-xs text-(--color-text-muted)">{t('welcome.footer.item2Desc')}</span>
      </div>
    </div>
    <div class="flex items-center gap-3">
      <svg
        width="20"
        height="20"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        class="shrink-0 text-(--welcome-footer-icon)"
        aria-hidden="true"
      >
        <path
          d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8M3 3v5h5M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16M21 21v-5h-5"
        />
      </svg>
      <div class="flex flex-col">
        <span class="text-sm font-medium">{t('welcome.footer.item3')}</span>
        <span class="text-xs text-(--color-text-muted)">{t('welcome.footer.item3Desc')}</span>
      </div>
    </div>
  </footer>
</div>
