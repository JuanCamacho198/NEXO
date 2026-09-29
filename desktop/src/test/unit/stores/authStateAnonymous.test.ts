/**
 * Unit tests for the anonymous marker on authState (auth-gate fix).
 *
 * DA-3 boots an anonymous session for RLS when no real session can be
 * restored. That session carries a token, so `isSignedIn` stays true, but it
 * is not a login: `isAuthenticated` must be false and a real session landing
 * must clear the marker.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { authState, setLocalUser } from '$lib/shared/stores/AuthState.svelte';

const baseSession = {
  accessToken: 'at-1',
  refreshToken: 'rt-1',
  expiresAt: Date.now() + 3_600_000,
  userId: 'anon-1',
  email: null,
  displayName: null,
  photoUrl: null,
};

describe('authState — anonymous marker', () => {
  beforeEach(() => {
    authState.clearSupabaseSession();
    authState.clearLocalUser();
  });

  it('a fresh store is signed out and not authenticated', () => {
    expect(authState.isSignedIn).toBe(false);
    expect(authState.isAnonymous).toBe(false);
    expect(authState.isAuthenticated).toBe(false);
  });

  it('an anonymous session is signed in but NOT authenticated', () => {
    authState.setSupabaseSession({ ...baseSession, isAnonymous: true });

    expect(authState.isSignedIn).toBe(true);
    expect(authState.isAnonymous).toBe(true);
    expect(authState.isAuthenticated).toBe(false);
  });

  it('a real session is signed in and authenticated', () => {
    authState.setSupabaseSession({
      ...baseSession,
      userId: 'user-1',
      email: 'user@example.com',
      isAnonymous: false,
    });

    expect(authState.isSignedIn).toBe(true);
    expect(authState.isAnonymous).toBe(false);
    expect(authState.isAuthenticated).toBe(true);
  });

  it('a real session landing clears a previously-set anonymous marker', () => {
    authState.setSupabaseSession({ ...baseSession, isAnonymous: true });
    expect(authState.isAuthenticated).toBe(false);

    authState.setSupabaseSession({
      ...baseSession,
      userId: 'user-1',
      email: 'user@example.com',
      isAnonymous: false,
    });

    expect(authState.isAnonymous).toBe(false);
    expect(authState.isAuthenticated).toBe(true);
  });

  it('clearing the session resets the anonymous marker', () => {
    authState.setSupabaseSession({ ...baseSession, isAnonymous: true });
    authState.clearSupabaseSession();

    expect(authState.isSignedIn).toBe(false);
    expect(authState.isAnonymous).toBe(false);
    expect(authState.isAuthenticated).toBe(false);
  });

  it('switching to a local user clears the anonymous marker', () => {
    authState.setSupabaseSession({ ...baseSession, isAnonymous: true });
    setLocalUser({ name: 'Dev', email: null, avatarUrl: null, localOnly: true });

    expect(authState.isAnonymous).toBe(false);
    expect(authState.isLocalUser).toBe(true);
  });
});
