/**
 * notificationOs — best-effort OS delivery for the notification system
 * (NOTIF-03). Sends through `@tauri-apps/plugin-notification` when it is
 * available; any failure (missing plugin, denied permission, non-Tauri host)
 * resolves silently so tray/toast delivery is never affected.
 *
 * Rule 2 (system + unfocused), rule 3 (never while reading) and rule 5 (never
 * OS for a nudge until NOTIF-05 preferences exist) are enforced by the policy
 * before this module is reached: it only sends what it is asked to send.
 */

import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from '@tauri-apps/plugin-notification';

export interface OsNotificationSink {
  send(title: string, body: string): Promise<unknown> | unknown;
}

let sinkOverride: OsNotificationSink | null = null;
let permissionAttempted = false;

/** Replaces the OS sink (tests). Pass null to restore the real sender. */
export function setOsNotificationSink(sink: OsNotificationSink | null): void {
  sinkOverride = sink;
}

/** Resets module state between tests. */
export function resetNotificationOsForTests(): void {
  sinkOverride = null;
  permissionAttempted = false;
}

async function realSend(title: string, body: string): Promise<void> {
  try {
    if (!permissionAttempted) {
      permissionAttempted = true;
      const granted = await isPermissionGranted();
      if (!granted) {
        await requestPermission();
      }
    }
    sendNotification({ title, body });
  } catch {
    // Best-effort: OS delivery must never break tray/toast.
  }
}

/**
 * Sends one OS notification. Never rejects: every failure path resolves
 * silently because OS delivery is advisory, never a delivery guarantee.
 */
export async function sendOsNotification(title: string, body: string): Promise<void> {
  try {
    if (sinkOverride) {
      await sinkOverride.send(title, body);
      return;
    }
    await realSend(title, body);
  } catch {
    // Ignore: see module docstring.
  }
}
