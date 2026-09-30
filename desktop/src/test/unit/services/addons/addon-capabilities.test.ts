/**
 * Addon capabilities + resolve (slice 9, tasks 9.4–9.6, 9.9, 9.11).
 *
 * Badges render one per declared capability (known id `resolve` localized,
 * unknown ids raw) and none when empty; the detail dialog lists the declared
 * capabilities. Resolution: a manifest without `resolveUrl` ⇒ empty with 0
 * I/O; declared `capabilities` without `resolve` ⇒ empty with 0 I/O (an
 * UNDECLARED list leaves `resolveUrl` authoritative); a `resolveUrl`-capable
 * addon resolves through its declared `resolveUrl` within its declared
 * capabilities and only with consent granted (denied ⇒ CONSENT_REQUIRED, 0
 * I/O). The composite routes by the existing book-id prefix over the active
 * source set; `getDetails` prefix routing and `NOT_FOUND` zero-I/O are pinned
 * unchanged.
 */
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';

import {
  AddonCatalogProvider,
  EMPTY_ADDON_ACCESS,
} from '$lib/shared/services/addons/AddonCatalogProvider';
import {
  AddonConsentService,
  InMemoryAddonConsentStore,
} from '$lib/shared/services/addons/AddonConsent';
import {
  createRebuildingCatalogProvider,
  defaultCatalogProviders,
  CompositeCatalogProvider,
} from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import type { CatalogBook, CatalogSource } from '$lib/shared/services/catalog/CatalogProvider';
import type { AddonManifest } from '@nexo/manifest-validator';
import type { AddonTransport, InstalledAddonRow } from '$lib/shared/services/addons/AddonRegistry';
import AddonCapabilityBadges from '$lib/features/addons/components/AddonCapabilityBadges.svelte';
import AddonCapabilityDetail from '$lib/features/addons/components/AddonCapabilityDetail.svelte';
import type { MessageKey } from '$lib/shared/i18n';

const here = dirname(fileURLToPath(import.meta.url));
const readSource = (rel: string): string => readFileSync(resolve(here, rel), 'utf8');

const t = (key: MessageKey): string => key;

const ADDON_ID = 'a1b2c3d4e5f60718';
const OTHER_ADDON_ID = '0011223344556677';

const RESOLVE_MANIFEST: AddonManifest = {
  id: 'space-books',
  name: 'Space Books',
  version: '2.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
  searchUrl: 'https://space.example/search?q={query}&page={page}',
  resolveUrl:
    'https://space.example/resolve?isbn={isbn}&title={title}&author={author}&ol={openLibraryId}&gb={googleBooksId}',
  capabilities: ['resolve'],
};

const V1_MANIFEST: AddonManifest = {
  id: 'space-books',
  name: 'Space Books',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
  searchUrl: 'https://space.example/search?q={query}&page={page}',
};

const NO_RESOLVE_CAP_MANIFEST: AddonManifest = {
  ...RESOLVE_MANIFEST,
  capabilities: ['catalog-search'],
};

function publicDomainBook(): CatalogBook {
  return {
    id: `addon:${ADDON_ID}:book-1`,
    provider: `addon:${ADDON_ID}`,
    title: 'Dune Messiah',
    authors: ['Herbert'],
    coverUrl: null,
    languages: ['en'],
    subjects: ['sci-fi'],
    downloadUrl: null,
    isbn13: '978-0-123',
    isPublicDomain: true,
    openLibraryWorkId: '/works/OL1W',
    googleBooksId: 'g123',
  };
}

const RESOLVE_PAYLOAD = {
  results: [
    {
      accessType: 'free',
      license: null,
      readUrl: 'https://space.example/read/1',
      downloadUrl: 'https://space.example/dl/1.epub',
    },
    {
      accessType: 'buy',
      license: null,
      readUrl: 'https://shop.example/buy/1',
      downloadUrl: null,
    },
    {
      accessType: 'rental',
      license: null,
      readUrl: 'https://x.example/rent/1',
      downloadUrl: 'https://x.example/dl-rent.epub',
    },
    {
      accessType: 'free',
      license: null,
      readUrl: 'http://insecure.example/read/1',
      downloadUrl: 'http://insecure.example/dl/1.epub',
    },
  ],
};

