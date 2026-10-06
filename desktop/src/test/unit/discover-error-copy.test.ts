/**
 * G3 — honest error classification and copy: connectivity, throttling and
 * upstream failures must render three distinct messages, and a 429 must never
 * read as "sin conexión".
 */
import { render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import DiscoverRailError from '$lib/features/discover/DiscoverRailError.svelte';
import { discoverErrorKey } from '$lib/features/discover/discoverErrorCopy';
import { isOfflineCatalogCode } from '$lib/features/discover/DiscoverRailsDomainState.svelte';
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import type { CatalogErrorCode } from '$lib/shared/services/catalog';

const t = (key: MessageKey): string => key;

describe('discover error copy split (G3)', () => {
  it('treats only NETWORK_ERROR as offline', () => {
    expect(isOfflineCatalogCode('NETWORK_ERROR')).toBe(true);
    expect(isOfflineCatalogCode('UPSTREAM_TIMEOUT')).toBe(false);
    expect(isOfflineCatalogCode('RATE_LIMITED')).toBe(false);
    expect(isOfflineCatalogCode('UPSTREAM_ERROR')).toBe(false);
    expect(isOfflineCatalogCode('NOT_FOUND')).toBe(false);
  });

  it('selects offline, slow, rate-limited and upstream keys', () => {
    expect(discoverErrorKey('NETWORK_ERROR')).toBe('discover.offline');
    expect(discoverErrorKey('UPSTREAM_TIMEOUT')).toBe('discover.errorSlow');
    expect(discoverErrorKey('RATE_LIMITED')).toBe('discover.rateLimited');
    expect(discoverErrorKey('UPSTREAM_ERROR')).toBe('discover.errorUpstream');
    expect(discoverErrorKey(null)).toBe('discover.errorUpstream');
  });

  it('renders the four distinct rail error states', async () => {
    const cases: [CatalogErrorCode, string][] = [
      ['NETWORK_ERROR', 'discover.offline'],
      ['UPSTREAM_TIMEOUT', 'discover.errorSlow'],
      ['RATE_LIMITED', 'discover.rateLimited'],
      ['UPSTREAM_ERROR', 'discover.errorUpstream'],
    ];
    for (const [code, expected] of cases) {
      const { unmount } = render(DiscoverRailError, {
        props: { code, t, onRetry: vi.fn() },
      });
      expect(await screen.findByText(expected)).toBeInTheDocument();
      unmount();
    }
  });

  it('offers a retry action for a rate-limited rail', async () => {
    const onRetry = vi.fn();
    render(DiscoverRailError, { props: { code: 'RATE_LIMITED', t, onRetry } });
    const retry = await screen.findByRole('button', { name: 'discover.retry' });
    retry.click();
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
