<script lang="ts">
  import type { ProfileSessionViewModel } from '../profileSession';
  import type { MessageKey } from '$lib/shared/i18n';
  import Avatar from '$lib/shared/ui/forms/Avatar.svelte';

  type Props = {
    profile: ProfileSessionViewModel;
    isProfileLoading: boolean;
    profileError: string | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { profile, isProfileLoading, profileError, t }: Props = $props();
</script>

{#if profileError}
  <p
    class="mb-2 rounded border border-(--color-error)/40 bg-(--color-error-soft) px-2 py-1 text-xs text-(--color-primary)"
  >
    {profileError}
  </p>
{/if}

<article class="flex gap-3 items-start">
  <Avatar src={profile.avatarUrl ?? undefined} name={profile.name} size="lg" />

  <div class="min-w-0 flex-1 flex flex-col gap-3">
    <div class="flex flex-col gap-1">
      <span class="text-2xs font-medium text-(--color-text-muted)"
        >{t('settings.profile.nameLabel')}</span
      >
      <span class="text-sm text-(--color-primary) font-semibold wrap-break">
        {isProfileLoading ? t('settings.profile.loading') : profile.name}
      </span>
    </div>

    {#if profile.isSignedIn}
      <div class="border-t border-(--color-border) pt-3 flex flex-col gap-1">
        <span class="text-2xs font-medium text-(--color-text-muted)"
          >{t('settings.profile.emailLabel')}</span
        >
        <span class="text-sm text-(--color-primary) wrap-break">
          {isProfileLoading ? t('settings.profile.loading') : profile.email}
        </span>
      </div>
    {:else}
      <div class="border-t border-(--color-border) pt-3">
        <span
          class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-(--color-accent-soft) text-(--color-accent-start) text-2xs font-medium w-fit"
        >
          {t('settings.profile.localMode')}
        </span>
      </div>
    {/if}
  </div>
</article>