function resolveTransport(
  payload: unknown = RESOLVE_PAYLOAD,
): AddonTransport & { calls: string[] } {
  const calls: string[] = [];
  const transport = (async (url: string) => {
    calls.push(url);
    return {
      status: 200,
      contentType: 'application/json',
      body: new TextEncoder().encode(JSON.stringify(payload)),
    };
  }) as AddonTransport;
  return Object.assign(transport, { calls });
}

function grantedConsent(): AddonConsentService {
  return new AddonConsentService(new InMemoryAddonConsentStore());
}

async function grantedConsentFor(id: string): Promise<AddonConsentService> {
  const consent = grantedConsent();
  await consent.grant(id);
  return consent;
}

function errOf(promise: Promise<unknown>): Promise<CatalogError> {
  return promise.catch((err: unknown) => err as CatalogError) as Promise<CatalogError>;
}

describe('addon resolve (consent + endpoint + capabilities)', () => {
  it('a granted resolveUrl-capable addon resolves through its declared resolveUrl', async () => {
    const transport = resolveTransport();
    const provider = new AddonCatalogProvider(
      RESOLVE_MANIFEST,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );

    const resolution = await provider.resolveAddonAccess(publicDomainBook());

    expect(transport.calls).toHaveLength(1);
    expect(transport.calls[0]).toBe(
      'https://space.example/resolve?isbn=978-0-123&title=Dune%20Messiah&author=Herbert&ol=%2Fworks%2FOL1W&gb=g123',
    );
    expect(resolution.canDownloadInApp).toBe(true);
    expect(resolution.downloadUrl).toBe('https://space.example/dl/1.epub');
    expect(resolution.options).toEqual([
      {
        group: 'FREE',
        titleKey: 'discover.accessOpen',
        url: 'https://space.example/read/1',
        opensInApp: false,
      },
      {
        group: 'BUY',
        titleKey: 'discover.accessOpen',
        url: 'https://shop.example/buy/1',
        opensInApp: false,
      },
      {
        group: 'FREE',
        titleKey: 'discover.accessOpen',
        url: 'https://x.example/rent/1',
        opensInApp: false,
      },
    ]);
  });

  it('identity params fall back (isbn10, empty author/ids)', async () => {
    const transport = resolveTransport({ results: [] });
    const provider = new AddonCatalogProvider(
      RESOLVE_MANIFEST,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );
    const book: CatalogBook = {
      ...publicDomainBook(),
      isbn13: null,
      isbn10: '0-123',
      authors: [],
      openLibraryWorkId: null,
      googleBooksId: null,
    };

    await provider.resolveAddonAccess(book);

    expect(transport.calls[0]).toContain('isbn=0-123&title=Dune%20Messiah&author=&ol=&gb=');
  });

  it('denied consent ⇒ CONSENT_REQUIRED with 0 calls even when resolve-capable', async () => {
    const transport = resolveTransport();
    const provider = new AddonCatalogProvider(
      RESOLVE_MANIFEST,
      ADDON_ID,
      transport,
      0,
      grantedConsent(),
    );

    const err = await errOf(provider.resolveAddonAccess(publicDomainBook()));
    expect(err.code).toBe('CONSENT_REQUIRED');
    expect(transport.calls).toHaveLength(0);
  });

  it('a manifest without resolveUrl ⇒ empty resolution with 0 I/O', async () => {
    const transport = resolveTransport();
    const provider = new AddonCatalogProvider(
      V1_MANIFEST,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );

    const resolution = await provider.resolveAddonAccess(publicDomainBook());
    expect(resolution).toEqual({ ...EMPTY_ADDON_ACCESS, options: [] });
    expect(transport.calls).toHaveLength(0);
  });

  it('declared capabilities without resolve ⇒ empty with 0 I/O', async () => {
    const transport = resolveTransport();
    const provider = new AddonCatalogProvider(
      NO_RESOLVE_CAP_MANIFEST,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );

    const resolution = await provider.resolveAddonAccess(publicDomainBook());
    expect(resolution).toEqual({ canDownloadInApp: false, downloadUrl: null, options: [] });
    expect(transport.calls).toHaveLength(0);
  });

  it('an UNDECLARED capabilities list leaves resolveUrl authoritative (v1-style)', async () => {
    const manifest: AddonManifest = { ...RESOLVE_MANIFEST };
    delete manifest.capabilities;
    const transport = resolveTransport({ results: [] });
    const provider = new AddonCatalogProvider(
      manifest,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );

    await provider.resolveAddonAccess(publicDomainBook());
    expect(transport.calls).toHaveLength(1);
  });

  it('in-app download needs free-or-cleared AND public-domain AND https download', async () => {
    const transport = resolveTransport({
      results: [
        {
          accessType: 'buy',
          license: 'CC0 ',
          readUrl: 'https://x.example/buy',
          downloadUrl: 'https://x.example/cleared.epub',
        },
        {
          accessType: 'free',
          license: null,
          readUrl: 'https://x.example/free',
          downloadUrl: 'https://x.example/free.epub',
        },
      ],
    });
    const provider = new AddonCatalogProvider(
      RESOLVE_MANIFEST,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );

    // License-cleared (CC0) wins as the first downloadable candidate.
    const cleared = await provider.resolveAddonAccess(publicDomainBook());
    expect(cleared.canDownloadInApp).toBe(true);
    expect(cleared.downloadUrl).toBe('https://x.example/cleared.epub');

    // Same payload, non-public-domain book: no in-app download, options stay.
    const gated = await provider.resolveAddonAccess({
      ...publicDomainBook(),
      isPublicDomain: false,
    });
    expect(gated.canDownloadInApp).toBe(false);
    expect(gated.downloadUrl).toBeNull();
    expect(gated.options).toHaveLength(2);
  });

  it('unknown accessType is never downloadable but its readUrl still surfaces', async () => {
    const transport = resolveTransport({
      results: [
        {
          accessType: 'rental',
          license: null,
          readUrl: 'https://x.example/rent',
          downloadUrl: 'https://x.example/rent.epub',
        },
      ],
    });
    const provider = new AddonCatalogProvider(
      RESOLVE_MANIFEST,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );

    const resolution = await provider.resolveAddonAccess(publicDomainBook());
    expect(resolution.canDownloadInApp).toBe(false);
    expect(resolution.downloadUrl).toBeNull();
    expect(resolution.options).toEqual([
      {
        group: 'FREE',
        titleKey: 'discover.accessOpen',
        url: 'https://x.example/rent',
        opensInApp: false,
      },
    ]);
  });

  it('a malformed resolve payload rejects UPSTREAM_ERROR', async () => {
    const transport = resolveTransport({ nope: [] });
    const provider = new AddonCatalogProvider(
      RESOLVE_MANIFEST,
      ADDON_ID,
      transport,
      0,
      await grantedConsentFor(ADDON_ID),
    );

    const err = await errOf(provider.resolveAddonAccess(publicDomainBook()));
    expect(err.code).toBe('UPSTREAM_ERROR');
  });
});

