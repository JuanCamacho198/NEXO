/**
 * Unit tests for `authPersistence`.
 *
 * Mocks `@tauri-apps/plugin-fs` with controllable mocks so we can simulate:
 *  - cache hits (round-trip Google + local)
 *  - missing files
 *  - malformed JSON
 *  - atomic write (write to .tmp, then rename)
 *  - clear behavior (and the stale .tmp cleanup)
 *
 * The platform call wrappers (`load/save/clearPersistedAuth`) must never
 * throw — they swallow IO errors and return `null`/`void` so the calling
 * UI can degrade gracefully.
 */

import { describe, expect, it, vi, beforeEach } from 'vitest';
import {
  clearPersistedAuth,
  loadPersistedAuth,
  savePersistedAuth,
  type LocalUserProfile,
  type PersistedAuth,
} from '$lib/shared/stores/authPersistence';

const mockReadTextFile = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<string>>());
const mockWriteTextFile = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<void>>());
const mockRemove = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<void>>());
const mockRename = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<void>>());
const mockExists = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<boolean>>());
const mockInvoke = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<string>>());

vi.mock('@tauri-apps/plugin-fs', () => ({
  BaseDirectory: { AppData: 0 },
  exists: mockExists,
  readTextFile: mockReadTextFile,
  writeTextFile: mockWriteTextFile,
  remove: mockRemove,
  rename: mockRename,
}));

// Mock DPAPI Rust command: reversible test transform, never real crypto.
vi.mock('@tauri-apps/api/core', () => ({
  invoke: mockInvoke,
}));

const localProfile: LocalUserProfile = {
  name: 'Dev',
  email: 'dev@local',
  avatarUrl: null,
  localOnly: true,
};

const localAuth: PersistedAuth = { kind: 'local', profile: localProfile };

beforeEach(() => {
  mockReadTextFile.mockReset();
  mockWriteTextFile.mockReset();
  mockRemove.mockReset();
  mockRename.mockReset();
  mockExists.mockReset();
  mockInvoke.mockReset();
  mockInvoke.mockImplementation(((cmd: unknown, args: unknown) => {
    if (cmd === 'protectSecret') {
      // Opaque like real DPAPI output: base64, so "no plaintext on disk"
      // assertions are meaningful against this mock.
      const plaintext = (args as { plaintext: string }).plaintext;
      return Promise.resolve(`sealed:${Buffer.from(plaintext, 'utf8').toString('base64')}`);
    }
    if (cmd === 'unprotectSecret') {
      const ciphertext = (args as { ciphertext: string }).ciphertext;
      if (!ciphertext.startsWith('sealed:')) {
        return Promise.reject(new Error('bad ciphertext'));
      }
      return Promise.resolve(
        Buffer.from(ciphertext.slice('sealed:'.length), 'base64').toString('utf8'),
      );
    }
    return Promise.reject(new Error(`unexpected command ${String(cmd)}`));
  }) as (...args: unknown[]) => Promise<string>);
});

function readWrittenEnvelope(): { v: number; alg: string; data: string } {
  const written = mockWriteTextFile.mock.calls[0]?.[1] as string;
  return JSON.parse(written) as { v: number; alg: string; data: string };
}

function sealFixture(payload: string): string {
  return JSON.stringify({
    v: 1,
    alg: 'dpapi-current-user',
    data: `sealed:${Buffer.from(payload, 'utf8').toString('base64')}`,
  });
}

