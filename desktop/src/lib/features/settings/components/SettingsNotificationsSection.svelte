<script lang="ts">
  import SwitchTrack from '$lib/shared/ui/forms/SwitchTrack.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import {
    getCachedNotificationPreferences,
    loadNotificationPreferences,
    updateNotificationPreferences,
    type NotificationPreferences,
  } from '$lib/shared/services/notificationPreferences';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = { t: (key: MessageKey) => string };
  let { t }: Props = $props();

  let prefs = $state<NotificationPreferences>(getCachedNotificationPreferences());

  $effect(() => {
    void loadNotificationPreferences().then((loaded) => {
      prefs = loaded;
    });
  });

  async function toggleSystem(): Promise<void> {
    prefs = await updateNotificationPreferences({ system: !prefs.system });
  }

  async function toggleNudges(): Promise<void> {
    prefs = await updateNotificationPreferences({ nudge: !prefs.nudge });
  }

  async function toggleQuietHours(): Promise<void> {
    prefs = await updateNotificationPreferences({
      quietHours: { enabled: !prefs.quietHours.enabled },
    });
  }

  async function changeQuietStart(event: Event): Promise<void> {
    const value = (event.target as HTMLInputElement).value;
    if (value) prefs = await updateNotificationPreferences({ quietHours: { start: value } });
  }

  async function changeQuietEnd(event: Event): Promise<void> {
    const value = (event.target as HTMLInputElement).value;
    if (value) prefs = await updateNotificationPreferences({ quietHours: { end: value } });
  }
</script>

{#snippet switchRow(
  testId: string,
  checked: boolean,
  label: string,
  hint: string,
  ontoggle: () => void,
)}
  <Button
    variant="ghost"
    size="md"
    role="switch"
    aria-checked={checked}
    data-testid={testId}
    class="-mx-4 w-[calc(100%+2rem)] gap-4"
    onclick={ontoggle}
  >
    <span class="flex min-w-0 flex-1 flex-col items-start gap-0.5 text-left whitespace-normal">
      <span class="text-(--color-primary)">{label}</span>
      <span class="text-xs font-normal text-(--color-text-muted)">{hint}</span>
    </span>
    <SwitchTrack {checked} />
  </Button>
{/snippet}

<Panel
  title={t('settings.notifications.delivery.title')}
  subtitle={t('settings.notifications.delivery.description')}
>
  <div class="flex flex-col gap-1">
    {@render switchRow(
      'notification-pref-system',
      prefs.system,
      t('settings.notifications.delivery.system'),
      t('settings.notifications.delivery.systemHint'),
      () => void toggleSystem(),
    )}
    {@render switchRow(
      'notification-pref-nudge',
      prefs.nudge,
      t('settings.notifications.delivery.nudges'),
      t('settings.notifications.delivery.nudgesHint'),
      () => void toggleNudges(),
    )}
    {@render switchRow(
      'notification-quiet-enabled',
      prefs.quietHours.enabled,
      t('settings.notifications.delivery.quietHours'),
      t('settings.notifications.delivery.quietHoursHint'),
      () => void toggleQuietHours(),
    )}
    <div class="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2 text-sm text-(--color-text-muted)">
      <label class="flex items-center gap-2">
        {t('settings.notifications.delivery.quietStart')}
        <input
          type="time"
          value={prefs.quietHours.start}
          disabled={!prefs.quietHours.enabled}
          data-testid="notification-quiet-start"
          class="rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-3 py-1.5 text-sm text-(--color-primary) tabular-nums focus:border-(--color-brand)/60 focus:outline-none disabled:opacity-50"
          onchange={changeQuietStart}
        />
      </label>
      <label class="flex items-center gap-2">
        {t('settings.notifications.delivery.quietEnd')}
        <input
          type="time"
          value={prefs.quietHours.end}
          disabled={!prefs.quietHours.enabled}
          data-testid="notification-quiet-end"
          class="rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-3 py-1.5 text-sm text-(--color-primary) tabular-nums focus:border-(--color-brand)/60 focus:outline-none disabled:opacity-50"
          onchange={changeQuietEnd}
        />
      </label>
    </div>
  </div>
</Panel>
