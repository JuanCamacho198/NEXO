<script lang="ts">
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
    variant="secondary"
    size="sm"
    role="switch"
    aria-checked={checked}
    data-testid={testId}
    class="self-start gap-2.5"
    onclick={ontoggle}
  >
    <span
      class={`w-9 h-5 rounded-full relative transition-colors ${checked ? 'bg-(--color-success)' : 'bg-(--color-border)'}`}
    >
      <span
        class={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${checked ? 'left-4.5' : 'left-0.5'}`}
      ></span>
    </span>
    <span>{label}</span>
    <span class="text-xs text-(--color-text-muted)">{hint}</span>
  </Button>
{/snippet}

<Panel title={t('settings.notifications.delivery.title')}>
  <div class="flex flex-col gap-3">
    <p class="text-xs text-(--color-text-muted)">
      {t('settings.notifications.delivery.description')}
    </p>
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
    <div class="flex items-center gap-3 pl-1 text-xs text-(--color-text-muted)">
      <label class="flex items-center gap-2">
        {t('settings.notifications.delivery.quietStart')}
        <input
          type="time"
          value={prefs.quietHours.start}
          disabled={!prefs.quietHours.enabled}
          data-testid="notification-quiet-start"
          class="rounded-lg border border-(--color-border) bg-(--color-background) px-2 py-1 text-xs text-(--color-primary) disabled:opacity-60"
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
          class="rounded-lg border border-(--color-border) bg-(--color-background) px-2 py-1 text-xs text-(--color-primary) disabled:opacity-60"
          onchange={changeQuietEnd}
        />
      </label>
    </div>
  </div>
</Panel>
