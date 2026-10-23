/**
 * Library metadata — ask the installed metadata addons about a book the reader
 * already owns, and write the answers back fill-only.
 *
 * The catalog paths already ask for `meta`, but only about items an addon
 * itself listed. The ask here is different in one decisive way: the payload is
 * the reader's own library, so it is gated by its own consent scope
 * (`library_meta`, per addon, default off, never implied by `stream`) and by the
 * addon's declared `idPrefixes`. An addon that declares `meta` without
 * prefixes is never asked — see `shouldConsultLibraryMeta`.
 *
 * One response per `(addonId, candidateId)` is cached for 24 h through the
 * package `TtlCache`; a manual refresh reads through a per-call key so a stale
 * entry can never satisfy it.
 *
 * A failing addon is silent: no toast, no dialog, no retry.
 */
import {
  TtlCache,
  candidateIds,
  mergeMeta,
  shouldConsultLibraryMeta,
  type AddonClient,
  type AddonDescriptor,
  type LibraryMetaBook,
  type LibraryMetaPatch,
  type MetaPreview,
} from '@nexo/addon-protocol';

import { addonConsent, type LibraryMetaConsentGate } from './AddonConsent';
import { defaultAddonTransport, getAddonRegistry, type AddonTransport } from './AddonRegistry';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';

/** One cached response per addon/identifier pair, for a day. */
export const LIBRARY_META_CACHE_TTL_MS = 24 * 60 * 60 * 1000;

const LIBRARY_META_CACHE_NAMESPACE = 'library-meta';

/**
 * Cache key for one `(addonId, candidateId)` response. A caller that must not
 * read a stale entry passes a refresh token, which produces a key nothing has
 * ever written.
 */
export function libraryMetaCacheKey(
  addonId: string,
  candidateId: string,
  refreshToken?: string,
): string {
  const stable = `${LIBRARY_META_CACHE_NAMESPACE}|${addonId}|${candidateId}`;
  return refreshToken === undefined ? stable : `${stable}|refresh|${refreshToken}`;
}

export interface LibraryMetadataBook extends LibraryMetaBook {
  id: string;
  title: string;
  /** `books.source_item_id`: the addon-namespaced id, when the row carries one. */
  sourceItemId?: string | null;
  /** `books.isbn13`: digits only, when the row carries one. */
  isbn13?: string | null;
}

/** Cover bytes the caller downloaded for a book that had none. */
export interface LibraryCoverBytes {
  bytes: Uint8Array;
  contentType: string | null;
}

/**
 * Where the merge is written back. Both members are optional because a platform
 * may be able to store one and not the other; an omitted member means "this
 * client cannot persist that yet", never "drop it silently".
 */
export interface LibraryMetadataPersistence {
  /** Fill fields on the EXISTING row. Never creates one, never touches progress. */
  updateFields?: (bookId: string, patch: LibraryMetaPatch) => Promise<void>;
  /** Store the cover exactly as import stores one: bytes into the covers dir. */
  updateCover?: (bookId: string, cover: LibraryCoverBytes) => Promise<void>;
}

export interface LibraryMetadataOptions {
  /** Installed addons as protocol descriptors, in install order. */
  loadAddons: () => Promise<AddonDescriptor[]>;
  /** The registry's memoized protocol client for one addon. */
  clientFor: (addonId: string) => AddonClient;
  /** The separate `library_meta` consent scope. There is no fail-open default. */
  consent: LibraryMetaConsentGate;
  /** Response cache; defaults to one owned by this service. */
  cache?: TtlCache;
  now?: () => number;
  /** Write-back port. Omitted ⇒ the merge result is returned but not stored. */
  persist?: LibraryMetadataPersistence;
  /** Byte fetch for a cover. Defaults to the Rust addon transport. */
  fetchBytes?: AddonTransport;
}

export interface LibraryMetadataResult {
  /** The ids this book can be asked about; empty ⇒ the book was skipped. */
  candidates: string[];
  /** Addons actually asked (a cached response counts as asked). */
  consulted: string[];
  /** Exactly the fields the metadata filled. Empty when nothing was written. */
  changed: LibraryMetaPatch;
}

/** A metadata response as cached: `null` records "asked, nothing to say". */
type CachedMeta = MetaPreview | null;

function fieldPatch(changed: LibraryMetaPatch): LibraryMetaPatch {
  const { coverUrl: coverUrl, ...fields } = changed;
  void coverUrl;
  return fields;
}

function hasFields(patch: LibraryMetaPatch): boolean {
  return Object.keys(patch).length > 0;
}

/**
 * Ask the installed metadata addons about a book the reader already owns.
 *
 * Every failure is silent: a dead addon, an unreadable payload, a refused cover
 * download, a missing writer — all of them leave the book exactly as it was and
 * never surface a notice. Enrichment is an improvement, never a step the reader
 * can be interrupted by.
 */
export class LibraryMetadataService {
  private readonly cache: TtlCache;
  private readonly now: () => number;
  private readonly fetchBytes: AddonTransport;

  constructor(private readonly options: LibraryMetadataOptions) {
    this.now = options.now ?? (() => Date.now());
    this.cache = options.cache ?? new TtlCache({ now: this.now });
    this.fetchBytes = options.fetchBytes ?? defaultAddonTransport();
  }

