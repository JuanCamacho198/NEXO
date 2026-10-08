import type { NotificationPort } from '$lib/shared/ports/NotificationPort';
import type { Notification } from '$lib/shared/types/notification';
import * as tauriClient from '$lib/shared/api/tauriClient';

export class TauriNotificationAdapter implements NotificationPort {
  list(): Promise<Notification[]> {
    return tauriClient.listNotifications();
  }

  save(notification: Notification): Promise<void> {
    return tauriClient.saveNotification(notification);
  }

  markRead(id: string, readAt: number): Promise<boolean> {
    return tauriClient.markNotificationRead(id, readAt);
  }

  markAllRead(readAt: number): Promise<number> {
    return tauriClient.markAllNotificationsRead(readAt);
  }

  clear(): Promise<number> {
    return tauriClient.clearAllNotifications();
  }

  prune(now: number): Promise<number> {
    return tauriClient.pruneNotifications(now);
  }
}
