/**
 * Unit tests for the atomic Tauri storage adapter (`tauriStorageAdapter`) in
 * `$lib/services/supabase` — WU3 (desktop-session-persistence).
 *
 * Exercises the REAL `$lib/services/supabase` module with an in-memory fake for
 * `@tauri-apps/plugin-fs` (mirroring the authPersistence.test.ts pattern) so we
 * can simulate:
 *  - atomic write (write .tmp, then rename over main)
 *  - crash-sim: orphaned .tmp beside a valid main — next write cleans it and
 *    the main file stays valid JSON (DA-4.1)
 *  - corrupt main file → getItem returns null + typed SUPABASE_AUTH_CACHE_READ_FAILED
 *    event, no crash (DA-4.2)
 *  - corrupt read never wipes the live in-memory session (liveSessionCache
 *    separation, DA-4.2)
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { logger } from '$lib/shared/logger/Logger';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import {
  tauriStorageAdapter,
  setLiveSession,
  clearLiveSession,
  hasLiveSession,
} from '$lib/services/supabase';

const SESSION_FILE = 'supabase-session.json';
const TMP_FILE = 'supabase-session.json.tmp';

const mockExists = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<boolean>>());
const mockReadTextFile = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<string>>());
const mockWriteTextFile = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<void>>());
const mockRename = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<void>>());
const mockRemove = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<void>>());

vi.mock('@tauri-apps/plugin-fs', () => ({
  BaseDirectory: { AppData: 0 },
  exists: mockExists,
  readTextFile: mockReadTextFile,
  writeTextFile: mockWriteTextFile,
  rename: mockRename,
  remove: mockRemove,
}));

/** In-memory file map used to simulate the adapter's filesystem. */
let files: Map<string, string>;

beforeEach(() => {
  files = new Map<string, string>();
  mockExists.mockImplementation((path: unknown) => Promise.resolve(files.has(String(path))));
  mockReadTextFile.mockImplementation((path: unknown) => {
    const key = String(path);
    if (!files.has(key)) return Promise.reject(new Error(`ENOENT: ${key}`));
    return Promise.resolve(files.get(key) as string);
  });
  mockWriteTextFile.mockImplementation((path: unknown, content: unknown) => {
    files.set(String(path), String(content));
    return Promise.resolve();
  });
  mockRename.mockImplementation((from: unknown, to: unknown) => {
    const fromKey = String(from);
    if (!files.has(fromKey)) return Promise.reject(new Error(`ENOENT: ${fromKey}`));
    const content = files.get(fromKey) as string;
    files.delete(fromKey);
    files.set(String(to), content);
    return Promise.resolve();
  });
  mockRemove.mockImplementation((path: unknown) => {
    files.delete(String(path));
    return Promise.resolve();
  });
});

describe('tauriStorageAdapter — atomic writes (DA-4.1)', () => {
  it('setItem writes to the tmp file, then renames over the main file', async () => {
    await tauriStorageAdapter.setItem('supabase.auth.token', '{"access_token":"t1"}');

    expect(mockWriteTextFile).toHaveBeenCalledTimes(1);
    expect(mockWriteTextFile).toHaveBeenCalledWith(TMP_FILE, expect.any(String), {
      baseDir: 0,
    });
    expect(mockRename).toHaveBeenCalledTimes(1);
    expect(mockRename).toHaveBeenCalledWith(TMP_FILE, SESSION_FILE, {
      oldPathBaseDir: 0,
      newPathBaseDir: 0,
    });
    // tmp never lingers after a successful write
    expect(files.has(TMP_FILE)).toBe(false);
    expect(files.has(SESSION_FILE)).toBe(true);
  });

  it('round-trips a value written via setItem and read via getItem', async () => {
    await tauriStorageAdapter.setItem('supabase.auth.token', '{"access_token":"t1"}');

    const value = await tauriStorageAdapter.getItem('supabase.auth.token');

    expect(value).toBe('{"access_token":"t1"}');
    expect(mockReadTextFile).toHaveBeenCalledWith(SESSION_FILE, { baseDir: 0 });
  });

  it('merges multiple keys into the same map and preserves unrelated keys', async () => {
    await tauriStorageAdapter.setItem('supabase.auth.token', '{"access_token":"t1"}');
    await tauriStorageAdapter.setItem('supabase.auth.refresh_token', 'rt-1');

    const token = await tauriStorageAdapter.getItem('supabase.auth.token');
    const refresh = await tauriStorageAdapter.getItem('supabase.auth.refresh_token');

    expect(token).toBe('{"access_token":"t1"}');
    expect(refresh).toBe('rt-1');
  });

  it('does not throw when the underlying write fails (best-effort persist)', async () => {
    mockWriteTextFile.mockRejectedValueOnce(new Error('disk full'));

    await expect(tauriStorageAdapter.setItem('supabase.auth.token', 'x')).resolves.toBeUndefined();
  });

  it('reports a typed, redacted SUPABASE_SESSION_PERSIST_FAILED event when the write fails', async () => {
    const warnSpy = vi.spyOn(logger, 'warn').mockImplementation(() => {});
    mockWriteTextFile.mockRejectedValueOnce(new Error('disk full token=secret123'));

    await tauriStorageAdapter.setItem('supabase.auth.token', 'x');

    expect(warnSpy).toHaveBeenCalledTimes(1);
    const event = warnSpy.mock.calls[0]?.[0] as {
      code?: string;
      context?: { reason?: string };
    };
    expect(event.code).toBe('SUPABASE_SESSION_PERSIST_FAILED');
    expect(event.context?.reason).not.toContain('secret123');
    warnSpy.mockRestore();
  });
});