describe('loadPersistedAuth', () => {
  it('returns null when the cache file does not exist', async () => {
    mockExists.mockResolvedValue(false);
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
    expect(mockReadTextFile).not.toHaveBeenCalled();
  });

  it('returns the auth stored inside a sealed envelope', async () => {
    const sealedBody = sealFixture(JSON.stringify(localAuth));
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(sealedBody);
    const result = await loadPersistedAuth();
    expect(result).toEqual(localAuth);
    expect(mockInvoke).toHaveBeenCalledWith('unprotectSecret', {
      ciphertext: (JSON.parse(sealedBody) as { data: string }).data,
    });
  });

  it('migrates a legacy plaintext file to sealed storage on read (no backup kept)', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(JSON.stringify(localAuth));
    mockWriteTextFile.mockResolvedValue();
    mockRename.mockResolvedValue();

    const result = await loadPersistedAuth();

    expect(result).toEqual(localAuth);
    expect(mockInvoke).toHaveBeenCalledWith('protectSecret', {
      plaintext: JSON.stringify(localAuth),
    });
    const envelope = readWrittenEnvelope();
    expect(envelope.v).toBe(1);
    expect(envelope.alg).toBe('dpapi-current-user');
    expect(mockRename).toHaveBeenCalledWith('auth.json.tmp', 'auth.json', {
      oldPathBaseDir: 0,
      newPathBaseDir: 0,
    });
  });

  it('returns null when the cache contains a legacy Google record (discarded per MG-01)', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(
      JSON.stringify({
        kind: 'google',
        tokens: { accessToken: 'access-123', refreshToken: 'refresh-123' },
      }),
    );
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
  });

  it('returns a parsed local auth when the cache contains a valid local record', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(sealFixture(JSON.stringify(localAuth)));
    const result = await loadPersistedAuth();
    expect(result).toEqual(localAuth);
  });

  it('returns null when the JSON is malformed (and does not throw)', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue('{not valid json');
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
  });

  it('returns null when the JSON is structurally valid but the discriminator is unknown', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(JSON.stringify({ kind: 'magic-link', payload: {} }));
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
  });

  it('returns null when the Supabase record has a non-object session field', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(
      JSON.stringify({ kind: 'supabase', session: 'not-an-object' }),
    );
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
  });

  it('returns null when the local profile has an empty name', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(
      JSON.stringify({
        kind: 'local',
        profile: { name: '', email: null, avatarUrl: null, localOnly: true },
      }),
    );
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
  });

  it('returns null when the local profile is missing the localOnly literal', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockResolvedValue(
      JSON.stringify({
        kind: 'local',
        profile: { name: 'Dev', email: null, avatarUrl: null },
      }),
    );
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
  });

  it('returns null when the platform read throws (no crash propagates)', async () => {
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockRejectedValue(new Error('disk gone'));
    const result = await loadPersistedAuth();
    expect(result).toBeNull();
  });
});

describe('savePersistedAuth', () => {
  it('seals Supabase auth via DPAPI, then writes the envelope to tmp and renames (atomic write)', async () => {
    mockWriteTextFile.mockResolvedValue();
    mockRename.mockResolvedValue();
    const supabaseAuth: PersistedAuth = {
      kind: 'supabase',
      session: { access_token: 'test' },
    };

    await savePersistedAuth(supabaseAuth);

    expect(mockInvoke).toHaveBeenCalledWith('protectSecret', {
      plaintext: JSON.stringify(supabaseAuth),
    });
    expect(mockWriteTextFile).toHaveBeenCalledTimes(1);
    const envelope = readWrittenEnvelope();
    expect(envelope.v).toBe(1);
    expect(envelope.alg).toBe('dpapi-current-user');
    expect(mockWriteTextFile).toHaveBeenCalledWith('auth.json.tmp', expect.any(String), {
      baseDir: 0,
    });
    expect(mockRename).toHaveBeenCalledTimes(1);
    expect(mockRename).toHaveBeenCalledWith('auth.json.tmp', 'auth.json', {
      oldPathBaseDir: 0,
      newPathBaseDir: 0,
    });
  });

  it('writes local auth sealed to the tmp file, then renames over the real file', async () => {
    mockWriteTextFile.mockResolvedValue();
    mockRename.mockResolvedValue();

    await savePersistedAuth(localAuth);

    expect(mockInvoke).toHaveBeenCalledWith('protectSecret', {
      plaintext: JSON.stringify(localAuth),
    });
    const envelope = readWrittenEnvelope();
    expect(envelope.v).toBe(1);
    expect(mockWriteTextFile).toHaveBeenCalledWith('auth.json.tmp', expect.any(String), {
      baseDir: 0,
    });
    expect(mockRename).toHaveBeenCalledWith('auth.json.tmp', 'auth.json', {
      oldPathBaseDir: 0,
      newPathBaseDir: 0,
    });
  });

  it('does not rename if the seal fails (never writes plaintext)', async () => {
    mockInvoke.mockRejectedValueOnce(new Error('DPAPI unavailable'));

    await expect(savePersistedAuth(localAuth)).rejects.toThrow('DPAPI unavailable');
    expect(mockWriteTextFile).not.toHaveBeenCalled();
    expect(mockRename).not.toHaveBeenCalled();
  });

  it('does not rename if the write fails (no half-written real file)', async () => {
    mockWriteTextFile.mockRejectedValue(new Error('disk full'));
    const supabaseAuth: PersistedAuth = {
      kind: 'supabase',
      session: { access_token: 'test' },
    };

    await expect(savePersistedAuth(supabaseAuth)).rejects.toThrow('disk full');
    expect(mockRename).not.toHaveBeenCalled();
  });
});