describe('composite resolveAddonAccess routing (regression pin)', () => {
  function installedRow(id: string, manifest: AddonManifest): InstalledAddonRow {
    return { id, url: `https://space.example/${id}.json`, manifest, enabled: true, addedAt: 1 };
  }

  it('routes to the owning addon only; builtins and unknown ids resolve empty with 0 I/O', async () => {
    const transport = resolveTransport({ results: [] });
    const consent = await grantedConsentFor(ADDON_ID);
    const composite = new CompositeCatalogProvider(
      defaultCatalogProviders(
        [installedRow(ADDON_ID, RESOLVE_MANIFEST), installedRow(OTHER_ADDON_ID, V1_MANIFEST)],
        transport,
        '',
        consent,
      ),
    );

    await composite.resolveAddonAccess(publicDomainBook());
    expect(transport.calls).toHaveLength(1);

    const builtin = await composite.resolveAddonAccess({
      ...publicDomainBook(),
      id: 'gutendex:11',
      provider: 'builtin:gutendex',
    });
    expect(builtin).toEqual({ canDownloadInApp: false, downloadUrl: null, options: [] });

    const unknown = await composite.resolveAddonAccess({
      ...publicDomainBook(),
      id: 'no-such-prefix:book-1',
      provider: 'no-such-prefix',
    });
    expect(unknown).toEqual({ canDownloadInApp: false, downloadUrl: null, options: [] });
    expect(transport.calls).toHaveLength(1);
  });

  it('no owner/implementation ⇒ empty result (addon without resolveUrl)', async () => {
    const transport = resolveTransport();
    const composite = new CompositeCatalogProvider(
      defaultCatalogProviders(
        [installedRow(OTHER_ADDON_ID, V1_MANIFEST)],
        transport,
        '',
        await grantedConsentFor(OTHER_ADDON_ID),
      ),
    );

    const resolution = await composite.resolveAddonAccess({
      ...publicDomainBook(),
      id: `addon:${OTHER_ADDON_ID}:book-9`,
      provider: `addon:${OTHER_ADDON_ID}`,
    });
    expect(resolution).toEqual({ canDownloadInApp: false, downloadUrl: null, options: [] });
    expect(transport.calls).toHaveLength(0);
  });

  it('the rebuilding supplier serves resolve through the active source set', async () => {
    const transport = resolveTransport({ results: [] });
    const supplier = createRebuildingCatalogProvider(
      () => Promise.resolve([installedRow(ADDON_ID, RESOLVE_MANIFEST)]),
      transport,
      '',
      { consent: await grantedConsentFor(ADDON_ID) },
    );

    const built = await supplier.current();
    await built.resolveAddonAccess(publicDomainBook());
    expect(transport.calls).toHaveLength(1);
  });

  it('getDetails prefix routing and NOT_FOUND zero-I/O keep working unchanged', async () => {
    const transport = resolveTransport();
    const composite = new CompositeCatalogProvider(
      defaultCatalogProviders(
        [installedRow(ADDON_ID, RESOLVE_MANIFEST)],
        transport,
        '',
        await grantedConsentFor(ADDON_ID),
      ),
    );

    const err = await errOf(composite.getDetails('no-such-prefix:book-1'));
    expect(err.code).toBe('NOT_FOUND');
    expect(transport.calls).toHaveLength(0);

    const page = await composite.searchSource('addon:0000000000000000' as CatalogSource, 'dune', 1);
    expect(page).toEqual({ results: [], nexoPage: null, totalCount: 0 });
    expect(transport.calls).toHaveLength(0);
  });

  it('liveComposite forwards resolveAddonAccess to the supplier composite', () => {
    const source = readSource('../../../../lib/shared/services/catalog/liveComposite.ts');
    expect(source).toContain('resolveAddonAccess(book: CatalogBook)');
    expect(source).toContain('supplier.current().then((c) => c.resolveAddonAccess(book))');
  });
});

