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

  it('renders the brand block with glass logo, name and Desktop subtitle', () => {
    render(WelcomeScreen, { props: { t } });

    expect(screen.getByText('NP')).toBeInTheDocument();
    expect(screen.getByText('Nexo')).toBeInTheDocument();
    expect(screen.getByText('welcome.brandDesktop')).toBeInTheDocument();
  });

  it('renders the eyebrow, headline, subtitle and the 4 feature items', () => {
    render(WelcomeScreen, { props: { t } });

    expect(screen.getByText('welcome.eyebrow')).toBeInTheDocument();
    expect(screen.getByText('welcome.headline')).toBeInTheDocument();
    expect(screen.getByText('welcome.subtitle')).toBeInTheDocument();
    for (const i of [1, 2, 3, 4]) {
      expect(screen.getByText(`welcome.feature${i}Label`)).toBeInTheDocument();
      expect(screen.getByText(`welcome.feature${i}Description`)).toBeInTheDocument();
    }
  });

  it('renders the login card with auth options and the create-account link', () => {
    render(WelcomeScreen, { props: { t } });

    expect(screen.getByText('welcome.cardTitle')).toBeInTheDocument();
    expect(screen.getByText('welcome.cardSubtitle')).toBeInTheDocument();
    expect(screen.getByTestId('google-login-stub')).toBeInTheDocument();
    expect(screen.getByText('welcome.continueLocal')).toBeInTheDocument();
    expect(screen.getByText('welcome.cardCreateAccount')).toBeInTheDocument();
  });

  it('renders the 3 trust items with title and description', () => {
    render(WelcomeScreen, { props: { t } });

    for (const i of [1, 2, 3]) {
      expect(screen.getByText(`welcome.footer.item${i}`)).toBeInTheDocument();
      expect(screen.getByText(`welcome.footer.item${i}Desc`)).toBeInTheDocument();
    }
  });
});
