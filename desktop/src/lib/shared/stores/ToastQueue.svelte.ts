/**
 * Global toast queue (REQ-04/05/06) — Svelte 5 runes store.
 *
 * Module-level `$state` queue so any module (SettingsPanel, AppState) can push
 * toasts that survive navigation: ToastHost renders the queue from AppModals,
 * which lives outside AppRouter (it stays mounted across route changes,
 * including the welcome branch).
 */
import type { NotificationTarget } from '$lib/shared/types/notification';

export type ToastType = 'success' | 'info' | 'error';

/**
 * The button a toast carries: either a notification deep link (NOTIF-04) or a
 * plain callback (e.g. "Undo").
 */
export type ToastAction =
  | { label: string; notificationId: string; target?: NotificationTarget; run?: never }
  | { label: string; run: () => void; notificationId?: never; target?: never };

export interface ToastItem {
  id: number;
  type: ToastType;
  message: string;
  action?: ToastAction;
}

// ─── Reactive State ───────────────────────────────────────────────────

let queue: ToastItem[] = $state([]);
let nextId = 1;

// ─── Public API ───────────────────────────────────────────────────────

export function pushToast(type: ToastType, message: string, action?: ToastAction): void {
  queue.push(action ? { id: nextId++, type, message, action } : { id: nextId++, type, message });
}

export function dismiss(id: number): void {
  const index = queue.findIndex((item) => item.id === id);
  if (index >= 0) {
    queue.splice(index, 1);
  }
}

export const toastQueue = {
  get items(): ToastItem[] {
    return queue;
  },
};
