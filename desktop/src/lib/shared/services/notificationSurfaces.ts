/**
 * notificationSurfaces — live context and single dispatch for the
 * notification system (NOTIF-03).
 *
 * `notify(...)` resolves `isWindowFocused` / `isReading` here instead of
 * static defaults, then dispatches the accepted entry to exactly the
 * surfaces its policy resolution allows: tray (already stored by the
 * caller), one in-app toast via the existing `ToastQueue`, and one OS
 * notification via `notificationOs`. A per-surface delivered set makes
 * double-dispatch of the same event impossible even if dispatch is
 * re-entered.
 *
 * Canonical sources (no new state invented):
 * - Focus: the live window focus state (Tauri `onFocusChanged` when
 *   available, `window` focus/blur listeners otherwise, `document.hasFocus`
 *   as the initial value).
 * - Reading: `readerState.activeReadingBookId !== null` — a book is open in
 *   the reader, so rule 3 (total silence while reading) applies.
 */
import { get } from 'svelte/store';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { i18n } from '$lib/shared/i18n';
import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
import { pushToast, type ToastAction, type ToastType } from '$lib/shared/stores/ToastQueue.svelte';
import type {
  Notification,
  NotificationI18nParams,
  Severity,
} from '$lib/shared/types/notification';
import type { MessageKey } from '$lib/shared/i18n';
import type { PolicyResolution } from '$lib/shared/services/notificationPolicy';
import { sendOsNotification } from '$lib/shared/services/notificationOs';
import { resolveNotificationTarget } from '$lib/shared/services/notificationNavigation';

// ─── Injectable sinks (tests) ─────────────────────────────────────────

export interface ToastSink {
  push(type: ToastType, message: string, action?: ToastAction): void;
}

let toastSink: ToastSink | null = null;

/** Replaces the toast sink (tests). Pass null to restore `ToastQueue`. */
export function setToastSink(sink: ToastSink | null): void {
  toastSink = sink;
}

// ─── Live focus ───────────────────────────────────────────────────────

let focusCache: boolean | null = null;
let focusTrackingStarted = false;

function documentHasFocusFallback(): boolean {
  if (typeof document === 'undefined' || typeof document.hasFocus !== 'function') return true;
  try {
    return document.hasFocus();
  } catch {
    return true;
  }
}

function startFocusTracking(): void {
  if (focusTrackingStarted) return;
  focusTrackingStarted = true;
  focusCache = documentHasFocusFallback();
  if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') return;
  window.addEventListener('focus', () => {
    focusCache = true;
  });
  window.addEventListener('blur', () => {
    focusCache = false;
  });
  try {
    const appWindow = getCurrentWindow();
    void appWindow
      .onFocusChanged(({ payload: focused }) => {
        focusCache = focused;
      })
      .catch(() => {
        // Non-Tauri host: DOM listeners above are the source of truth.
      });
  } catch {
    // Non-Tauri host: DOM listeners above are the source of truth.
  }
}

/** Live window focus state for the policy (`true` when unknown). */
export function isWindowFocusedLive(): boolean {
  startFocusTracking();
  if (focusCache !== null) return focusCache;
  return documentHasFocusFallback();
}

/** Test seam: pins the live focus value without touching listeners. */
export function setWindowFocusedForTests(focused: boolean | null): void {
  focusCache = focused;
}

// ─── Live reading state ───────────────────────────────────────────────

/**
 * Whether the reader is currently reading, from the canonical reading
 * state (`readerState.activeReadingBookId`). Never throws: an unreadable
 * state means "not reading".
 */
export function isReadingLive(): boolean {
  try {
    return readerState.activeReadingBookId !== null;
  } catch {
    return false;
  }
}

// ─── Message rendering ────────────────────────────────────────────────

function translate(
  key: MessageKey,
  params?: NotificationI18nParams,
): { heading: string; text: string; label: string; viewLabel: string } {
  let locale = i18n.DEFAULT_LOCALE;
  try {
    locale = get(i18n.locale);
  } catch {
    // Keep the default locale.
  }
  const label = i18n.t(locale, key, params);
  const viewLabel = i18n.t(locale, 'notifications.action.view');
  const name = params?.name !== undefined ? String(params.name) : null;
  const detail = params?.detail !== undefined ? String(params.detail) : null;
  const heading = name ?? label;
  const text = detail ? `${heading}: ${detail}` : heading;
  return { heading, text, label, viewLabel };
}

function toastTypeFor(severity: Severity): ToastType {
  if (severity === 'success') return 'success';
  if (severity === 'error') return 'error';
  return 'info';
}

function pushToastMessage(type: ToastType, message: string, action?: ToastAction): void {
  if (toastSink) {
    toastSink.push(type, message, action);
    return;
  }
  pushToast(type, message, action);
}

// ─── Exactly-once dispatch ────────────────────────────────────────────

/** Keys of already-delivered (notification id, surface) pairs. */
const deliveredSurfaces = new Set<string>();

function markDelivered(id: string, surface: 'toast' | 'os'): boolean {
  const key = `${id}:${surface}`;
  if (deliveredSurfaces.has(key)) return false;
  deliveredSurfaces.add(key);
  return true;
}

/**
 * Routes one accepted notification to its policy-allowed surfaces. Tray is
 * the caller's job (the entry is already stored); this function emits at
 * most one toast and at most one OS notification, and never a surface the
 * resolution forbids. Safe to call once per accepted `notify`.
 */
export function dispatchSurfaces(notification: Notification, resolution: PolicyResolution): void {
  if (!resolution.accepted) return;
  const { heading, text, viewLabel } = translate(notification.i18nKey, notification.i18nParams);
  if (resolution.delivery.toast && markDelivered(notification.id, 'toast')) {
    try {
      // The toast carries a deep-link action only when the target resolves to
      // a real destination; unknown targets stay tray-only (safe degrade).
      const action: ToastAction | undefined = resolveNotificationTarget(notification.target)
        ? {
            label: viewLabel,
            notificationId: notification.id,
            target: notification.target,
          }
        : undefined;
      pushToastMessage(toastTypeFor(notification.severity), text, action);
    } catch {
      // A toast failure must never break the tray or the OS path.
    }
  }
  if (resolution.delivery.os && markDelivered(notification.id, 'os')) {
    void sendOsNotification(heading, String(notification.i18nParams?.detail ?? '')).catch(() => {});
  }
}

/** Resets module state between tests. */
export function resetNotificationSurfacesForTests(): void {
  deliveredSurfaces.clear();
  toastSink = null;
  focusCache = null;
}
