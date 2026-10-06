<!--
  NotificationArrivalAnnouncer — polite arrival announcements (NOTIF-06).

  The tray dialog only exists while open, so it cannot announce arrivals that
  land while it is closed. This always-mounted live region watches the unread
  count and announces each new arrival once, rendered through the active
  translator. Opening the tray marks everything read (the count drops), which
  is never announced — only growth is.
-->
<script lang="ts">
  import { notificationCenter } from '$lib/shared/stores/notificationCenter.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();

  let announcement = $state('');
  let baseline: number | null = $state(null);

  $effect(() => {
    const count = notificationCenter.unreadCount;
    if (baseline === null) {
      baseline = count;
      return;
    }
    if (count > baseline) {
      const latest = [...notificationCenter.items].reverse().find((entry) => entry.readAt === null);
      announcement =
        latest !== undefined
          ? t('notifications.center.newArrival', {
              message: t(latest.i18nKey, latest.i18nParams),
            })
          : t('notifications.bell.unread', { count });
    }
    baseline = count;
  });
</script>

<div class="sr-only" role="status" aria-live="polite">{announcement}</div>
