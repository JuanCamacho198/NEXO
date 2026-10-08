<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import type { AccessGroup } from '$lib/shared/services/catalog';
  import type { AddonReadState } from '../useAddonReadSheet.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    state: AddonReadState;
    onClose: () => void;
    onRetry: () => void;
    onAllow: () => void;
    onDeny: () => void;
    onCancel: () => void;
  };

  let { t, state, onClose, onRetry, onAllow, onDeny, onCancel }: Props = $props();

  const GROUP_ORDER: readonly AccessGroup[] = ['FREE', 'BUY', 'SUBSCRIBE'];

  const GROUP_LABEL: Record<AccessGroup, MessageKey> = {
    FREE: 'discover.accessGroupFree',
    BUY: 'discover.accessGroupBuy',
    SUBSCRIBE: 'discover.accessGroupSubscribe',
  };
</script>

<!--
  AddonReadSheet — presentational sheet for the 7 read states (slice 9):
  Hidden composes nothing; Resolving/Downloading show progress; Loaded lists
  the resolved access (grouped external options + legal notice); Empty shows
  the no-options copy; Error shows the failure copy with retry; ConsentRequired
  reuses the informed-consent copy with allow/deny. Purely presentational:
  every value arrives via props and every action leaves through an on* callback.
-->
{#if state.kind !== 'Hidden'}
  <div role="dialog" aria-label={t('addons.readSheet.resolving')} class="flex flex-col gap-2">
    {#if state.kind === 'Resolving'}
      <p class="m-0 text-sm">{t('addons.readSheet.resolving')}</p>
    {:else if state.kind === 'Downloading'}
      <p class="m-0 text-sm">{t('addons.readSheet.downloading')}</p>
      <progress class="w-full" value={state.downloaded} max={state.total ?? undefined}>
        {state.downloaded}
      </progress>
      <p class="m-0 text-xs opacity-70">
        {state.downloaded}{state.total !== null ? ` / ${state.total}` : ''}
      </p>
      <div class="flex gap-2">
        <button type="button" class="text-sm" onclick={() => onCancel()}>
          {t('discover.downloadCancel')}
        </button>
        <button type="button" class="text-sm" onclick={() => onClose()}>
          {t('discover.dismiss')}
        </button>
      </div>
    {:else if state.kind === 'Loaded'}
      <p class="m-0 text-sm font-medium">{state.addonName}</p>
      <p class="m-0 text-xs opacity-70">{t('addons.readSheet.legalNotice')}</p>
      {#each GROUP_ORDER as group (group)}
        {@const options = state.access.options.filter((option) => option.group === group)}
        {#if options.length > 0}
          <div class="mt-1">
            <p class="m-0 text-micro font-medium text-(--color-text-muted)">
              {t(GROUP_LABEL[group])}
            </p>
            <ul class="m-0 mt-1 flex list-none flex-col gap-0.5 p-0">
              {#each options as option (option.url)}
                <li>
                  <a href={option.url} target="_blank" rel="noreferrer" class="text-sm">
                    {t(option.titleKey)}
                  </a>
                </li>
              {/each}
            </ul>
          </div>
        {/if}
      {/each}
      <div class="flex gap-2">
        <button type="button" class="text-sm" onclick={() => onClose()}>
          {t('discover.dismiss')}
        </button>
      </div>
    {:else if state.kind === 'Empty'}
      <p class="m-0 text-sm font-medium">{t('addons.readSheet.empty.title')}</p>
      <p class="m-0 text-sm opacity-70">{t('addons.readSheet.empty.body')}</p>
      <div class="flex gap-2">
        <button type="button" class="text-sm" onclick={() => onClose()}>
          {t('discover.dismiss')}
        </button>
      </div>
    {:else if state.kind === 'Error'}
      <p class="m-0 text-sm">{t('addons.readSheet.error')}</p>
      <div class="flex gap-2">
        <button type="button" class="text-sm" onclick={() => onRetry()}>
          {t('discover.retry')}
        </button>
        <button type="button" class="text-sm" onclick={() => onClose()}>
          {t('discover.dismiss')}
        </button>
      </div>
    {:else if state.kind === 'ConsentRequired'}
      <p class="m-0 text-sm font-medium">{t('addons.consent.title')}</p>
      <p class="m-0 text-sm opacity-70">{t('addons.consent.body', { name: state.addonName })}</p>
      <div class="flex gap-2">
        <button type="button" class="text-sm" onclick={() => onAllow()}>
          {t('addons.consent.allow')}
        </button>
        <button type="button" class="text-sm" onclick={() => onDeny()}>
          {t('addons.consent.deny')}
        </button>
      </div>
    {/if}
  </div>
{/if}
