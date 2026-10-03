import { render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { axe } from 'vitest-axe';
import { toHaveNoViolations } from 'vitest-axe/dist/matchers.js';
import HomeHero from '$lib/features/home/components/HomeHero.svelte';

expect.extend({ toHaveNoViolations });

const { mockAuthState } = vi.hoisted(() => {
  const state: {
    isLocalUser: boolean;
    localUser: {
      name: string;
      email: string | null;
      avatarUrl: string | null;
      localOnly: true;
    } | null;
    email: string | null;
    displayName: string | null;
    photoUrl: string | null;
  } = {
    isLocalUser: false,
    localUser: null,
    email: null,
    displayName: null,
    photoUrl: null,
  };
  return { mockAuthState: state };
});

vi.mock('$lib/shared/stores/AuthState.svelte', () => ({
  authState: mockAuthState,
}));

const dictionary: Record<string, string> = {
  'home.pageTitle': 'Home',
  'home.greeting': 'Hi',
  'home.greetingName': 'Hi, {{name}}',
  'home.heroDescription': 'Continue reading where you left off, on any device.',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  const template = dictionary[key] ?? key;
  if (!params) {
    return template;
  }
  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, token: string) =>
    String(params[token] ?? ''),
  );
};

const signOut = (): void => {
  mockAuthState.isLocalUser = false;
  mockAuthState.localUser = null;
  mockAuthState.email = null;
  mockAuthState.displayName = null;
  mockAuthState.photoUrl = null;
};

describe('HomeHero', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    signOut();
  });

  it('greets a Google user by name and renders their photo', () => {
    mockAuthState.email = 'jane@example.com';
    mockAuthState.displayName = 'Jane Doe';
    mockAuthState.photoUrl = 'https://example.com/jane.jpg';

    const { container } = render(HomeHero, { props: { t } });

    expect(screen.getByText('Hi, Jane Doe')).toBeInTheDocument();
    const img = container.querySelector('img');
    expect(img).not.toBeNull();
    expect(img).toHaveAttribute('src', 'https://example.com/jane.jpg');
  });

  it('greets a local user without a photo by name and falls back to initials', () => {
    mockAuthState.isLocalUser = true;
    mockAuthState.localUser = {
      name: 'Ana Torres',
      email: null,
      avatarUrl: null,
      localOnly: true,
    };

    const { container } = render(HomeHero, { props: { t } });

    expect(screen.getByText('Hi, Ana Torres')).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
    expect(container).toHaveTextContent('AT');
  });

  it('shows the generic greeting when there is no session', () => {
    render(HomeHero, { props: { t } });

    expect(screen.getByText('Hi')).toBeInTheDocument();
    expect(screen.queryByText(/Hi,/)).toBeNull();
  });

  it('keeps a long profile name on one truncating line as supporting chrome', () => {
    mockAuthState.email = 'long@example.com';
    mockAuthState.displayName =
      'Alexandria Cassandra Montgomery-Wellington the Third of the Northern Reading Guild';

    const { container } = render(HomeHero, { props: { t } });

    const heading = screen.getByRole('heading', { level: 2 });
    expect(heading).toHaveTextContent('Alexandria Cassandra Montgomery-Wellington');
    expect(heading).toHaveClass('truncate');

    const section = container.querySelector('section');
    expect(section).toHaveAttribute('aria-label', 'Home');
  });

  it('contains no gradient or glow classes', () => {
    mockAuthState.email = 'jane@example.com';
    mockAuthState.displayName = 'Jane Doe';

    const { container } = render(HomeHero, { props: { t } });
    const html = container.innerHTML;

    expect(html).not.toMatch(/linear-gradient/);
    expect(html).not.toContain('bg-blue-500');
    expect(html).not.toContain('backdrop-blur');
    expect(html).not.toContain('accent-blue');
  });

  it('has no axe violations', async () => {
    mockAuthState.email = 'jane@example.com';
    mockAuthState.displayName = 'Jane Doe';
    mockAuthState.photoUrl = 'https://example.com/jane.jpg';

    const { container } = render(HomeHero, { props: { t } });
    const results = await axe(container);
    const assertion = toHaveNoViolations(results);
    expect(assertion.pass, assertion.message()).toBe(true);
  });
});
