/**
 * AddonConsent — per-addon network-consent model (slice 8, Domain C).
 *
 * Fail-closed contract (mirrors Android `AddonConsentStore` + the 0018 table):
 * consent is per addon (never global), absent means denied (including newly
 * installed addons), and no consent implies ZERO network/transport I/O from
 * `AddonCatalogProvider.resolveAddonAccess` (a `CONSENT_REQUIRED` outcome).
 * Grants and withdrawals persist durably, so both survive a restart;
 * uninstall revokes the row (Rust `deleteInstalledAddon`), so a reinstall
 * starts denied and can never inherit consent.
 *
 * Consumers never touch the backend: they depend on the `AddonConsentStore`
 * async persistence port and the `AddonConsentGate` sync gate. Production
 * wires `TauriAddonConsentStore` through the `addonConsent` singleton;
 * tests inject `InMemoryAddonConsentStore`.
 */
import { invoke } from '@tauri-apps/api/core';

/** Async persistence port: the durable consent snapshot (swappable backend). */
export interface AddonConsentStore {
  list(): Promise<{ addonId: string; granted: boolean }[]>;
  set(addonId: string, granted: boolean): Promise<void>;
}

/** Sync, fail-closed gate for providers: unknown / unhydrated ⇒ `false`. */
export interface AddonConsentGate {
  ensureLoaded(): Promise<void>;
  hasConsent(addonId: string): boolean;
}

/** Process-local store: consent recorded once per addon id (tests / fallback). */
export class InMemoryAddonConsentStore implements AddonConsentStore {
  private readonly grantedIds = new Set<string>();

  async list(): Promise<{ addonId: string; granted: boolean }[]> {
    return [...this.grantedIds].map((addonId) => ({ addonId, granted: true }));
  }

  async set(addonId: string, granted: boolean): Promise<void> {
    if (granted) this.grantedIds.add(addonId);
    else this.grantedIds.delete(addonId);
  }
}

/**
 * Durable store over the Rust `listAddonConsents` / `setAddonConsent`
 * commands (migration 0018). Withdrawal keeps the row with `granted = 0`,
 * so the denial itself survives a restart.
 */
class TauriAddonConsentStore implements AddonConsentStore {
  async list(): Promise<{ addonId: string; granted: boolean }[]> {
    return invoke<{ addonId: string; granted: boolean }[]>('listAddonConsents');
  }

  async set(addonId: string, granted: boolean): Promise<void> {
    await invoke('setAddonConsent', { id: addonId, granted });
  }
}

export class AddonConsentService implements AddonConsentGate {
  private snapshot: Map<string, boolean> | null = null;
  private loading: Promise<void> | null = null;

  constructor(private readonly store: AddonConsentStore = new InMemoryAddonConsentStore()) {}

  /**
   * Load the durable snapshot once (memoized: concurrent callers share the
   * single in-flight read, later calls are a no-op). Until it resolves the
   * gate stays fail-closed (`hasConsent` ⇒ `false`).
   */
  ensureLoaded(): Promise<void> {
    this.loading ??= this.store
      .list()
      .then((rows) => {
        this.snapshot = new Map(rows.map((row) => [row.addonId, row.granted]));
      })
      .finally(() => {
        // Keep `loading` resolved (not null) so the snapshot is read once;
        // a rejected load leaves the gate fail-closed, never half-hydrated.
        if (!this.snapshot) this.snapshot = new Map();
      });
    return this.loading;
  }

  /** Sync read over the snapshot: unknown / unhydrated ⇒ `false`. */
  hasConsent(addonId: string): boolean {
    return this.snapshot?.get(addonId) ?? false;
  }

  /** Grant durably: snapshot + store, so the grant survives a restart. */
  async grant(addonId: string): Promise<void> {
    await this.ensureLoaded();
    await this.store.set(addonId, true);
    this.snapshot?.set(addonId, true);
  }

  /** Withdraw durably: snapshot + store, re-blocking immediately. */
  async revoke(addonId: string): Promise<void> {
    await this.ensureLoaded();
    await this.store.set(addonId, false);
    this.snapshot?.set(addonId, false);
  }
}

/** Production singleton: durable Tauri store, shared by every consumer. */
export const addonConsent: AddonConsentService = new AddonConsentService(
  new TauriAddonConsentStore(),
);
