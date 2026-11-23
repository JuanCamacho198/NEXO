<!--
  NotificationCenter — the local tray dialog (NOTIF-06).

  Every row renders from its stored `i18nKey` + `i18nParams` through the active
  translator at render time: switching the UI language re-renders history
  without touching the store, and no resolved string is ever stored in the
  list UI. Repeated events sharing one `source` + `i18nKey` collapse into a
  single group with a count; expanding the group reveals each stored row with
  its own target and read state. Grouping is presentational — stored rows are
  never merged.

  Keyboard: every row and every group toggle is a native button (Tab to move,
  Enter/Space to activate). Escape, backdrop close and focus return are owned
  by the Modal facade. The global `:focus-visible` floor draws the ring, so no
  control here suppresses its outline.
-->
<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import {
    notificationCenter,
    clearNotifications,
  } from '$lib/shared/stores/notificationCenter.svelte';
  import { groupNotifications } from '$lib/shared/services/notificationGroups';
  import { openNotification } from '$lib/shared/services/notificationNavigation';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { Notification } from '$lib/shared/types/notification';

  type Props = {
    open: boolean;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { open = $bindable(), t }: Props = $props();

  const groups = $derived(groupNotifications(notificationCenter.items));

  /** Group keys currently expanded to their stored rows. */
  let expanded = $state(new Set<string>());

  function isExpanded(key: string): boolean {
    return expanded.has(key);
  }

  function toggleGroup(key: string): void {
    const next = new Set(expanded);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    expanded = next;
  }

  /**
   * Heading convention shared with the toast surface: the data label wins
   * when the emitter passed one (`name`), otherwise the rendered key.
   */
  function headingFor(entry: Notification): string {
    const name = entry.i18nParams?.name;
    return name !== undefined ? String(name) : t(entry.i18nKey, entry.i18nParams);
  }

  function detailFor(entry: Notification): string | null {
    const detail = entry.i18nParams?.detail;
    return detail !== undefined ? String(detail) : null;
  }

  function isFailure(entry: Notification): boolean {
    return entry.severity === 'error' || entry.severity === 'warning';
  }

  /**
   * Opens one entry: always marks it read; closes the center only when the
   * target navigated somewhere real. Unknown targets stay on the tray.
   */
  function handleOpen(entry: Notification): void {
    if (openNotification(entry)) {
      open = false;
    }
  }
</script>

{#snippet entryButton(entry: Notification)}
  <button
    type="button"
    class="w-full rounded-(--radius-md) px-2 py-2.5 text-left transition-colors hover:bg-(--color-surface-hover) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
    onclick={() => handleOpen(entry)}
  >
    <div class="flex items-center gap-3">
      <span
        class="flex size-7 shrink-0 items-center justify-center rounded-full {isFailure(entry)
          ? 'bg-(--color-error)/12 text-(--color-error)'
          : 'bg-(--color-success)/12 text-(--color-success)'}"
      >
        {#if isFailure(entry)}
          <CircleAlert size={15} strokeWidth={2} aria-hidden="true" />
        {:else}
          <CircleCheck size={15} strokeWidth={2} aria-hidden="true" />
        {/if}
        <span class="sr-only">
          {isFailure(entry) ? t('notifications.state.failure') : t('notifications.state.success')}
        </span>
      </span>
      <p class="text-sm font-medium text-(--color-primary)">{headingFor(entry)}</p>
      <time
        class="ml-auto shrink-0 text-xs tabular-nums text-(--color-text-muted)"
        datetime={new Date(entry.createdAt).toISOString()}
      >
        {new Date(entry.createdAt).toLocaleString()}
      </time>
    </div>
    {#if detailFor(entry)}
      <p class="mt-0.5 truncate pl-10 text-xs text-(--color-text-muted)">{detailFor(entry)}</p>
    {/if}
  </button>
{/snippet}

<Modal bind:open title={t('notifications.center.title')} size="md">
  {#if groups.length === 0}
    <div class="flex flex-col items-center justify-center py-12 text-center">
      <p class="text-lg font-semibold tracking-tight text-(--color-primary)">
        {t('notifications.center.emptyTitle')}
      </p>
      <p class="mt-2 max-w-xs text-sm leading-relaxed text-(--color-text-muted)">
        {t('notifications.center.emptyDescription')}
      </p>
    </div>
  {:else}
    <ul class="m-0 flex list-none flex-col divide-y divide-(--color-edge) p-0">
      {#each groups as group (group.key)}
        {#if group.entries.length === 1}
          <li class="py-1">
            {@render entryButton(group.entries[0])}
          </li>
        {:else}
          {@const representative = group.entries[0]}
          <li class="py-1">
            <button
              type="button"
              class="w-full rounded-(--radius-md) px-2 py-2.5 text-left transition-colors hover:bg-(--color-surface-hover) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
              aria-expanded={isExpanded(group.key)}
              aria-label={isExpanded(group.key)
                ? t('notifications.center.collapse')
                : t('notifications.center.expand', { count: group.entries.length })}
              onclick={() => toggleGroup(group.key)}
            >
              <div class="flex items-center gap-3">
                <span
                  class="flex size-7 shrink-0 items-center justify-center rounded-full {isFailure(
                    representative,
                  )
                    ? 'bg-(--color-error)/12 text-(--color-error)'
                    : 'bg-(--color-success)/12 text-(--color-success)'}"
                  aria-hidden="true"
                >
                  {#if isFailure(representative)}
                    <CircleAlert size={15} strokeWidth={2} />
                  {:else}
                    <CircleCheck size={15} strokeWidth={2} />
                  {/if}
                </span>
                <p class="text-sm font-medium text-(--color-primary)">
                  {t(representative.i18nKey)}
                </p>
                <span
                  class="shrink-0 rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-2 py-0.5 text-xs font-semibold tabular-nums text-(--color-secondary)"
                  aria-hidden="true"
                >
                  ×{group.entries.length}
                </span>
                <span class="sr-only">
                  {t('notifications.center.groupCount', { count: group.entries.length })}
                </span>
                <time
                  class="ml-auto shrink-0 text-xs tabular-nums text-(--color-text-muted)"
                  datetime={new Date(representative.createdAt).toISOString()}
                >
                  {new Date(representative.createdAt).toLocaleString()}
                </time>
              </div>
            </button>
            {#if isExpanded(group.key)}
              <ul
                class="m-0 mt-1 mb-1 ml-5 flex list-none flex-col border-l border-(--color-edge) p-0 pl-2"
              >
                {#each group.entries as entry (entry.id)}
                  <li>
                    {@render entryButton(entry)}
                  </li>
                {/each}
              </ul>
            {/if}
          </li>
        {/if}
      {/each}
    </ul>
  {/if}
  {#snippet footer()}
    {#if groups.length > 0}
      <Button variant="secondary" size="sm" onclick={() => clearNotifications()}>
        {t('notifications.center.clear')}
      </Button>
    {/if}
  {/snippet}
</Modal>
