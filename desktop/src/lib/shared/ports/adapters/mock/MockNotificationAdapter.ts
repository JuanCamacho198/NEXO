import type { NotificationPort } from '$lib/shared/ports/NotificationPort';
import type { Notification } from '$lib/shared/types/notification';
import {
  NOTIFICATION_RETENTION_MAX_AGE_MS,
  NOTIFICATION_RETENTION_MAX_COUNT,
} from '$lib/shared/services/notificationRetention';

/**
 * In-memory notification history for tests and non-Tauri hosts. Mirrors the
 * Rust repository semantics: newest-first reads, idempotent per-item read,
 * clear-all and both retention bounds.
 */
export class MockNotificationAdapter implements NotificationPort {
  #rows: Notification[] = [];

  /** Test seam: preload the persisted history. */
  seed(notifications: Notification[]): void {
    this.#rows = notifications.map((row) => ({ ...row }));
  }

  async list(): Promise<Notification[]> {
    return [...this.#rows]
      .sort((a, b) => b.createdAt - a.createdAt || (a.id < b.id ? 1 : -1))
      .map((row) => ({ ...row }));
  }

  async save(notification: Notification): Promise<void> {
    this.#rows = this.#rows.filter((row) => row.id !== notification.id);
    this.#rows.push({ ...notification });
  }

  async markRead(id: string, readAt: number): Promise<boolean> {
    const row = this.#rows.find((item) => item.id === id);
    if (!row || row.readAt !== null) return false;
    row.readAt = readAt;
    return true;
  }

  async markAllRead(readAt: number): Promise<number> {
    let changed = 0;
    for (const row of this.#rows) {
      if (row.readAt === null) {
        row.readAt = readAt;
        changed++;
      }
    }
    return changed;
  }

  async clear(): Promise<number> {
    const removed = this.#rows.length;
    this.#rows = [];
    return removed;
  }

  async prune(now: number): Promise<number> {
    const cutoff = now - NOTIFICATION_RETENTION_MAX_AGE_MS;
    let removed = 0;
    const fresh = this.#rows.filter((row) => {
      if (row.createdAt < cutoff) {
        removed++;
        return false;
      }
      return true;
    });
    fresh.sort((a, b) => a.createdAt - b.createdAt);
    const kept = fresh.slice(Math.max(0, fresh.length - NOTIFICATION_RETENTION_MAX_COUNT));
    removed += fresh.length - kept.length;
    this.#rows = kept;
    return removed;
  }
}
