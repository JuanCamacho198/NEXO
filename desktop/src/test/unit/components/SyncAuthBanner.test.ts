/**
 * Component test for SyncAuthBanner (WU2 — desktop-session-persistence).
 *
 * SR-3.1: a typed AUTH_REQUIRED surfaces as a visible banner with a working
 * "Sign in with Google" CTA and a dismiss action; no banner when no alert.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/svelte';
import { tick } from 'svelte';
import SyncAuthBanner from '$lib/shared/ui/feedback/SyncAuthBanner.svelte';
import { syncAlertStore, reportAuthError } from '$lib/shared/stores/syncAlert.svelte';
import { authState } from '$lib/shared/stores/AuthState.svelte';

const mockSignInWithGoogle = vi.fn<() => Promise<void>>();

vi.mock('$lib/shared/services/SupabaseAuthService', () => ({
  signInWithGoogle: () => mockSignInWithGoogle(),
}));

beforeEach(() => {
  vi.clearAllMocks();
  syncAlertStore.clear();
  authState.clearSupabaseSession();
  mockSignInWithGoogle.mockResolvedValue(undefined);
});

describe('SyncAuthBanner', () => {
  it('renders nothing when there is no alert', () => {
    render(SyncAuthBanner);

    expect(screen.queryByTestId('sync-auth-banner')).toBeNull();
  });

  it('renders the typed AUTH_REQUIRED message with a re-auth CTA', async () => {
    reportAuthError(
      Object.assign(new Error('Google Drive access expired. Please sign in with Google again.'), {
        code: 'AUTH_REQUIRED',
      }),
    );
    render(SyncAuthBanner);

    expect(screen.getByTestId('sync-auth-banner')).toBeTruthy();
    expect(
      screen.getByText('Google Drive access expired. Please sign in with Google again.'),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign in with Google' })).toBeTruthy();
  });

  it('dismiss clears the store and unmounts the banner', async () => {
    reportAuthError(Object.assign(new Error('Drive access expired'), { code: 'AUTH_REQUIRED' }));
    render(SyncAuthBanner);
    expect(screen.getByTestId('sync-auth-banner')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Dismiss auth alert' }));
    await tick();

    expect(syncAlertStore.current).toBeNull();
    expect(screen.queryByTestId('sync-auth-banner')).toBeNull();
  });

  it('the CTA starts a Google sign-in (SR-3.1 working re-auth path)', async () => {
    reportAuthError(Object.assign(new Error('Drive access expired'), { code: 'AUTH_EXPIRED' }));
    render(SyncAuthBanner);

    await fireEvent.click(screen.getByRole('button', { name: 'Sign in with Google' }));

    expect(mockSignInWithGoogle).toHaveBeenCalledTimes(1);
  });

  it('clears the banner after a successful re-auth (D7: SIGNED_IN/TOKEN_REFRESHED → isSignedIn true)', async () => {
    reportAuthError(Object.assign(new Error('Drive access expired'), { code: 'AUTH_REQUIRED' }));
    render(SyncAuthBanner);
    expect(screen.getByTestId('sync-auth-banner')).toBeTruthy();

    // Simulate the OAuth callback hydrating a fresh session.
    authState.setSupabaseSession({
      accessToken: 'at-new',
      refreshToken: 'rt',
      expiresAt: Date.now() + 3_600_000,
      userId: 'u1',
      email: null,
      displayName: null,
      photoUrl: null,
      isAnonymous: false,
    });
    await tick();

    expect(syncAlertStore.current).toBeNull();
    expect(screen.queryByTestId('sync-auth-banner')).toBeNull();
  });

  it('keeps the banner when AUTH_REQUIRED fires while still signed in (no token change → no self-clear)', async () => {
    authState.setSupabaseSession({
      accessToken: 'at-existing',
      refreshToken: 'rt',
      expiresAt: Date.now() + 3_600_000,
      userId: 'u1',
      email: null,
      displayName: null,
      photoUrl: null,
      isAnonymous: false,
    });
    await tick();
    render(SyncAuthBanner);
    await tick();

    // Drive token expired mid-session: banner set, accessToken unchanged.
    reportAuthError(Object.assign(new Error('Drive access expired'), { code: 'AUTH_REQUIRED' }));
    await tick();

    expect(syncAlertStore.current).not.toBeNull();
    expect(screen.getByTestId('sync-auth-banner')).toBeTruthy();
  });
});
