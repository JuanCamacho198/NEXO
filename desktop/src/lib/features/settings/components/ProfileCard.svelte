<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { ProfileSessionViewModel } from '../profileSession';
  import type { MessageKey } from '$lib/shared/i18n';
  import Avatar from '$lib/shared/ui/forms/Avatar.svelte';

  type Props = {
    profile: ProfileSessionViewModel;
    isProfileLoading: boolean;
    profileError: string | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    /** Account actions placed at the row's end (sign out). */
    actions?: Snippet;
  };

  let { profile, isProfileLoading, profileError, t, actions }: Props = $props();
</script>

{#if profileError}
  <p
    class="mb-3 rounded border border-(--color-error)/40 bg-(--color-error-soft) px-2 py-1 text-xs text-(--color-primary)"
  >
    {profileError}
  </p>
{/if}

<!-- One account row: who is signed in, the session state, and its action. The
  name and address speak for themselves, so they carry no field labels. -->
<article class="flex flex-wrap items-center gap-x-4 gap-y-3">
  <Avatar src={profile.avatarUrl ?? undefined} name={profile.name} size="lg" />

  <div class="min-w-0 flex-1 basis-56">
    <p class="m-0 truncate text-xl font-semibold tracking-tight text-(--color-primary)">
      {isProfileLoading ? t('settings.profile.loading') : profile.name}
    </p>
    {#if profile.isSignedIn}
      <p class="m-0 truncate text-sm text-(--color-text-muted)">
        {isProfileLoading ? t('settings.profile.loading') : profile.email}
      </p>
      <p
        class="m-0 mt-2 inline-flex items-center gap-1.5 text-xs font-medium text-(--color-success)"
      >
        <span class="size-1.5 rounded-full bg-(--color-success)" aria-hidden="true"></span>
        {t('settings.profile.syncOn')}
      </p>
    {:else}
      <span
        class="mt-2 inline-flex w-fit items-center gap-1.5 rounded-full border border-(--color-brand)/40 bg-(--color-brand-soft) px-2.5 py-0.5 text-xs font-medium text-(--color-brand-text)"
      >
        {t('settings.profile.localMode')}
      </span>
    {/if}
  </div>

  {#if actions}
    <div class="flex shrink-0 items-center gap-2">
      {@render actions()}
    </div>
  {/if}
</article>
