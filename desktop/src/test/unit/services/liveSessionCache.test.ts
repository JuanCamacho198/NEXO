/**
 * Unit tests for the live-session auth gate (WU1 — desktop-session-persistence).
 *
 * Exercises the REAL `$lib/services/supabase` module (only the Tauri fs plugin
 * is mocked) so `hasLiveSession()` is verified against the real `authState`
 * userId comparison, per design decision D1/D3 and requirement DA-1/DA-5.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Session } from '@supabase/supabase-js';

import { authState } from '$lib/shared/stores/AuthState.svelte';
import {
  setLiveSession,
  clearLiveSession,
  getLiveSession,
  hasLiveSession,
} from '$lib/services/supabase';

vi.mock('@tauri-apps/plugin-fs', () => ({
  BaseDirectory: { AppData: 0 },
  exists: vi.fn().mockResolvedValue(false),
  readTextFile: vi.fn(),
  writeTextFile: vi.fn(),
  remove: vi.fn(),
  rename: vi.fn(),
}));

function makeSession(userId: string): Session {
  return {
    access_token: `at-${userId}`,
    refresh_token: `rt-${userId}`,
    expires_at: 1_900_000_000,
    expires_in: 3600,
    token_type: 'bearer',
    user: { id: userId } as unknown as Session['user'],
  } as unknown as Session;
}

function hydrateAuthState(userId: string): void {
  authState.setSupabaseSession({
    accessToken: `at-${userId}`,
    refreshToken: `rt-${userId}`,
    expiresAt: Date.now() + 3_600_000,
    userId,
    email: null,
    displayName: null,
    photoUrl: null,
    isAnonymous: false,
  });
}

describe('liveSessionCache — hasLiveSession() auth gate', () => {
  beforeEach(() => {
    clearLiveSession();
    authState.clearSupabaseSession();
  });

  it('is false when no session is cached', () => {
    expect(hasLiveSession()).toBe(false);
  });

  it('is true when the cached session user matches the authState user', () => {
    hydrateAuthState('u1');
    setLiveSession(makeSession('u1'));

    expect(hasLiveSession()).toBe(true);
  });

  it('is false when the cached session user differs from the authState user', () => {
    hydrateAuthState('u2');
    setLiveSession(makeSession('u1'));

    expect(hasLiveSession()).toBe(false);
  });

  it('is false after session loss even when the cache is stale (authState cleared)', () => {
    hydrateAuthState('u1');
    setLiveSession(makeSession('u1'));

    authState.clearSupabaseSession(); // SIGNED_OUT clears authState

    expect(hasLiveSession()).toBe(false);
  });

  it('setLiveSession(null) clears the cache and fails the gate', () => {
    hydrateAuthState('u1');
    setLiveSession(makeSession('u1'));
    expect(hasLiveSession()).toBe(true);

    setLiveSession(null);

    expect(hasLiveSession()).toBe(false);
    expect(getLiveSession()).toBeNull();
  });

  it('getLiveSession returns the exact cached session', () => {
    const session = makeSession('u1');
    setLiveSession(session);

    expect(getLiveSession()).toBe(session);
  });
});
