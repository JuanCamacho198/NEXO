/**
 * Book access resolution through the `stream` resource (protocol v3).
 *
 * `resolveAddonAccess` keeps its name and signature but is now answered by
 * `createAddonClient().getStreams`, so the pending option list comes from every
 * installed addon that declares `stream` for the book id. Consent gates that
 * resource alone and is checked BEFORE any transport call: without a grant the
 * resolve rejects `CONSENT_REQUIRED` with zero I/O, exactly as the v2 adapter
 * did for `resolve`. `meta` is ungated and fills the addon detail sheet for a
 * namespaced id (`gutenberg:1342`), which no source prefix owns.
 *
 * Ordering is the product rule, not install order: `public-domain` → `borrow` →
 * `purchase` → `unknown`, then `epub` → `pdf`, then addon `addedAt`, then
 * addon `id`. A torrent never reaches the option list: `assertDeliverableStream`
 * drops it and counts it in `skipped`.
 *
 * Fully offline: every addon request is answered by an injected
 * `ResourceTransport`, so no test here can reach the network.
 */
import { describe, expect, it, vi } from 'vitest';

import {
  createAddonClient,
  parseStreamPayload,
  type AddonClient,
  type AddonDescriptor,
  type AddonManifestV3,
  type ResourceTransport,
} from '@nexo/addon-protocol';
import {
  AddonFanoutCatalogProvider,
  EMPTY_ADDON_ACCESS,
} from '$lib/shared/services/addons/AddonCatalogProvider';
import {
  AddonConsentService,
  InMemoryAddonConsentStore,
} from '$lib/shared/services/addons/AddonConsent';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import { addonSource, type CatalogBook } from '$lib/shared/services/catalog/CatalogProvider';

const ADDON_A = 'aaaaaaaaaaaaaaaa';
const ADDON_B = 'bbbbbbbbbbbbbbbb';
const ADDON_OTHER_PREFIX = 'cccccccccccccccc';

const BOOK_ID = 'gutenberg:1342';
const BOOK_URL = `https://a.example/stream/book/${BOOK_ID}.json`;
const META_URL = `https://a.example/meta/book/${BOOK_ID}.json`;

const BOOK: CatalogBook = {
  id: BOOK_ID,
  provider: addonSource(ADDON_A),
  title: 'Moby Dick',
  authors: ['Herman Melville'],
  coverUrl: null,
  languages: ['en'],
  subjects: ['sea'],
  downloadUrl: null,
};

/** A stream-only addon: it answers `stream` and `meta` for one id prefix. */
function streamManifest(id: string, name: string, prefixes: string[] = ['gutenberg:']) {
  const manifest: AddonManifestV3 = {
    id,
    version: '3.0.0',
    name,
    types: ['book'],
    catalogs: [],
    resources: [
      { name: 'meta', types: ['book'], idPrefixes: prefixes },
      { name: 'stream', types: ['book'], idPrefixes: prefixes },
    ],
  };
  return manifest;
}

function descriptor(addonId: string, manifest: AddonManifestV3, addedAt: number): AddonDescriptor {
  return {
    id: addonId,
    installUrl: `https://${addonId === ADDON_A ? 'a' : addonId === ADDON_B ? 'b' : 'c'}.example/manifest.json`,
    addedAt,
    enabled: true,
    manifest,
  };
}

const ADDON_A_DESCRIPTOR = descriptor(
  ADDON_A,
  streamManifest('org.example.a', 'Standard Ebooks'),
  100,
);
const ADDON_B_DESCRIPTOR = descriptor(
  ADDON_B,
  streamManifest('org.example.b', 'Open Library'),
  300,
);
const ADDON_OTHER_PREFIX_DESCRIPTOR = descriptor(
  ADDON_OTHER_PREFIX,
  streamManifest('org.example.c', 'ISBN books', ['isbn:']),
  50,
);

/** `public-domain` EPUB plus a `purchase` EPUB, both from the same addon. */
const A_STREAMS = {
  streams: [
    {
      name: 'Standard Ebooks',
      title: 'EPUB · Public domain',
      url: 'https://a.example/pd.epub',
      behaviorHints: { accessType: 'public-domain', format: 'epub' },
    },
    {
      name: 'Standard Ebooks',
      title: 'EPUB · Purchase',
      url: 'https://a.example/buy.epub',
      behaviorHints: { accessType: 'purchase' },
    },
  ],
};

const META_PAYLOAD = {
  meta: {
    id: BOOK_ID,
    type: 'book',
    name: 'Moby Dick',
    poster: 'https://covers.example/moby.jpg',
    description: 'A whale, an obsession.',
    releaseInfo: '1851',
    authors: ['Herman Melville'],
    languages: ['en'],
    subjects: ['sea'],
  },
};

function addonClient(handler: (url: string) => { status: number; body?: unknown }): {
  client: AddonClient;
  urls: string[];
} {
  const urls: string[] = [];
  const transport: ResourceTransport = async (url) => {
    urls.push(url);
    const response = handler(url);
    return response.status >= 200 && response.status <= 299
      ? { status: response.status, body: response.body }
      : { status: response.status, body: undefined };
  };
  return { client: createAddonClient({ transport }), urls };
}

