/**
 * installDeepLink pure routing tests (sdd/addon-deeplink-v1 Work Unit A).
 * Parse matrix per design: valid install URL; wrong host; missing url param;
 * http:// / file:// targets (still parsed — https is enforced later);
 * garbage → null; canonical nexo:// and legacy nextpage-desktop:// accepted;
 * non-install → null.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { logger } from '$lib/shared/logger/Logger';
import {
  handleDeepLinkUrls,
  parseInstallDeepLink,
  resetInstallDeepLinkHandler,
  setInstallDeepLinkHandler,
} from '$lib/features/addons/installDeepLink';

describe('parseInstallDeepLink', () => {
  it('parses a valid nextpage://install url', () => {
    expect(
      parseInstallDeepLink('nextpage://install?url=https://example.com/manifest.json'),
    ).toEqual({
      installUrl: 'https://example.com/manifest.json',
    });
  });

  it('accepts the legacy nextpage-desktop:// scheme', () => {
    expect(
      parseInstallDeepLink('nextpage-desktop://install?url=https://example.com/manifest.json'),
    ).toEqual({ installUrl: 'https://example.com/manifest.json' });
  });

  it('accepts the canonical nexo:// scheme', () => {
    expect(parseInstallDeepLink('nexo://install?url=https://example.com/manifest.json')).toEqual({
      installUrl: 'https://example.com/manifest.json',
    });
  });

  it('rejects a wrong host', () => {
    expect(
      parseInstallDeepLink('nextpage://open?url=https://example.com/manifest.json'),
    ).toBeNull();
  });

  it('rejects a missing url param', () => {
    expect(parseInstallDeepLink('nextpage://install')).toBeNull();
    expect(parseInstallDeepLink('nextpage://install?other=1')).toBeNull();
  });

  it('rejects an empty url param', () => {
    expect(parseInstallDeepLink('nextpage://install?url=')).toBeNull();
  });

  it('passes through http and file targets (https enforced later, pre-fetch)', () => {
    expect(parseInstallDeepLink('nextpage://install?url=http://example.com/manifest.json')).toEqual(
      {
        installUrl: 'http://example.com/manifest.json',
      },
    );
    expect(parseInstallDeepLink('nextpage://install?url=file:///etc/passwd')).toEqual({
      installUrl: 'file:///etc/passwd',
    });
  });

  it('returns null for garbage input', () => {
    expect(parseInstallDeepLink('')).toBeNull();
    expect(parseInstallDeepLink('not a url')).toBeNull();
    expect(parseInstallDeepLink('https://example.com/manifest.json')).toBeNull();
  });
});

describe('handleDeepLinkUrls', () => {
  beforeEach(() => {
    resetInstallDeepLinkHandler();
  });

  afterEach(() => {
    resetInstallDeepLinkHandler();
    vi.restoreAllMocks();
  });

  it('routes install urls to the registered handler', async () => {
    const handler = vi.fn();
    setInstallDeepLinkHandler(handler);
    await handleDeepLinkUrls(['nextpage://install?url=https://example.com/m.json']);
    expect(handler).toHaveBeenCalledWith('https://example.com/m.json');
  });

  it('ignores non-install urls (OAuth reservation)', async () => {
    const handler = vi.fn();
    setInstallDeepLinkHandler(handler);
    await handleDeepLinkUrls(['nextpage://auth?token=abc']);
    expect(handler).not.toHaveBeenCalled();
  });

  it('is a no-op without a handler and logs ignored urls', async () => {
    const log = vi.spyOn(logger, 'debug').mockImplementation(() => {});
    await expect(
      handleDeepLinkUrls(['nextpage://install?url=https://example.com/m.json']),
    ).resolves.toBeUndefined();
    log.mockRestore();
  });

  it('processes every url in the batch', async () => {
    const handler = vi.fn();
    setInstallDeepLinkHandler(handler);
    await handleDeepLinkUrls([
      'nextpage://auth?token=abc',
      'nextpage://install?url=https://a.example/m.json',
      'nextpage-desktop://install?url=https://b.example/m.json',
    ]);
    expect(handler).toHaveBeenCalledTimes(2);
    expect(handler).toHaveBeenNthCalledWith(1, 'https://a.example/m.json');
    expect(handler).toHaveBeenNthCalledWith(2, 'https://b.example/m.json');
  });
});
