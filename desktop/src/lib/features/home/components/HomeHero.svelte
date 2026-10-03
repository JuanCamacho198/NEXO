<script lang="ts">
  import type { Snippet } from 'svelte';
  import { profileSessionFromAuthState } from '$lib/features/settings/profileSession';
  import Avatar from '$lib/shared/ui/forms/Avatar.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    actions?: Snippet;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { actions, t }: Props = $props();

  // Read the profile from reactive auth state. Updates automatically
  // when the user signs in / out or refreshes their Google profile.
  const profile = $derived(profileSessionFromAuthState());
</script>

<section
  class="flex items-center justify-between gap-4 rounded-(--radius-xl) border border-(--color-border) bg-(--color-surface) px-4 py-3 shadow-(--shadow-soft)"
  aria-label={t('home.pageTitle')}
>
  <div class="flex min-w-0 flex-1 items-center gap-3">
    <Avatar
      src={profile.avatarUrl ?? undefined}
      name={profile.name}
      size="md"
      class="border-(--color-border-strong)"
    />
    <div class="min-w-0">
      <h2 class="truncate text-lg font-semibold tracking-tight text-(--color-primary)">
        {#if profile.isSignedIn}
          {t('home.greetingName', { name: profile.name })}
        {:else}
          {t('home.greeting')}
        {/if}
      </h2>
      <p class="mt-0.5 truncate text-sm text-(--color-text-muted)">{t('home.heroDescription')}</p>
    </div>
  </div>

  {#if actions}
    <div class="flex shrink-0 items-center gap-2">
      {@render actions()}
    </div>
  {/if}
</section>
