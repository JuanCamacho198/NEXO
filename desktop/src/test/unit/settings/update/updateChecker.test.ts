import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import {
  checkForUpdates,
  compareDesktopVersions,
  defaultFetchFeed,
  parseDesktopFeed,
  performPluginUpdateNow,
  resetStartupUpdateCheckForTests,
  runStartupUpdateCheck,
  type UpdateCheckDeps,
  type UpdateCheckState,
} from '$lib/features/settings/update/updateChecker';
import {
  recordRemindLater,
  type SuppressionStorage,
} from '$lib/features/settings/update/updateSuppression';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

const fixturePath = resolve(process.cwd(), '../mocks/update-feed/desktop-latest.json');
const fixtureText = readFileSync(fixturePath, 'utf-8');
const fixture = JSON.parse(fixtureText) as Record<string, unknown>;

const FEED_URL = 'https://updates.example.com/latest-linux.json';

const createMemoryStorage = (): SuppressionStorage => {
  let value: string | null = null;
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

const baseDeps = (overrides: Partial<UpdateCheckDeps> = {}): UpdateCheckDeps => ({
  feedUrl: FEED_URL,
  getInstalledVersion: async () => '0.3.0',
  fetchFeed: async () => structuredClone(fixture),
  isOnline: () => true,
  storage: createMemoryStorage(),
  nowEpochMs: () => 1_000_000,
  ...overrides,
});

const availableOf = (state: UpdateCheckState | null) => {
  if (state?.status !== 'available') throw new Error(`expected available, got ${state?.status}`);
  return state;
};

describe('desktop feed parsing against the mock fixture', () => {
  it('parses the real mock fixture and finds the 0.3.1 candidate', () => {
    const parsed = parseDesktopFeed(fixture);
    expect(parsed.kind).toBe('feed');
    if (parsed.kind !== 'feed') return;
    expect(parsed.feed.version).toBe('0.3.1');
    expect(parsed.feed.notes.length).toBeGreaterThan(0);
    expect(parsed.feed.pubDate.length).toBeGreaterThan(0);
    expect(Object.keys(parsed.feed.platforms).length).toBeGreaterThan(0);
    for (const entry of Object.values(parsed.feed.platforms)) {
      expect(entry.url).toMatch(/^https:\/\//);
      expect(entry.signature.length).toBeGreaterThan(0);
    }
  });

  it('rejects a platform entry without a signature', () => {
    const parsed = parseDesktopFeed({
      version: '0.4.0',
      notes: 'notes',
      pub_date: '2026-09-28T12:00:00Z',
      platforms: { 'windows-x86_64': { url: 'https://example.com/app.exe' } },
    });
    expect(parsed).toEqual({ kind: 'malformed' });
  });

  it('ignores unknown channels without offering an update', () => {
    const parsed = parseDesktopFeed({ ...fixture, channel: 'beta-future' });
    expect(parsed).toEqual({ kind: 'ignored' });
  });

  it('compares versions: newer offers, equal or older offers nothing', () => {
    expect(compareDesktopVersions('0.4.0', '0.3.0')).toBeGreaterThan(0);
    expect(compareDesktopVersions('0.3.0', '0.3.0')).toBe(0);
    expect(compareDesktopVersions('0.2.9', '0.3.0')).toBeLessThan(0);
    expect(compareDesktopVersions('0.10.0', '0.9.9')).toBeGreaterThan(0);
  });
});

describe('desktop check state mapping', () => {
  it('reports available for the newer mock feed', async () => {
    const state = await checkForUpdates(baseDeps(), { manual: true });
    const available = availableOf(state);
    expect(available.feedVersion).toBe('0.3.1');
    expect(available.installedVersion).toBe('0.3.0');
    expect(available.notes.length).toBeGreaterThan(0);
  });

  it('reports available when installed is older than the feed', async () => {
    const state = await checkForUpdates(baseDeps({ getInstalledVersion: async () => '0.2.9' }), {
      manual: true,
    });
    expect(state).toMatchObject({ status: 'available', installedVersion: '0.2.9' });
  });

  it('falls back to version-display-only when the feed URL is disabled', async () => {
    const fetchFeed = vi.fn();
    const manual = await checkForUpdates(baseDeps({ feedUrl: '  ', fetchFeed }), {
      manual: true,
    });
    expect(manual).toEqual({ status: 'disabled' });
    expect(fetchFeed).not.toHaveBeenCalled();
    const startup = await checkForUpdates(baseDeps({ feedUrl: '', fetchFeed }), {
      manual: false,
    });
    expect(startup).toBeNull();
  });

  it('reports offline on manual checks but stays silent on startup', async () => {
    const manual = await checkForUpdates(baseDeps({ isOnline: () => false }), {
      manual: true,
    });
    expect(manual).toEqual({ status: 'error', kind: 'OFFLINE' });
    const startup = await checkForUpdates(baseDeps({ isOnline: () => false }), {
      manual: false,
    });
    expect(startup).toBeNull();
  });

  it('never claims up-to-date on fetch or schema errors', async () => {
    const failingFetch = async () => {
      throw new Error('boom');
    };
    expect(await checkForUpdates(baseDeps({ fetchFeed: failingFetch }), { manual: true })).toEqual({
      status: 'error',
      kind: 'UNREACHABLE',
    });
    expect(
      await checkForUpdates(baseDeps({ fetchFeed: async () => ({ bogus: true }) }), {
        manual: true,
      }),
    ).toEqual({ status: 'error', kind: 'MALFORMED' });
  });

  it('manual checks bypass remind-later suppression, startup honors it', async () => {
    const storage = createMemoryStorage();
    recordRemindLater(storage, '0.3.1', 1_000_000);
    const deps = () => baseDeps({ storage });

    const manual = await checkForUpdates(deps(), { manual: true });
    expect(manual?.status).toBe('available');

    resetStartupUpdateCheckForTests();
    expect(await runStartupUpdateCheck(deps())).toBeNull();

    const fresh = createMemoryStorage();
    recordRemindLater(fresh, '0.3.0', 1_000_000);
    resetStartupUpdateCheckForTests();
    const newer = await runStartupUpdateCheck(baseDeps({ storage: fresh }));
    expect(newer?.status).toBe('available');
  });

  it('runs the startup check at most once per launch', async () => {
    resetStartupUpdateCheckForTests();
    const deps = baseDeps();
    expect((await runStartupUpdateCheck(deps))?.status).toBe('available');
    expect(await runStartupUpdateCheck(deps)).toBeNull();
  });
});

describe('desktop mock-feed fetch over MSW', () => {
  const server = setupServer(
    http.get(FEED_URL, () => HttpResponse.json(fixture)),
    http.get('https://updates.example.com/broken.json', () => HttpResponse.json({ bogus: true })),
  );

  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  it('drives the available flow through the real fetch path', async () => {
    const state = await checkForUpdates(baseDeps({ fetchFeed: (url) => defaultFetchFeed(url) }), {
      manual: true,
    });
    expect(availableOf(state).feedVersion).toBe('0.3.1');
  });

  it('maps a schema-invalid body to malformed', async () => {
    const state = await checkForUpdates(
      baseDeps({
        feedUrl: 'https://updates.example.com/broken.json',
        fetchFeed: (url) => defaultFetchFeed(url),
      }),
      { manual: true },
    );
    expect(state).toEqual({ status: 'error', kind: 'MALFORMED' });
  });
});

describe('desktop plugin update-now orchestration', () => {
  it('downloads, installs, and reports installed for the relaunch step', async () => {
    const downloadAndInstall = vi.fn(async () => undefined);
    const outcome = await performPluginUpdateNow(
      {
        checkForPluginUpdate: async () => ({ version: '0.4.0', notes: 'notes' }),
        downloadAndInstall,
        relaunchApp: async () => undefined,
      },
      () => undefined,
    );
    expect(outcome).toBe('installed');
    expect(downloadAndInstall).toHaveBeenCalledTimes(1);
  });

  it('reports noUpdateAnymore when the feed no longer advertises an update', async () => {
    const downloadAndInstall = vi.fn(async () => undefined);
    const outcome = await performPluginUpdateNow(
      {
        checkForPluginUpdate: async () => null,
        downloadAndInstall,
        relaunchApp: async () => undefined,
      },
      () => undefined,
    );
    expect(outcome).toBe('noUpdateAnymore');
    expect(downloadAndInstall).not.toHaveBeenCalled();
  });
});

describe('desktop update EN/ES parity spot-check', () => {
  const keys = [
    'update.check',
    'update.checking',
    'update.upToDate',
    'update.availableTitle',
    'update.availableBody',
    'update.notes',
    'update.now',
    'update.later',
    'update.relaunchConfirm',
    'update.errorUnreachable',
    'update.errorMalformed',
    'update.errorOffline',
  ] as const;

  it('exposes every update key in both locales with real translations', () => {
    for (const key of keys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
      expect(messagesEs[key]).not.toBe(messagesEn[key]);
    }
    expect(messagesEn['update.relaunchConfirm']).toContain('Relaunch');
  });
});
