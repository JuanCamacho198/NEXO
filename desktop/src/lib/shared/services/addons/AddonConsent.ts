/**
 * AddonConsent — per-addon, per-scope network-consent model (slice 8, Domain C).
 *
 * Fail-closed contract (mirrors Android `AddonConsentStore` + the 0018 table):
 * consent is per addon (never global), absent means denied (including newly
 * installed addons), and no consent implies ZERO network/transport I/O from the
 * addon access resolve (a `CONSENT_REQUIRED` outcome).
 *
 * Two scopes, deliberately disjoint:
 *
 * - `stream` gates the `stream` resource, the only one that can point at an
 *   arbitrary third-party file. `AddonCatalogProvider.resolveAddonAccess` and
 *   the v3 fan-out resolve both check this gate BEFORE building or sending a
 *   request.
 * - `library_meta` gates asking a metadata addon about a book the reader
 *   ALREADY OWNS. The request itself is a plain `meta` read, but the payload is
 *   the reader's library, so it is a disclosure the reader makes explicitly.
 *   `stream` consent never implies it, and installing a collection never grants
 *   it: only the per-addon toggle does.
 *
 * Grants and withdrawals persist durably, so both survive a restart; uninstall
 * clears both scopes (`forget`), so a reinstall starts denied and can never
 * inherit consent.
 *
 * Consumers never touch the backend: they depend on the `AddonConsentStore`
 * async persistence port and the sync gates. Production wires
 * `TauriAddonConsentStore` through the `addonConsent` singleton; tests inject
 * `InMemoryAddonConsentStore`.
 */
import { invoke } from '@tauri-apps/api/core';

/**
 * `stream` is the original slice-8 grant; `library_meta` is the metadata-addon
 * disclosure added with library enrichment. Stored as separate rows.
 */
export type AddonConsentScope = 'stream' | 'library_meta';

/** Async persistence port: the durable consent snapshot (swappable backend). */
export interface AddonConsentStore {
  list(): Promise<{ addonId: string; granted: boolean; scope?: AddonConsentScope }[]>;
  /** `stream` when no scope is given, so existing callers keep their meaning. */
  set(addonId: string, granted: boolean, scope?: AddonConsentScope): Promise<void>;
}

/** Sync, fail-closed gate for providers: unknown / unhydrated ⇒ `false`. */
export interface AddonConsentGate {
  ensureLoaded(): Promise<void>;
  hasConsent(addonId: string): boolean;
}

/** Sync, fail-closed gate for library enrichment (the `library_meta` scope). */
export interface LibraryMetaConsentGate {
  ensureLoaded(): Promise<void>;
  hasLibraryMetaConsent(addonId: string): boolean;
}

/**
 * The candidates whose addon id holds a grant, in candidate order. Fail-closed
 * by construction: an unknown addon and an unhydrated snapshot both answer
 * "no". Callers must `ensureLoaded()` first and send no request for an addon
 * this drops.
 */
export function consentedCandidates<T extends { id: string }>(
  gate: AddonConsentGate,
  candidates: readonly T[],
): T[] {
  return candidates.filter((candidate) => gate.hasConsent(candidate.id));
}

/** Scope-qualified row key for a store that keeps one flat namespace. */
const LIBRARY_META_KEY_SUFFIX = '#library_meta';

function storeKey(addonId: string, scope: AddonConsentScope): string {
  return scope === 'library_meta' ? `${addonId}${LIBRARY_META_KEY_SUFFIX}` : addonId;
}

/** Process-local store: one grant per addon id and scope (tests / fallback). */
export class InMemoryAddonConsentStore implements AddonConsentStore {
  private readonly grantedKeys = new Set<string>();

  async list(): Promise<{ addonId: string; granted: boolean; scope: AddonConsentScope }[]> {
    return [...this.grantedKeys].map((key) =>
      key.endsWith(LIBRARY_META_KEY_SUFFIX)
        ? {
            addonId: key.slice(0, -LIBRARY_META_KEY_SUFFIX.length),
            granted: true,
            scope: 'library_meta' as const,
          }
        : { addonId: key, granted: true, scope: 'stream' as const },
    );
  }

  async set(addonId: string, granted: boolean, scope: AddonConsentScope = 'stream'): Promise<void> {
    const key = storeKey(addonId, scope);
    if (granted) this.grantedKeys.add(key);
    else this.grantedKeys.delete(key);
  }
}