describe('capability badges + detail (real components)', () => {
  it('badges render one per declared capability and none when empty', () => {
    const { container } = render(AddonCapabilityBadges, {
      t,
      capabilities: ['resolve', 'future-x'],
    } as Record<string, unknown>);
    const badges = container.querySelectorAll('button');
    expect(badges).toHaveLength(2);
    // Known id `resolve` is localized; unknown ids render raw.
    expect(container.textContent).toContain('addons.capabilities.resolve');
    expect(container.textContent).toContain('future-x');

    const { container: empty } = render(AddonCapabilityBadges, {
      t,
      capabilities: [],
    } as Record<string, unknown>);
    expect(empty.textContent).toBe('');
    expect(empty.querySelector('button')).toBeNull();
  });

  it('activating a badge opens the detail dialog listing the declared capabilities', async () => {
    const user = userEvent.setup();
    const { container } = render(AddonCapabilityBadges, {
      t,
      capabilities: ['resolve', 'future-x'],
    } as Record<string, unknown>);

    await user.click(container.querySelector('button') as HTMLButtonElement);
    const dialog = container.querySelector('[role="dialog"]');
    expect(dialog?.textContent).toContain('addons.capabilities.title');
    expect(dialog?.textContent).toContain('addons.capabilities.resolve');
    expect(dialog?.textContent).toContain('future-x');

    await user.click(screen.getByRole('button', { name: 'discover.dismiss' }));
    expect(container.querySelector('[role="dialog"]')).toBeNull();
  });

  it('the detail dialog lists every declared capability', () => {
    const { container } = render(AddonCapabilityDetail, {
      t,
      capabilities: ['resolve', 'catalog-search', 'future-x'],
      onClose: () => undefined,
    } as Record<string, unknown>);
    const items = container.querySelectorAll('li');
    expect(items).toHaveLength(3);
    expect(container.textContent).toContain('addons.capabilities.detail');
    expect(container.textContent).toContain('addons.capabilities.resolve');
    expect(container.textContent).toContain('catalog-search');
  });

  it('dismissing the detail calls onClose', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(AddonCapabilityDetail, {
      t,
      capabilities: ['resolve'],
      onClose,
    } as Record<string, unknown>);

    await user.click(screen.getByRole('button', { name: 'discover.dismiss' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
