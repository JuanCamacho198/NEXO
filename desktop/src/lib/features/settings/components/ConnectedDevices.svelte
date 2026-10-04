<script lang="ts">
  import type { DeviceViewModel } from '$lib/services/devices';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    devices: DeviceViewModel[];
    error: string | null;
    isLoading: boolean;
    onremove: (id: string) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { devices, error, isLoading, onremove, t }: Props = $props();

  /** Devices that are NOT the current one */
  let otherDevices = $derived(devices.filter((d) => !d.isCurrent));

  /** The current device (null if not found) */
  let currentDevice = $derived(devices.find((d) => d.isCurrent) ?? null);

  function formatRelative(lastActive: {
    value: number;
    unit: 'now' | 'min' | 'hour' | 'day';
  }): string {
    if (lastActive.unit === 'now') {
      return t('settings.connectedDevices.justNow');
    }
    if (lastActive.unit === 'min') {
      return t('settings.connectedDevices.minAgo', { count: lastActive.value });
    }
    if (lastActive.unit === 'hour') {
      return t('settings.connectedDevices.hourAgo', { count: lastActive.value });
    }
    return t('settings.connectedDevices.dayAgo', { count: lastActive.value });
  }

  function handleRemove(device: DeviceViewModel): void {
    if (confirm(t('settings.connectedDevices.removeConfirm', { name: device.name }))) {
      onremove(device.id);
    }
  }

  function deviceSubtitle(device: DeviceViewModel): string {
    const base = device.os;
    if (device.isCurrent) {
      return `${base} · ${t('settings.connectedDevices.lastActive')}: ${formatRelative(device.lastActive)}`;
    }
    return `${base} · ${formatRelative(device.lastActive)}`;
  }
</script>

{#if error}
  <p
    class="mb-3 rounded border border-(--color-error)/40 bg-(--color-error-soft) px-2 py-1 text-xs text-(--color-primary)"
  >
    {error}
  </p>
{/if}

<div class="flex flex-col gap-3">
  {#if isLoading && !currentDevice}
    <p class="text-xs text-(--color-text-muted)">{t('settings.connectedDevices.loading')}</p>
  {:else}
    <!-- Current device card — always visible -->
    {#if currentDevice}
      {@render DeviceCard({
        device: currentDevice,
        subtitle: deviceSubtitle(currentDevice),
        isCurrent: true,
        t,
      })}
    {/if}

    <!-- Other devices -->
    {#if otherDevices.length > 0}
      <div class="flex items-center gap-2">
        <span class="h-px flex-1 bg-(--color-border)"></span>
        <span class="shrink-0 text-2xs text-(--color-text-muted) uppercase tracking-wider">
          {t('settings.connectedDevices.count', { count: otherDevices.length })}
        </span>
        <span class="h-px flex-1 bg-(--color-border)"></span>
      </div>

      {#each otherDevices as device (device.id)}
        {@render DeviceCard({
          device,
          subtitle: deviceSubtitle(device),
          isCurrent: false,
          t,
          onremove: () => handleRemove(device),
        })}
      {/each}
    {:else if !isLoading && currentDevice}
      <!-- Empty state: no other devices -->
      <div class="flex flex-col items-center gap-2 py-4 text-center">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="32"
          height="32"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          class="text-(--color-text-muted) opacity-50"
          aria-hidden="true"
        >
          <rect x="2" y="3" width="20" height="14" rx="2" />
          <line x1="8" y1="21" x2="16" y2="21" />
          <line x1="12" y1="17" x2="12" y2="21" />
        </svg>
        <p class="m-0 text-xs text-(--color-text-muted)">
          {t('settings.connectedDevices.noDevices')}
        </p>
      </div>
    {/if}
  {/if}
</div>

{#snippet DeviceCard({
  device,
  subtitle,
  isCurrent,
  t,
  onremove,
}: {
  device: DeviceViewModel;
  subtitle: string;
  isCurrent: boolean;
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
  onremove?: () => void;
})}
  <div
    class="flex items-center gap-3 rounded-lg border border-(--color-border) bg-(--color-surface) px-3 py-2.5"
    class:bg-(--color-accent-soft)={isCurrent}
  >
    <!-- Device type icon -->
    {#if device.icon === 'laptop'}
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        class="shrink-0 text-(--color-text-muted)"
        aria-hidden="true"
      >
        <rect x="2" y="3" width="20" height="14" rx="2" />
        <line x1="2" y1="20" x2="22" y2="20" />
        <path d="M8 20l.5-2h7l.5 2" />
      </svg>
    {:else if device.icon === 'phone'}
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        class="shrink-0 text-(--color-text-muted)"
        aria-hidden="true"
      >
        <rect x="5" y="2" width="14" height="20" rx="3" />
        <line x1="12" y1="18" x2="12.01" y2="18" stroke-width="2" />
      </svg>
    {:else if device.icon === 'tablet'}
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        class="shrink-0 text-(--color-text-muted)"
        aria-hidden="true"
      >
        <rect x="4" y="2" width="16" height="20" rx="3" />
        <line x1="12" y1="18" x2="12.01" y2="18" stroke-width="2" />
      </svg>
    {:else if device.icon === 'globe'}
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        class="shrink-0 text-(--color-text-muted)"
        aria-hidden="true"
      >
        <circle cx="12" cy="12" r="10" />
        <ellipse cx="12" cy="12" rx="4" ry="10" />
        <path d="M2 12h20" />
      </svg>
    {:else}
      <!-- Fallback: unknown/null icon renders the laptop glyph (defensive) -->
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        class="shrink-0 text-(--color-text-muted)"
        aria-hidden="true"
      >
        <rect x="2" y="3" width="20" height="14" rx="2" />
        <line x1="2" y1="20" x2="22" y2="20" />
        <path d="M8 20l.5-2h7l.5 2" />
      </svg>
    {/if}

    <!-- Info -->
    <div class="min-w-0 flex-1">
      <div class="flex items-center gap-2">
        <span class="truncate text-sm font-medium text-(--color-primary)">
          {device.name}
        </span>
        {#if isCurrent}
          <span
            class="shrink-0 rounded-md bg-(--color-accent-soft) px-1.5 py-0.5 text-2xs font-medium text-(--color-accent-start)"
          >
            {t('settings.connectedDevices.thisDevice')}
          </span>
        {/if}
      </div>
      <p class="m-0 mt-0.5 truncate text-2xs text-(--color-text-muted)">
        {subtitle}
      </p>
    </div>

    <!-- Remove button -->
    {#if !isCurrent && onremove}
      <button
        class="shrink-0 cursor-pointer border-none bg-transparent p-0 text-xs text-(--color-error) transition-opacity duration-150 hover:opacity-80"
        aria-label={t('settings.connectedDevices.removeLabel', { name: device.name })}
        onclick={onremove}
      >
        {t('settings.connectedDevices.remove')}
      </button>
    {/if}
  </div>
{/snippet}
