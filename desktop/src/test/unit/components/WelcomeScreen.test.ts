import { render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const t = (key: string) => key;

// vi.mock is hoisted, so vi.hoisted must define the mock data first
const { mockAppState } = vi.hoisted(() => {
  const mas = {
    t: (key: string) => key,
    navigateToHome: vi.fn(),
  };
  return { mockAppState: mas };
});

vi.mock('$lib/shared/stores/AppState.svelte', () => ({
  appState: mockAppState,
}));

vi.mock('$lib/shared/stores/AuthState.svelte', () => ({
  authState: {
    isSignedIn: false,
    isAnonymous: false,
    isAuthenticated: false,
    accessToken: null,
    email: null,
  },
  setLocalUser: vi.fn(),
}));

vi.mock('$lib/shared/stores/authPersistence', () => ({
  savePersistedAuth: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('$lib/features/library', async () => {
  const mod = await import('../../mocks/MockGoogleLoginButton.svelte');
  return { GoogleLoginButton: mod.default };
});

import WelcomeScreen from '$lib/features/welcome/WelcomeScreen.svelte';

describe('WelcomeScreen', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('bounds the login card with viewport cap and internal scroll classes', () => {
    render(WelcomeScreen, { props: { t } });

    const section = screen.getByLabelText('welcome.signInAria');
    expect(section.classList.contains('max-h-full')).toBe(true);

    const card = screen.getByTestId('welcome-login-card');
    expect(card.classList.contains('max-h-full')).toBe(true);
    expect(card.classList.contains('overflow-y-auto')).toBe(true);
  });

  it('renders the brand wordmark in place of the ad-hoc initials lockup', () => {
    render(WelcomeScreen, { props: { t } });

    const wordmark = screen.getByAltText('NEXO');
    expect(wordmark).toBeInTheDocument();
    expect(wordmark.getAttribute('src')).toBe('./nexo-horizontal-dark.svg');
  });

  it('renders the eyebrow, headline, subtitle and three feature items', () => {
    render(WelcomeScreen, { props: { t } });

    expect(screen.getByText('welcome.eyebrow')).toBeInTheDocument();
    expect(screen.getByText('welcome.headline')).toBeInTheDocument();
    expect(screen.getByText('welcome.subtitle')).toBeInTheDocument();
    // Read, annotate, sync: the stats promise was cut with the trust footer,
    // which repeated "sync" three times.
    for (const i of [1, 2, 4]) {
      expect(screen.getByText(`welcome.feature${i}Label`)).toBeInTheDocument();
      expect(screen.getByText(`welcome.feature${i}Description`)).toBeInTheDocument();
    }
    expect(screen.queryByText('welcome.feature3Label')).toBeNull();
    expect(screen.queryByText('welcome.footer.item1')).toBeNull();
  });

  it('renders the login card with both ways in and says what local mode means', () => {
    render(WelcomeScreen, { props: { t } });

    expect(screen.getByText('welcome.cardTitle')).toBeInTheDocument();
    expect(screen.getByText('welcome.cardSubtitle')).toBeInTheDocument();
    expect(screen.getByTestId('google-login-stub')).toBeInTheDocument();
    expect(screen.getByText('welcome.continueLocal')).toBeInTheDocument();
    expect(screen.getByText('welcome.localHint')).toBeInTheDocument();
  });

  // The link had no handler, and Google sign-in needs no separate account.
  it('offers no dead "create account" link', () => {
    render(WelcomeScreen, { props: { t } });
    expect(screen.queryByText('welcome.cardCreateAccount')).toBeNull();
  });
});
