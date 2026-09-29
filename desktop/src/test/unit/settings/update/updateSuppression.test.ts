import { describe, expect, it } from 'vitest';
import {
  REMIND_LATER_INTERVAL_MS,
  clearSuppression,
  isSuppressedFor,
  loadSuppression,
  recordRemindLater,
  type SuppressionStorage,
} from '$lib/features/settings/update/updateSuppression';

const createMemoryStorage = (seed: string | null = null): SuppressionStorage => {
  let value = seed;
  return {
    read: () => value,
    write: (next: string) => {
      value = next;
    },
    remove: () => {
      value = null;
    },
  };
};

describe('desktop update suppression', () => {
  it('reserves the shared remind-later interval constant', () => {
    expect(REMIND_LATER_INTERVAL_MS).toBe(7 * 24 * 60 * 60 * 1000);
  });

  it('suppresses only the dismissed version', () => {
    const storage = createMemoryStorage();
    recordRemindLater(storage, '0.4.0', 1_000_000);
    const record = loadSuppression(storage);

    expect(isSuppressedFor(record, '0.4.0', 1_000_000 + 60_000)).toBe(true);
    expect(isSuppressedFor(record, '0.5.0', 1_000_000 + 60_000)).toBe(false);
  });

  it('expires suppression after the interval', () => {
    const storage = createMemoryStorage();
    recordRemindLater(storage, '0.4.0', 1_000_000);
    const record = loadSuppression(storage);

    expect(isSuppressedFor(record, '0.4.0', 1_000_000 + REMIND_LATER_INTERVAL_MS - 1)).toBe(true);
    expect(isSuppressedFor(record, '0.4.0', 1_000_000 + REMIND_LATER_INTERVAL_MS + 1)).toBe(false);
  });

  it('survives restarts through serialized storage', () => {
    const storage = createMemoryStorage();
    recordRemindLater(storage, '0.4.0', 2_000_000);

    const raw = storage.read();
    expect(raw).toContain('0.4.0');
    const reloaded = createMemoryStorage(raw);
    expect(isSuppressedFor(loadSuppression(reloaded), '0.4.0', 2_000_000 + 60_000)).toBe(true);
  });

  it('treats missing or corrupt records as no suppression', () => {
    expect(loadSuppression(createMemoryStorage(null))).toBeNull();
    expect(loadSuppression(createMemoryStorage('not-json'))).toBeNull();
    expect(loadSuppression(createMemoryStorage('{"dismissedVersion":42}'))).toBeNull();
    expect(isSuppressedFor(null, '0.4.0', Date.now())).toBe(false);
  });

  it('clears the record on demand', () => {
    const storage = createMemoryStorage();
    recordRemindLater(storage, '0.4.0', 3_000_000);
    clearSuppression(storage);
    expect(loadSuppression(storage)).toBeNull();
  });
});