describe('clearPersistedAuth', () => {
  it('removes the cache file when it exists', async () => {
    mockExists.mockImplementation(((path: unknown) => Promise.resolve(path === 'auth.json')) as (
      ...args: unknown[]
    ) => Promise<boolean>);
    mockRemove.mockResolvedValue();

    await clearPersistedAuth();

    expect(mockRemove).toHaveBeenCalledWith('auth.json', { baseDir: 0 });
  });

  it('does not call remove when the cache file is missing', async () => {
    mockExists.mockResolvedValue(false);

    await clearPersistedAuth();

    expect(mockRemove).not.toHaveBeenCalled();
  });

  it('removes a stale .tmp file even when the main file is missing', async () => {
    mockExists.mockImplementation(((path: unknown) =>
      Promise.resolve(path === 'auth.json.tmp')) as (...args: unknown[]) => Promise<boolean>);
    mockRemove.mockResolvedValue();

    await clearPersistedAuth();

    expect(mockRemove).toHaveBeenCalledWith('auth.json.tmp', { baseDir: 0 });
  });

  it('swallows platform errors and does not throw', async () => {
    mockExists.mockResolvedValue(true);
    mockRemove.mockRejectedValue(new Error('permission denied'));

    await expect(clearPersistedAuth()).resolves.toBeUndefined();
  });
});

describe('round-trip persistence', () => {
  it('returns the same Supabase auth that was saved', async () => {
    let storedPayload: string | null = null;
    const supabaseAuth: PersistedAuth = {
      kind: 'supabase',
      session: { access_token: 'test', refresh_token: 'test-refresh' },
    };

    mockWriteTextFile.mockImplementation(((_path: unknown, data: unknown) => {
      storedPayload = data as string;
      return Promise.resolve();
    }) as (...args: unknown[]) => Promise<void>);
    mockRename.mockResolvedValue();
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockImplementation((() => {
      if (storedPayload === null) {
        return Promise.reject(new Error('not yet written'));
      }
      return Promise.resolve(storedPayload);
    }) as (...args: unknown[]) => Promise<string>);

    await savePersistedAuth(supabaseAuth);
    const loaded = await loadPersistedAuth();
    expect(loaded).toEqual(supabaseAuth);
  });

  it('returns the same local auth that was saved', async () => {
    let storedPayload: string | null = null;

    mockWriteTextFile.mockImplementation(((_path: unknown, data: unknown) => {
      storedPayload = data as string;
      return Promise.resolve();
    }) as (...args: unknown[]) => Promise<void>);
    mockRename.mockResolvedValue();
    mockExists.mockResolvedValue(true);
    mockReadTextFile.mockImplementation((() => {
      if (storedPayload === null) {
        return Promise.reject(new Error('not yet written'));
      }
      return Promise.resolve(storedPayload);
    }) as (...args: unknown[]) => Promise<string>);

    await savePersistedAuth(localAuth);
    const loaded = await loadPersistedAuth();
    expect(loaded).toEqual(localAuth);
  });
});
