/**
 * Reactive auth state using Svelte 5 runes ($state).
 *
 * Wraps a Supabase Auth session internally but exposes the same
 * reactive interface as before for backward compatibility.
 *
 * Consumers read: `isSignedIn`, `isAnonymous`, `isAuthenticated`, `email`,
 * `userId`, `displayName`, `photoUrl`, `isLocalUser`, `accessToken`,
 * `refreshToken`, `expiresAt`.
 *
 * Local-user support:
 * Local users are first-class profiles that do NOT set `accessToken`.
 * `isSignedIn` therefore evaluates to `false` for local users.
 * Callers that need "has any profile" should check
 * `authState.isLocalUser || authState.isSignedIn`.
 *
 * Anonymous sessions (DA-3):
 * The boot fallback calls `signInAnonymously()` so RLS has an auth context
 * when no real session can be restored. That session carries a token, so
 * `isSignedIn` is `true`, but it is NOT a login. `isAnonymous` marks it and
 * `isAuthenticated` is the predicate every "this session means logged in"
 * decision must use (`isSignedIn && !isAnonymous`). Sync/RLS gates keep
 * using `isSignedIn` so the fallback session still works as before.
 */

import type { LocalUserProfile } from './authPersistence';

export type { LocalUserProfile };

/**
 * Internal Supabase session data shape (identity-only, login-drive-separation).
 *
 * Carries the Supabase identity session and nothing else. Drive
 * authorization lives in drive.json under DriveConnectService — this store
 * never holds provider or Drive refresh tokens.
 */
export interface SupabaseSessionData {
  accessToken: string | null;
  refreshToken: string | null;
  expiresAt: number | null;
  userId: string | null;
  email: string | null;
  displayName: string | null;
  photoUrl: string | null;
  /**
   * True when the session comes from the DA-3 anonymous fallback rather than
   * a real sign-in. Anonymous sessions exist only to give RLS an auth
   * context; they are never treated as a login.
   */
  isAnonymous: boolean;
}

let accessToken: string | null = $state(null);
let refreshToken: string | null = $state(null);
let expiresAt: number | null = $state(null);
let email: string | null = $state(null);
let displayName: string | null = $state(null);
let photoUrl: string | null = $state(null);
let userId: string | null = $state(null);
let localUser: LocalUserProfile | null = $state(null);
let anonymous = $state(false);

const isSignedIn = $derived(accessToken !== null);
const isAnonymous = $derived(anonymous);
const isAuthenticated = $derived(isSignedIn && !anonymous);
const isLocalUser = $derived(localUser !== null);
const isTokenExpired = $derived(
  expiresAt === null || Date.now() >= expiresAt - 60000, // 1-minute buffer
);

/**
 * Set a Supabase session. Clears any local user profile.
 */
export function setSupabaseSession(data: SupabaseSessionData): void {
  accessToken = data.accessToken;
  refreshToken = data.refreshToken;
  expiresAt = data.expiresAt;
  userId = data.userId;
  email = data.email;
  displayName = data.displayName;
  photoUrl = data.photoUrl;
  anonymous = data.isAnonymous;
  localUser = null; // Clear local user when supabase session is set
}

/**
 * Clear the Supabase session (sign out). Identity-only: the independent
 * Drive grant in drive.json is never touched here.
 */
export function clearSupabaseSession(): void {
  accessToken = null;
  refreshToken = null;
  expiresAt = null;
  email = null;
  displayName = null;
  photoUrl = null;
  userId = null;
  anonymous = false;
}

/**
 * Set the active local-user profile. Local users do NOT have an
 * `accessToken` — see file-level comment for the rationale.
 *
 * Callers are expected to persist the profile via `savePersistedAuth({ kind:
 * 'local', profile })` immediately after calling this.
 */
export function setLocalUser(profile: LocalUserProfile): void {
  localUser = profile;
  // Clear any supabase session when switching to local
  clearSupabaseSession();
}

export function clearLocalUser(): void {
  localUser = null;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getRefreshToken(): string | null {
  return refreshToken;
}

export function getExpiresAt(): number | null {
  return expiresAt;
}

export function needsRefresh(): boolean {
  return accessToken !== null && expiresAt !== null && Date.now() >= expiresAt - 60000;
}

export const authState = {
  get isSignedIn(): boolean {
    return isSignedIn;
  },
  get isAnonymous(): boolean {
    return isAnonymous;
  },
  get isAuthenticated(): boolean {
    return isAuthenticated;
  },
  get isTokenExpired(): boolean {
    return isTokenExpired;
  },
  get isLocalUser(): boolean {
    return isLocalUser;
  },
  get email(): string | null {
    return email;
  },
  get displayName(): string | null {
    return displayName;
  },
  get photoUrl(): string | null {
    return photoUrl;
  },
  get userId(): string | null {
    return userId;
  },
  get accessToken(): string | null {
    return accessToken;
  },
  get refreshToken(): string | null {
    return refreshToken;
  },
  get expiresAt(): number | null {
    return expiresAt;
  },
  get localUser(): LocalUserProfile | null {
    return localUser;
  },
  setSupabaseSession,
  clearSupabaseSession,
  setLocalUser,
  clearLocalUser,
  getAccessToken,
  getRefreshToken,
  getExpiresAt,
  needsRefresh,
};
