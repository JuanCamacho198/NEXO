<script lang="ts">
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import {
    notificationCenter,
    clearNotifications,
    type NotificationKind,
  } from '$lib/shared/stores/notificationCenter.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    open: boolean;
    t: (key: MessageKey) => string;
  };

  let { open = $bindable(), t }: Props = $props();

  const kindKey: Record<NotificationKind, MessageKey> = {
    'import-success': 'notifications.kind.importSuccess',
    'import-failure': 'notifications.kind.importFailure',
    'sync-success': 'notifications.kind.syncSuccess',
    'sync-failure': 'notifications.kind.syncFailure',
  };

  const isFailure: Record<NotificationKind, boolean> = {
    'import-success': false,
    'import-failure': true,
    'sync-success': false,
    'sync-failure': true,
  };
</script>

<Modal bind:open title={t('notifications.center.title')} size="md">
  {#if notificationCenter.items.length === 0}
    <div class="flex flex-col items-center justify-center py-12 text-center">
      <p class="text-lg font-semibold tracking-tight text-(--color-primary)">
        {t('notifications.center.emptyTitle')}
      </p>
      <p class="mt-2 max-w-xs text-sm leading-relaxed text-(--color-text-muted)">
        {t('notifications.center.emptyDescription')}
      </p>
    </div>
  {:else}
    <ul class="flex flex-col gap-2">
      {#each [...notificationCenter.items].reverse() as entry (entry.id)}
        <li class="rounded-xl border border-(--color-border) bg-(--color-surface) px-4 py-3">
          <div class="flex items-center gap-2">
            <span
              class="inline-block size-2 shrink-0 rounded-full"
              class:bg-emerald-500={!isFailure[entry.kind]}
              class:bg-red-500={isFailure[entry.kind]}
              aria-hidden="true"
            ></span>
            <p class="text-sm font-medium text-(--color-primary)">{t(kindKey[entry.kind])}</p>
            <time
              class="ml-auto shrink-0 text-xs text-(--color-text-muted)"
              datetime={new Date(entry.at).toISOString()}
            >
              {new Date(entry.at).toLocaleString()}
            </time>
          </div>
          <p class="mt-1 truncate text-sm text-(--color-primary)">{entry.title}</p>
          {#if entry.message}
            <p class="mt-0.5 truncate text-xs text-(--color-text-muted)">{entry.message}</p>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
  {#snippet footer()}
    {#if notificationCenter.items.length > 0}
      <button
        class="rounded-lg px-3 py-1.5 text-sm font-medium text-(--color-text-muted) transition-colors hover:bg-(--color-panel-accent) hover:text-(--color-primary)"
        onclick={() => clearNotifications()}
      >
        {t('notifications.center.clear')}
      </button>
    {/if}
  {/snippet}
</Modal>
