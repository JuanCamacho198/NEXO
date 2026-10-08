/**
 * notificationPreferences — per-category switches and quiet hours (NOTIF-05).
 *
 * Why this shape: the delivery policy already resolves a closed `Category`
 * (`system` | `nudge`), so one switch per category is exactly the granularity
 * the policy can enforce — finer per-source switches would add UI without a
 * policy effect. Quiet hours reuse rule-3 semantics (tray records, nothing
 * sounds) on a user-configured schedule instead of the reading state.
 *
 * Why SettingsPort: preferences are generic app settings, so they ride the
 * existing `app_settings` key-value storage (`notifications.preferences`,
 * JSON) instead of a new table — no Rust migration, no new commands. The
 * `NotificationPort` stays what it is: the durable tray history.
 */
import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import { TauriSettingsAdapter } from '$lib/shared/ports/adapters/tauri/TauriSettingsAdapter';
import type { AppSettingDto } from '$lib/shared/types';

/** Storage key inside the generic `app_settings` table. */
export const NOTIFICATION_PREFERENCES_SETTING_KEY = 'notifications.preferences' as const;

export interface QuietHoursSchedule {
  enabled: boolean;
  /** Local `HH:MM` (24h). */
  start: string;
  /** Local `HH:MM` (24h). */
  end: string;
}

export interface NotificationPreferences {
  /** Routine app events (imports, sync, addons, updates). */
  system: boolean;
  /** Engagement nudges (streak, goal). Switchable off without losing the channel. */
  nudge: boolean;
  quietHours: QuietHoursSchedule;
}

export const DEFAULT_NOTIFICATION_PREFERENCES: NotificationPreferences = {
  system: true,
  nudge: true,
  quietHours: { enabled: false, start: '22:00', end: '07:00' },
};

/** Accepts anything persisted and returns a valid shape (unknown → defaults). */
export function parseNotificationPreferences(raw: unknown): NotificationPreferences {
  const defaults = DEFAULT_NOTIFICATION_PREFERENCES;
  if (typeof raw !== 'object' || raw === null) {
    return structuredCloneSafe(defaults);
  }
  const record = raw as Record<string, unknown>;
  const quiet =
    typeof record.quietHours === 'object' && record.quietHours !== null
      ? (record.quietHours as Record<string, unknown>)
      : {};
  return {
    system: typeof record.system === 'boolean' ? record.system : defaults.system,
    nudge: typeof record.nudge === 'boolean' ? record.nudge : defaults.nudge,
    quietHours: {
      enabled: typeof quiet.enabled === 'boolean' ? quiet.enabled : defaults.quietHours.enabled,
      start:
        typeof quiet.start === 'string' && quiet.start.length > 0
          ? quiet.start
          : defaults.quietHours.start,
      end:
        typeof quiet.end === 'string' && quiet.end.length > 0 ? quiet.end : defaults.quietHours.end,
    },
  };
}

function structuredCloneSafe(prefs: NotificationPreferences): NotificationPreferences {
  return {
    system: prefs.system,
    nudge: prefs.nudge,
    quietHours: { ...prefs.quietHours },
  };
}

/** `HH:MM` → minutes since local midnight, or null when malformed. */
function parseClockTime(value: string): number | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}

/**
 * True when quiet hours are enabled and `nowMs` falls inside the schedule.
 * Overnight ranges (start > end, e.g. 22:00–07:00) wrap past midnight. A
 * degenerate range (start === end) or malformed input never silences.
 */
export function isQuietHoursActive(prefs: NotificationPreferences, nowMs: number): boolean {
  const schedule = prefs?.quietHours;
  if (!schedule?.enabled) return false;
  const start = parseClockTime(schedule.start);
  const end = parseClockTime(schedule.end);
  if (start === null || end === null || start === end) return false;
  const at = new Date(nowMs);
  const current = at.getHours() * 60 + at.getMinutes();
  return start < end ? current >= start && current < end : current >= start || current < end;
}

// ─── Port seam + synchronous cache ──────────────────────────────────────

let settingsPort: SettingsPort = new TauriSettingsAdapter();

/** Replaces the settings adapter (tests, non-Tauri hosts). */
export function setNotificationPreferencesPort(next: SettingsPort): void {
  settingsPort = next;
}

/**
 * Synchronous snapshot for the `notify` emit path, which must stay
 * synchronous. Warmed by `loadNotificationPreferences()` at startup; before
 * that (and in unit tests) it is the default — everything enabled.
 */
let cached: NotificationPreferences = structuredCloneSafe(DEFAULT_NOTIFICATION_PREFERENCES);

/** Current preferences without I/O (a copy; mutate via `update...`). */
export function getCachedNotificationPreferences(): NotificationPreferences {
  return structuredCloneSafe(cached);
}

function findPreferencesValue(settings: AppSettingDto[]): unknown {
  const entry = settings.find((item) => item.key === NOTIFICATION_PREFERENCES_SETTING_KEY);
  if (!entry) return null;
  try {
    return JSON.parse(entry.valueJson) as unknown;
  } catch {
    return null;
  }
}

/**
 * Loads the persisted preferences into the cache. Unknown storage content
 * falls back to the defaults; a storage failure keeps the current cache.
 */
export async function loadNotificationPreferences(): Promise<NotificationPreferences> {
  try {
    const settings = await settingsPort.getAppSettings();
    cached = parseNotificationPreferences(findPreferencesValue(settings));
  } catch {
    // Keep the current cache: preferences are a delivery gate, not history.
  }
  return getCachedNotificationPreferences();
}

export type NotificationPreferencesPatch = {
  system?: boolean;
  nudge?: boolean;
  quietHours?: Partial<QuietHoursSchedule>;
};

/**
 * Merges a patch, updates the cache immediately and persists it. The cache
 * update is synchronous so the next `notify` already honors the new value;
 * a persistence failure keeps the session value and propagates to the caller.
 */
export async function updateNotificationPreferences(
  patch: NotificationPreferencesPatch,
): Promise<NotificationPreferences> {
  const next: NotificationPreferences = {
    system: patch.system ?? cached.system,
    nudge: patch.nudge ?? cached.nudge,
    quietHours: { ...cached.quietHours, ...(patch.quietHours ?? {}) },
  };
  cached = structuredCloneSafe(next);
  await settingsPort.upsertAppSettings([
    {
      key: NOTIFICATION_PREFERENCES_SETTING_KEY,
      valueJson: JSON.stringify(next),
      updatedAt: new Date().toISOString(),
    },
  ]);
  return getCachedNotificationPreferences();
}

/** Resets the port seam and cache between tests. */
export function resetNotificationPreferencesForTests(): void {
  settingsPort = new TauriSettingsAdapter();
  cached = structuredCloneSafe(DEFAULT_NOTIFICATION_PREFERENCES);
}