function consentFor(addonIds: readonly string[]): Promise<AddonConsentService> {
  const consent = new AddonConsentService(new InMemoryAddonConsentStore());
  return Promise.all(addonIds.map((id) => consent.grant(id))).then(() => consent);
}

function providerWith(
  addons: AddonDescriptor[],
  client: AddonClient,
  options: {
    consent?: AddonConsentService;
    onFailure?: (failures: readonly { addonId: string; reason: string }[]) => void;
    isOwned?: (sourceItemId: string) => Promise<boolean>;
  } = {},
): AddonFanoutCatalogProvider {
  return new AddonFanoutCatalogProvider({
    loadAddons: async () => addons,
    clientFor: () => client,
    ...options,
  });
}

function errOf(promise: Promise<unknown>): Promise<CatalogError> {
  return promise.catch((err: unknown) => err as CatalogError) as Promise<CatalogError>;
}

/** The torrent shape the protocol must never deliver: hash, hints, sources. */
const TORRENT_STREAMS = {
  streams: [
    {
      name: 'TPB',
      title: 'Moby Dick · 1080p',
      infoHash: 'd1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0',
      fileIdx: 0,
      sources: [{ url: 'tracker:opentrackr.org:1337', type: 'torrent' }],
    },
    {
      name: 'TPB magnet',
      url: 'magnet:?xt=urn:btih:d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0',
      behaviorHints: { bittorrent: { infoHash: 'd1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0' } },
    },
  ],
};

describe('resolveAddonAccess through the stream resource', () => {
  it('asks the stream route for a namespaced id and offers the download', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: A_STREAMS }));
    const provider = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A]),
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    expect(urls).toEqual([BOOK_URL]);
    expect(resolution.canDownloadInApp).toBe(true);
    expect(resolution.downloadUrl).toBe('https://a.example/pd.epub');
    expect(resolution.options.map((option) => option.url)).toEqual([
      'https://a.example/pd.epub',
      'https://a.example/buy.epub',
    ]);
    expect(resolution.owned).toBe(false);
  });

  it('checks consent before any transport call and keeps CONSENT_REQUIRED', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: A_STREAMS }));
    const provider = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: new AddonConsentService(new InMemoryAddonConsentStore()),
    });

    const err = await errOf(provider.resolveAddonAccess(BOOK));

    expect(err).toBeInstanceOf(CatalogError);
    expect(err.code).toBe('CONSENT_REQUIRED');
    expect(err.retryable).toBe(false);
    expect(urls).toEqual([]);
  });

  it('a withdrawn grant blocks the resolve again with zero calls', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: A_STREAMS }));
    const consent = await consentFor([ADDON_A]);
    await consent.revoke(ADDON_A);
    const provider = providerWith([ADDON_A_DESCRIPTOR], client, { consent });

    const err = await errOf(provider.resolveAddonAccess(BOOK));

    expect(err.code).toBe('CONSENT_REQUIRED');
    expect(urls).toEqual([]);
  });

  it('an unconsented addon is skipped when another one holds the grant', async () => {
    const { client, urls } = addonClient((url) =>
      url.includes('a.example')
        ? { status: 200, body: A_STREAMS }
        : { status: 200, body: { streams: [{ url: 'https://b.example/borrow' }] } },
    );
    const provider = providerWith([ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A]),
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    expect(urls).toEqual([BOOK_URL]);
    expect(resolution.options.map((option) => option.url)).toEqual([
      'https://a.example/pd.epub',
      'https://a.example/buy.epub',
    ]);
  });

  it('no addon declares the stream resource ⇒ empty resolution with zero I/O', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: A_STREAMS }));
    const provider = providerWith([ADDON_OTHER_PREFIX_DESCRIPTOR], client, {
      consent: new AddonConsentService(new InMemoryAddonConsentStore()),
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    expect(resolution).toEqual(EMPTY_ADDON_ACCESS);
    expect(urls).toEqual([]);
  });
});

describe('option order is the product rule', () => {
  it('orders public-domain → borrow → purchase → unknown, then epub → pdf, then addedAt', async () => {
    const { client } = addonClient((url) =>
      url.includes('a.example')
        ? {
            status: 200,
            body: {
              streams: [
                { url: 'https://a.example/borrow', behaviorHints: { accessType: 'borrow' } },
                {
                  url: 'https://a.example/pd.pdf',
                  behaviorHints: { accessType: 'public-domain', format: 'pdf' },
                },
                {
                  url: 'https://a.example/pd.epub',
                  behaviorHints: { accessType: 'public-domain', format: 'epub' },
                },
              ],
            },
          }
        : {
            status: 200,
            body: {
              streams: [
                { url: 'https://b.example/unknown' },
                { url: 'https://b.example/buy', behaviorHints: { accessType: 'purchase' } },
                {
                  url: 'https://b.example/pd.epub',
                  behaviorHints: { accessType: 'public-domain', format: 'epub' },
                },
              ],
            },
          },
    );
    const provider = providerWith([ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A, ADDON_B]),
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    // Both addons answered; the earlier install wins inside a tier, and the
    // access tier outranks install order across tiers.
    expect(resolution.options.map((option) => option.url)).toEqual([
      'https://a.example/pd.epub',
      'https://b.example/pd.epub',
      'https://a.example/pd.pdf',
      'https://a.example/borrow',
      'https://b.example/buy',
      'https://b.example/unknown',
    ]);
    expect(resolution.options.map((option) => option.accessType)).toEqual([
      'public-domain',
      'public-domain',
      'public-domain',
      'borrow',
      'purchase',
      'unknown',
    ]);
  });

  it('merges every answering addon and keeps the list when one addon fails', async () => {
    const onFailure = vi.fn();
    const { client } = addonClient((url) =>
      url.includes('a.example') ? { status: 200, body: A_STREAMS } : { status: 503 },
    );
    const provider = providerWith([ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A, ADDON_B]),
      onFailure,
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    expect(resolution.options.map((option) => option.url)).toEqual([
      'https://a.example/pd.epub',
      'https://a.example/buy.epub',
    ]);
    expect(onFailure).toHaveBeenCalledWith([{ addonId: ADDON_B, reason: 'status' }]);
  });
});

