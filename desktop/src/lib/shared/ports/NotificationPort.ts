import type { Notification } from '$lib/shared/types/notification';

/**
 * Persistence port for the local notification history (NOTIF-02).
 *
 * Mirrors the Rust commands one-to-one. `list()` returns newest-first; the
 * store reverses into its chronological in-memory order.
 */
export interface NotificationPort {
  list(): Promise<Notification[]>;
  save(notification: Notification): Promise<void>;
  /** Marks one item read; resolves to whether an unread row actually changed. */
  markRead(id: string, readAt: number): Promise<boolean>;
  /** Marks every unread item read; resolves to how many rows changed. */
  markAllRead(readAt: number): Promise<number>;
  /** Deletes the whole history; resolves to how many rows were removed. */
  clear(): Promise<number>;
  /** Applies both durable retention bounds; resolves to rows removed. */
  prune(now: number): Promise<number>;
}