  /** `refresh` is the explicit per-book action: it bypasses the 24 h cache. */
  async enrich(
    book: LibraryMetadataBook,
    opts: { refresh?: boolean } = {},
  ): Promise<LibraryMetadataResult> {
    const candidates = candidateIds(book);
    const skipped: LibraryMetadataResult = { candidates, consulted: [], changed: {} };
    if (candidates.length === 0) return skipped;

    const addons = await this.eligibleAddons(candidates);
    if (addons.length === 0) return skipped;

    const consented = await this.consentedAddons(addons);
    if (consented.length === 0) return skipped;

    const refreshToken = opts.refresh === true ? String(this.now()) : undefined;
    const consulted = new Set<string>();
    let merged: LibraryMetadataBook = { ...book };
    let changed: LibraryMetaPatch = {};

    for (const addon of consented) {
      for (const candidate of candidates) {
        if (!shouldConsultLibraryMeta(addon.manifest, [candidate], { enabled: addon.enabled })) {
          continue;
        }
        const meta = await this.readMeta(addon, candidate, refreshToken);
        if (meta === null) continue;
        consulted.add(addon.id);
        const step = mergeMeta(merged, meta);
        merged = step.book;
        changed = { ...changed, ...step.changed };
      }
    }

    if (!hasFields(changed) && changed.coverUrl === undefined) {
      return { candidates, consulted: [...consulted], changed: {} };
    }

    await this.writeFields(book.id, changed);
    await this.writeCover(book.id, changed.coverUrl);
    return { candidates, consulted: [...consulted], changed };
  }

  /** Enabled addons that declare `meta` for a book type. A read failure is none. */
  private async eligibleAddons(candidates: readonly string[]): Promise<AddonDescriptor[]> {
    let addons: AddonDescriptor[];
    try {
      addons = await this.options.loadAddons();
    } catch {
      return [];
    }
    return addons.filter((addon) =>
      shouldConsultLibraryMeta(addon.manifest, candidates, { enabled: addon.enabled }),
    );
  }

  private async consentedAddons(addons: readonly AddonDescriptor[]): Promise<AddonDescriptor[]> {
    try {
      await this.options.consent.ensureLoaded();
    } catch {
      return [];
    }
    return addons.filter((addon) => this.options.consent.hasLibraryMetaConsent(addon.id));
  }

  /**
   * One response per `(addonId, candidateId)`, cached for 24 h. A refresh reads
   * through a per-call key, so a stale entry can never satisfy it, and the fresh
   * answer is written under the stable key the next read uses.
   */
  private async readMeta(
    addon: AddonDescriptor,
    candidateId: string,
    refreshToken: string | undefined,
  ): Promise<CachedMeta> {
    const stableKey = libraryMetaCacheKey(addon.id, candidateId);
    if (refreshToken === undefined) {
      const cached = this.cache.get(stableKey) as CachedMeta | undefined;
      if (cached !== undefined) return cached;
    }

    let meta: CachedMeta = null;
    try {
      const result = await this.options.clientFor(addon.id).getMeta([addon], { id: candidateId });
      meta = result.items[0] ?? null;
    } catch {
      // A failing addon is silent: no toast, no dialog, no retry.
      return null;
    }
    this.cache.set(stableKey, meta, LIBRARY_META_CACHE_TTL_MS);
    return meta;
  }

  private async writeFields(bookId: string, changed: LibraryMetaPatch): Promise<void> {
    const fields = fieldPatch(changed);
    if (!hasFields(fields)) return;
    try {
      await this.options.persist?.updateFields?.(bookId, fields);
    } catch {
      // Best effort: the row keeps what it had.
    }
  }

  /**
   * The cover goes through the import cover path (bytes → covers dir), so a
   * metadata cover and an extracted one are the same artefact. A download that
   * fails, or exceeds the transport's byte cap, leaves the book without a cover.
   */
  private async writeCover(bookId: string, coverUrl: string | undefined): Promise<void> {
    const updateCover = this.options.persist?.updateCover;
    if (coverUrl === undefined || updateCover === undefined) return;

    let fetched: Awaited<ReturnType<AddonTransport>>;
    try {
      fetched = await this.fetchBytes(coverUrl);
    } catch {
      return;
    }
    if (fetched.status < 200 || fetched.status > 299 || fetched.body.byteLength === 0) return;

    try {
      await updateCover(bookId, { bytes: fetched.body, contentType: fetched.contentType });
    } catch {
      // Best effort: no cover beats a broken row.
    }
  }
}

/** Production write-back over the library port (the same routine import uses). */
export class TauriLibraryMetadataPersistence implements LibraryMetadataPersistence {
  constructor(private readonly port: LibraryPort = new TauriLibraryAdapter()) {}

  async updateCover(bookId: string, cover: LibraryCoverBytes): Promise<void> {
    await this.port.upsertBookCover({
      bookId,
      data: Array.from(cover.bytes),
      mimeType: cover.contentType ?? undefined,
    });
  }
}

/**
 * Production singleton: the shared registry's memoized clients, the separate
 * consent gate, and the library cover writer.
 *
 * `updateFields` is deliberately absent: the desktop `books` table has no
 * field-level writer (its metadata columns are filled by the import extractor,
 * and `upsertBook` rewrites progress). Nothing is faked into `upsertBook` to
 * make this look complete.
 */
export const libraryMetadata: LibraryMetadataService = new LibraryMetadataService({
  loadAddons: () => getAddonRegistry().listDescriptors(),
  clientFor: (addonId) => getAddonRegistry().addonClient(addonId),
  consent: addonConsent,
  persist: new TauriLibraryMetadataPersistence(),
});

/**
 * Import trigger: called once the book row exists, and never awaited by the
 * import. Silent by construction — no toast, no dialog, no retry.
 */
export function enrichLibraryBookAfterImport(book: LibraryMetadataBook): void {
  void libraryMetadata.enrich(book).catch(() => {});
}