describe('owned books', () => {
  it('flags an owned item by source item id and never offers a re-download', async () => {
    const { client } = addonClient(() => ({ status: 200, body: A_STREAMS }));
    const isOwned = vi.fn(async (sourceItemId: string) => sourceItemId === BOOK_ID);
    const provider = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A]),
      isOwned,
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    expect(isOwned).toHaveBeenCalledWith(BOOK_ID);
    // Flagged, never hidden: every option stays, only the transfer is withheld.
    expect(resolution.owned).toBe(true);
    expect(resolution.canDownloadInApp).toBe(false);
    expect(resolution.downloadUrl).toBeNull();
    expect(resolution.options).toHaveLength(2);
  });

  it('an unowned item is never flagged owned', async () => {
    const { client } = addonClient(() => ({ status: 200, body: A_STREAMS }));
    const provider = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A]),
      isOwned: async () => false,
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    expect(resolution.owned).toBe(false);
    expect(resolution.canDownloadInApp).toBe(true);
  });
});

describe('the torrent guard', () => {
  it('drops torrent-shaped streams and counts them as skipped', () => {
    const payload = parseStreamPayload(TORRENT_STREAMS);

    expect(payload.streams).toEqual([]);
    expect(payload.skipped).toBe(2);
  });

  it('never turns a torrent into an option or a download', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: TORRENT_STREAMS }));
    const provider = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A]),
    });

    const resolution = await provider.resolveAddonAccess(BOOK);

    expect(urls).toEqual([BOOK_URL]);
    expect(resolution.options).toEqual([]);
    expect(resolution.canDownloadInApp).toBe(false);
    expect(resolution.downloadUrl).toBeNull();
  });
});

describe('getMeta fills the detail sheet', () => {
  it('answers a namespaced id without consent', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: META_PAYLOAD }));
    const provider = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: new AddonConsentService(new InMemoryAddonConsentStore()),
    });

    const meta = await provider.getMeta(BOOK_ID);

    expect(urls).toEqual([META_URL]);
    expect(meta).toMatchObject({
      id: BOOK_ID,
      provider: addonSource(ADDON_A),
      title: 'Moby Dick',
      coverUrl: 'https://covers.example/moby.jpg',
      description: 'A whale, an obsession.',
      authors: ['Herman Melville'],
      languages: ['en'],
      subjects: ['sea'],
    });
  });

  it('rejects NOT_FOUND for an id no addon can describe, with zero I/O', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: META_PAYLOAD }));
    const provider = providerWith([ADDON_OTHER_PREFIX_DESCRIPTOR], client);

    const err = await errOf(provider.getDetails(BOOK_ID));

    expect(err.code).toBe('NOT_FOUND');
    expect(urls).toEqual([]);
  });
});

describe('composite routing for namespaced addon ids', () => {
  it('routes getDetails and resolveAddonAccess to the fan-out provider', async () => {
    const { client, urls } = addonClient((url) =>
      url.includes('/meta/')
        ? { status: 200, body: META_PAYLOAD }
        : { status: 200, body: A_STREAMS },
    );
    const fanout = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A]),
    });
    const composite = new CompositeCatalogProvider([fanout]);

    const book = await composite.getDetails(BOOK_ID);
    expect(book.title).toBe('Moby Dick');

    const resolution = await composite.resolveAddonAccess(BOOK);
    expect(resolution.downloadUrl).toBe('https://a.example/pd.epub');
    expect(urls).toEqual([META_URL, BOOK_URL]);
  });

  it('still rejects an id no provider owns with zero I/O', async () => {
    const { client, urls } = addonClient(() => ({ status: 200, body: A_STREAMS }));
    const fanout = providerWith([ADDON_A_DESCRIPTOR], client, {
      consent: await consentFor([ADDON_A]),
    });
    const composite = new CompositeCatalogProvider([fanout]);

    const err = await errOf(composite.getDetails('isbn:9780141439518'));
    expect(err.code).toBe('NOT_FOUND');
    expect(urls).toEqual([]);
  });
});
