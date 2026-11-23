<script lang="ts">
  import { authState, setLocalUser } from '$lib/shared/stores/AuthState.svelte';
  import { savePersistedAuth } from '$lib/shared/stores/authPersistence';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { GoogleLoginButton } from '$lib/features/library';
  import type { MessageKey } from '$lib/shared/i18n';
  import { Button } from '$lib/shared/ui';
  import BookOpen from 'lucide-svelte/icons/book-open';
  import Cloud from 'lucide-svelte/icons/cloud';
  import Hand from 'lucide-svelte/icons/hand';
  import Highlighter from 'lucide-svelte/icons/highlighter';
  import LocalUserForm from './components/LocalUserForm.svelte';
  import type { LocalUserProfile } from '$lib/shared/stores/authPersistence';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();

  // Three promises, one per reason to open the app. Sync is the one a sign-in
  // buys, so it closes the list; stats are found once inside.
  const features = [
    { icon: BookOpen, label: 'welcome.feature1Label', description: 'welcome.feature1Description' },
    {
      icon: Highlighter,
      label: 'welcome.feature2Label',
      description: 'welcome.feature2Description',
    },
    { icon: Cloud, label: 'welcome.feature4Label', description: 'welcome.feature4Description' },
  ] as const;

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

<!-- NEXO ember: the navy ground with the brand's warm light rising from the
  card's side, the same atmosphere as the browser page shown after sign-in. -->
<div
  class="flex h-full w-full flex-col overflow-hidden text-(--color-primary)"
  style="background:
    radial-gradient(52rem 34rem at 88% 12%, rgba(247, 120, 26, 0.16), transparent 62%),
    radial-gradient(40rem 30rem at 0% 100%, rgba(255, 166, 32, 0.06), transparent 60%),
    linear-gradient(180deg, #0a1322 0%, #0d1828 100%)"
>
  <main
    class="flex-1 min-h-0 flex flex-col lg:flex-row lg:items-center lg:justify-center gap-10 lg:gap-20 px-5 sm:px-8 lg:px-12 py-6 overflow-hidden"
  >
    <!-- Left: the brand and what NEXO does, in three lines. -->
    <section
      class="flex w-full lg:flex-1 lg:max-w-xl min-w-0 flex-col gap-4 my-auto"
      aria-labelledby="welcome-headline"
    >
      <img src="./nexo-horizontal-dark.svg" alt="NEXO" class="h-12 w-auto mr-auto ml-0" />
      <p
        class="m-0 mt-2 text-xs font-semibold uppercase tracking-[0.14em] text-(--color-brand-text)"
      >
        {t('welcome.eyebrow')}
      </p>
      <h1
        id="welcome-headline"
        class="m-0 text-4xl sm:text-5xl font-extrabold leading-[1.08] tracking-[-0.03em] whitespace-pre-line"
      >
        {t('welcome.headline')}
      </h1>
      <p class="m-0 max-w-lg text-base text-(--color-text-muted) leading-relaxed">
        {t('welcome.subtitle')}
      </p>

      <ul id="features" class="m-0 mt-4 p-0 list-none flex flex-col gap-4">
        {#each features as feature (feature.label)}
          {@const FeatureIcon = feature.icon}
          <li class="flex items-center gap-4 min-w-0">
            <span
              class="flex size-11 shrink-0 items-center justify-center rounded-full bg-(--color-brand-soft) text-(--color-brand-text)"
              aria-hidden="true"
            >
              <FeatureIcon size={20} strokeWidth={1.8} />
            </span>
            <div class="min-w-0">
              <p class="m-0 text-base font-semibold">{t(feature.label)}</p>
              <p class="mt-0.5 mb-0 text-sm text-(--color-text-muted) leading-snug">
                {t(feature.description)}
              </p>
            </div>
          </li>
        {/each}
      </ul>
    </section>

    <!-- Right: the sign-in card. The height chain is unchanged: section
         max-h-full → card max-h-full overflow-y-auto, so the card becomes the
         sole scroll container on short windows. -->
    <section
      class="relative flex flex-col min-w-0 min-h-0 max-h-full items-center justify-center w-full max-w-md my-auto mx-auto lg:mx-0"
      aria-label={t('welcome.signInAria')}
    >
      <div
        data-testid="welcome-login-card"
        class="relative z-10 w-full max-h-full overflow-y-auto rounded-(--radius-xl) border border-(--color-edge) bg-(--color-home-card)/80 p-6 lg:p-8 shadow-[0_30px_60px_-30px_rgba(0,0,0,0.7)] backdrop-blur-xl"
      >
        <h2
          class="m-0 mb-1.5 flex items-center justify-center gap-2 text-2xl font-bold tracking-tight"
        >
          <span>{t('welcome.cardTitle')}</span>
          <Hand size={20} strokeWidth={1.8} class="shrink-0 -rotate-6 text-(--color-brand-text)" />
        </h2>
        <p class="m-0 mb-7 text-center text-sm text-(--color-text-muted)">
          {t('welcome.cardSubtitle')}
        </p>

        <GoogleLoginButton {t} />

        <div class="my-5 flex items-center gap-3" aria-hidden="true">
          <div class="h-px flex-1 bg-(--color-edge)"></div>
          <span class="text-xs uppercase tracking-wider text-(--color-text-muted)">
            {t('welcome.divider')}
          </span>
          <div class="h-px flex-1 bg-(--color-edge)"></div>
        </div>

        {#if isDev}
          <div class="flex flex-col gap-2">
            <Button
              onclick={() => void handleLocalDev()}
              variant="secondary"
              fullWidth
              disabled={isCreatingLocal}
            >
              {t('welcome.continueLocal')}
            </Button>
            <p class="m-0 text-center text-xs text-(--color-text-muted)">
              {t('welcome.localHint')}
            </p>
            <p class="m-0 text-center text-micro text-(--color-text-muted)/70">
              {t('welcome.devSkipHint')}
            </p>
          </div>
        {:else if !showLocalForm}
          <div class="flex flex-col gap-2">
            <Button
              onclick={toggleLocalForm}
              variant="secondary"
              fullWidth
              disabled={isCreatingLocal}
            >
              {t('welcome.continueLocal')}
            </Button>
            <p class="m-0 text-center text-xs text-(--color-text-muted)">
              {t('welcome.localHint')}
            </p>
          </div>
        {:else}
          <LocalUserForm
            {t}
            error={localFormError}
            isSubmitting={isCreatingLocal}
            onCancel={toggleLocalForm}
            onSubmit={(name, email) => handleLocalProdSubmit(name, email)}
          />
        {/if}
      </div>
    </section>
  </main>
</div>
