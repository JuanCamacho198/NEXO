export const REMIND_LATER_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000;

export type UpdateSuppression = {
  dismissedVersion: string;
  dismissedAtEpochMs: number;
};

export type SuppressionStorage = {
  read(): string | null;
  write(value: string): void;
  remove(): void;
};

const STORAGE_KEY = 'nextpage.update.suppression';

const createLocalStorageBacking = (): SuppressionStorage => ({
  read(): string | null {
    try {
      return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null;
    } catch {
      return null;
    }
  },
  write(value: string): void {
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, value);
    } catch {
      // Suppression is best-effort; a full/blocked store must never break checks.
    }
  },
  remove(): void {
    try {
      globalThis.localStorage?.removeItem(STORAGE_KEY);
    } catch {
      // See write(): best-effort only.
    }
  },
});

export const defaultSuppressionStorage = (): SuppressionStorage => createLocalStorageBacking();

const parseRecord = (raw: string | null): UpdateSuppression | null => {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<UpdateSuppression>;
    if (
      typeof parsed.dismissedVersion !== 'string' ||
      parsed.dismissedVersion.length === 0 ||
      typeof parsed.dismissedAtEpochMs !== 'number' ||
      !Number.isFinite(parsed.dismissedAtEpochMs)
    ) {
      return null;
    }
    return {
      dismissedVersion: parsed.dismissedVersion,
      dismissedAtEpochMs: parsed.dismissedAtEpochMs,
    };
  } catch {
    return null;
  }
};

export function loadSuppression(storage: SuppressionStorage): UpdateSuppression | null {
  return parseRecord(storage.read());
}

export function recordRemindLater(
  storage: SuppressionStorage,
  version: string,
  nowEpochMs: number,
): UpdateSuppression {
  const record: UpdateSuppression = { dismissedVersion: version, dismissedAtEpochMs: nowEpochMs };
  storage.write(JSON.stringify(record));
  return record;
}

export function clearSuppression(storage: SuppressionStorage): void {
  storage.remove();
}

export function isSuppressedFor(
  record: UpdateSuppression | null,
  feedVersion: string,
  nowEpochMs: number,
): boolean {
  if (!record) return false;
  if (record.dismissedVersion !== feedVersion) return false;
  return nowEpochMs - record.dismissedAtEpochMs < REMIND_LATER_INTERVAL_MS;
}