/**
 * Durable store over the Rust `listAddonConsents` / `setAddonConsent` commands
 * (migration 0018). Withdrawal keeps the row with `granted = 0`, so the denial
 * itself survives a restart.
 *
 * The `library_meta` scope rides the same table under a scope-qualified key
 * because the table has one boolean column per addon id and 0018 is shipped:
 * adding a scope column would be a schema migration for a consent marker. The
 * uninstall path calls `forget`, which removes both keys, so the 0018 invariant
 * ("a reinstall starts denied") still holds.
 */
class TauriAddonConsentStore implements AddonConsentStore {
  async list(): Promise<{ addonId: string; granted: boolean; scope: AddonConsentScope }[]> {
    const rows = await invoke<{ addonId: string; granted: boolean }[]>('listAddonConsents');
    return rows.map((row) =>
      row.addonId.endsWith(LIBRARY_META_KEY_SUFFIX)
        ? {
            addonId: row.addonId.slice(0, -LIBRARY_META_KEY_SUFFIX.length),
            granted: row.granted,
            scope: 'library_meta' as const,
          }
        : { addonId: row.addonId, granted: row.granted, scope: 'stream' as const },
    );
  }

  async set(addonId: string, granted: boolean, scope: AddonConsentScope = 'stream'): Promise<void> {
    await invoke('setAddonConsent', { id: storeKey(addonId, scope), granted });
  }
}

export class AddonConsentService implements AddonConsentGate, LibraryMetaConsentGate {
  private stream: Map<string, boolean> | null = null;
  private libraryMeta: Map<string, boolean> | null = null;
  private loading: Promise<void> | null = null;

  constructor(private readonly store: AddonConsentStore = new InMemoryAddonConsentStore()) {}

  /**
   * Load the durable snapshot once (memoized: concurrent callers share the
   * single in-flight read, later calls are a no-op). Until it resolves both
   * gates stay fail-closed (`false`).
   */
  ensureLoaded(): Promise<void> {
    this.loading ??= this.store
      .list()
      .then((rows) => {
        const stream = new Map<string, boolean>();
        const libraryMeta = new Map<string, boolean>();
        for (const row of rows) {
          if (row.scope === 'library_meta') libraryMeta.set(row.addonId, row.granted);
          else stream.set(row.addonId, row.granted);
        }
        this.stream = stream;
        this.libraryMeta = libraryMeta;
      })
      .finally(() => {
        // Keep `loading` resolved (not null) so the snapshot is read once;
        // a rejected load leaves both gates fail-closed, never half-hydrated.
        this.stream ??= new Map();
        this.libraryMeta ??= new Map();
      });
    return this.loading;
  }

  /** Sync read over the snapshot: unknown / unhydrated ⇒ `false`. */
  hasConsent(addonId: string): boolean {
    return this.stream?.get(addonId) ?? false;
  }

  /**
   * The `library_meta` grant: true only when the reader turned it on for THIS
   * addon. A `stream` grant lives in a different map and can never answer here.
   */
  hasLibraryMetaConsent(addonId: string): boolean {
    return this.libraryMeta?.get(addonId) ?? false;
  }

  /** Grant durably: snapshot + store, so the grant survives a restart. */
  async grant(addonId: string): Promise<void> {
    await this.ensureLoaded();
    await this.store.set(addonId, true);
    this.stream?.set(addonId, true);
  }

  /** Withdraw durably: snapshot + store, re-blocking immediately. */
  async revoke(addonId: string): Promise<void> {
    await this.ensureLoaded();
    await this.store.set(addonId, false);
    this.stream?.set(addonId, false);
  }

  /** Grant the library-metadata disclosure. Never implied by `grant`. */
  async grantLibraryMeta(addonId: string): Promise<void> {
    await this.ensureLoaded();
    await this.store.set(addonId, true, 'library_meta');
    this.libraryMeta?.set(addonId, true);
  }

  /** Withdraw the library-metadata disclosure; the `stream` grant is untouched. */
  async revokeLibraryMeta(addonId: string): Promise<void> {
    await this.ensureLoaded();
    await this.store.set(addonId, false, 'library_meta');
    this.libraryMeta?.set(addonId, false);
  }

  /**
   * Drop every grant this addon holds. Uninstall calls it so a reinstall can
   * never inherit either scope.
   */
  async forget(addonId: string): Promise<void> {
    await this.revoke(addonId);
    await this.revokeLibraryMeta(addonId);
  }
}

/** Production singleton: durable Tauri store, shared by every consumer. */
export const addonConsent: AddonConsentService = new AddonConsentService(
  new TauriAddonConsentStore(),
);