describe('tauriStorageAdapter — crash-sim: orphaned tmp cleanup (DA-4.1)', () => {
  it('cleans an orphaned tmp and keeps the main file valid when a crash left both', async () => {
    // Crash after tmp write, before rename: main holds old valid JSON, tmp holds partial.
    files.set(SESSION_FILE, JSON.stringify({ 'supabase.auth.token': 'old-valid' }));
    files.set(TMP_FILE, '{"supabase.auth.token":"partial-new"');

    await tauriStorageAdapter.setItem('supabase.auth.token', '{"access_token":"new"}');

    expect(mockRemove).toHaveBeenCalledWith(TMP_FILE, { baseDir: 0 });
    expect(files.has(TMP_FILE)).toBe(false);
    const mainRaw = files.get(SESSION_FILE);
    expect(mainRaw).toBeDefined();
    // Main file must be valid JSON after the write (never truncated/corrupt).
    const main = JSON.parse(mainRaw as string) as Record<string, string>;
    expect(main['supabase.auth.token']).toBe('{"access_token":"new"}');
  });

  it('cleans an orphaned tmp even when the main file is missing (crash before rename on first write)', async () => {
    files.set(TMP_FILE, '{"supabase.auth.token":"partial"');

    await tauriStorageAdapter.setItem('supabase.auth.token', '{"access_token":"fresh"}');

    expect(mockRemove).toHaveBeenCalledWith(TMP_FILE, { baseDir: 0 });
    expect(files.has(TMP_FILE)).toBe(false);
    const mainRaw = files.get(SESSION_FILE);
    expect(mainRaw).toBeDefined();
    expect(() => JSON.parse(mainRaw as string)).not.toThrow();
  });

  it('removeItem also cleans an orphaned tmp and rewrites atomically', async () => {
    files.set(SESSION_FILE, JSON.stringify({ 'supabase.auth.token': 't1', keep: 'k' }));
    files.set(TMP_FILE, 'garbage');

    await tauriStorageAdapter.removeItem('supabase.auth.token');

    expect(files.has(TMP_FILE)).toBe(false);
    const main = JSON.parse(files.get(SESSION_FILE) as string) as Record<string, string>;
    expect(main['supabase.auth.token']).toBeUndefined();
    expect(main.keep).toBe('k');
    expect(mockWriteTextFile).toHaveBeenCalledWith(TMP_FILE, expect.any(String), {
      baseDir: 0,
    });
  });
});

describe('tauriStorageAdapter — corrupt-file resilience (DA-4.2)', () => {
  it('returns null and logs a typed SUPABASE_AUTH_CACHE_READ_FAILED event on corrupt JSON', async () => {
    const warnSpy = vi.spyOn(logger, 'warn').mockImplementation(() => {});
    files.set(SESSION_FILE, '{not valid json');

    const value = await tauriStorageAdapter.getItem('supabase.auth.token');

    expect(value).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    const event = warnSpy.mock.calls[0]?.[0] as { code?: string; recoverable?: boolean };
    expect(event.code).toBe('SUPABASE_AUTH_CACHE_READ_FAILED');
    expect(event.recoverable).toBe(true);
    warnSpy.mockRestore();
  });

  it('returns null without a typed event when the file is simply missing', async () => {
    const warnSpy = vi.spyOn(logger, 'warn').mockImplementation(() => {});

    const value = await tauriStorageAdapter.getItem('supabase.auth.token');

    expect(value).toBeNull();
    expect(warnSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('does not wipe the live in-memory session when the file is corrupt', async () => {
    const warnSpy = vi.spyOn(logger, 'warn').mockImplementation(() => {});
    authState.setSupabaseSession({
      accessToken: 'at-1',
      refreshToken: 'rt-1',
      expiresAt: Date.now() + 3_600_000,
      userId: 'u1',
      email: null,
      displayName: null,
      photoUrl: null,
      isAnonymous: false,
    });
    setLiveSession({ user: { id: 'u1' } } as never);
    files.set(SESSION_FILE, '{not valid json');

    const value = await tauriStorageAdapter.getItem('supabase.auth.token');

    expect(value).toBeNull();
    // The corrupt-file read failure must NOT wipe the live session mirror.
    expect(hasLiveSession()).toBe(true);

    clearLiveSession();
    authState.clearSupabaseSession();
    warnSpy.mockRestore();
  });
});
