/**
 * AddonRegistry — install-by-URL service over the desktop registry store.
 * install(url): HTTPS check → platform fetch → validateManifest → addonId →
 * UPSERT preserving enabled. enable/disable/uninstall mutate rows; the
 * composite's existence-check cache guard (design A1) keeps uninstalled or
 * disabled addon sources from ever serving cached entries.
 */
import { invoke } from '@tauri-apps/api/core';

import { addonIdFromUrl } from './addonId';
import {
  AddonFetchError,
  AddonFetchErrorCode,
  assertHttpsInstallUrl,
  validateManifest,
  type AddonManifest,
} from '@nexo/manifest-validator';

/** Closed set of built-in source names addon manifests must not claim. */
const BUILTIN_SOURCE_NAMES = new Set(['gutendex', 'openlibrary']);

export { AddonFetchErrorCode, AddonFetchError };
export type { AddonManifest };

export interface InstalledAddonRow {
  id: string;
  url: string;
  manifest: AddonManifest;
  enabled: boolean;
  addedAt: number;
}

/** Raw persistence row; mirrors Rust `InstalledAddonDto` / Room `AddonEntity`. */
interface RegistryStoreRow {
  id: string;
  url: string;
  manifestJson: string;
  enabled: boolean;
  addedAt: number;
}

export interface AddonRegistryStore {
  listInstalled(): Promise<RegistryStoreRow[]>;
  upsert(row: RegistryStoreRow): Promise<void>;
  setEnabled(id: string, enabled: boolean): Promise<void>;
  uninstall(id: string): Promise<void>;
}

/** Default store: the thin Rust CRUD commands (desktop only). */
export class TauriAddonRegistryStore implements AddonRegistryStore {
  async listInstalled(): Promise<RegistryStoreRow[]> {
    return invoke<RegistryStoreRow[]>('listInstalledAddons');
  }

  async upsert(row: RegistryStoreRow): Promise<void> {
    await invoke('upsertInstalledAddon', { addon: row });
  }

  async setEnabled(id: string, enabled: boolean): Promise<void> {
    await invoke('setAddonEnabled', { id, enabled });
  }

  async uninstall(id: string): Promise<void> {
    await invoke('deleteInstalledAddon', { id });
  }
}

export interface AddonFetchResult {
  status: number;
  contentType: string | null;
  body: Uint8Array;
}

/** Platform network layer seam (desktop: the Rust fetchAddonResource command). */
export type AddonTransport = (url: string) => Promise<AddonFetchResult>;

/**
 * Platform network layer seam (desktop: the Rust fetchAddonResource command
 * - addon hosts are never fetched from the webview). Used by the install
 * flow and AddonCatalogProvider; tests inject fakes via the constructors.
 */
export function defaultAddonTransport(): AddonTransport {
  return async (url) => {
    const res = await invoke<{ status: number; contentType: string | null; body: number[] }>(
      'fetchAddonResource',
      { url },
    );
    return { status: res.status, contentType: res.contentType, body: new Uint8Array(res.body) };
  };
}

export class AddonRegistry {
  private readonly store: AddonRegistryStore;
  private readonly transport: AddonTransport;
  private readonly now: () => number;
  private readonly listeners = new Set<(version: number) => void>();
  private mutationVersion = 0;

  constructor(
    options: {
      store?: AddonRegistryStore;
      transport?: AddonTransport;
      now?: () => number;
    } = {},
  ) {
    this.store = options.store ?? new TauriAddonRegistryStore();
    this.transport = options.transport ?? defaultAddonTransport();
    this.now = options.now ?? (() => Date.now());
  }

  async install(url: string): Promise<AddonManifest> {
    assertHttpsInstallUrl(url);
    const fetched = await this.transport(url);
    if (fetched.status < 200 || fetched.status >= 300) {
      throw new AddonFetchError(
        AddonFetchErrorCode.NETWORK,
        `addon fetch status ${fetched.status}`,
      );
    }
    const manifest = validateManifest(fetched.body, fetched.contentType);
    return this.installManifest(url, manifest);
  }

  /** Install with a manifest already fetched + validated (deep-link preview flow). */
  async installManifest(url: string, manifest: AddonManifest): Promise<AddonManifest> {
    if (
      BUILTIN_SOURCE_NAMES.has(manifest.id) ||
      manifest.id.includes(':') ||
      manifest.id.startsWith('builtin')
    ) {
      throw new AddonFetchError(
        AddonFetchErrorCode.INVALID_MANIFEST,
        `addon id must not collide with built-in source ids: ${manifest.id}`,
      );
    }
    const addonId = await addonIdFromUrl(url);
    const existing = (await this.store.listInstalled()).find((row) => row.id === addonId);
    await this.store.upsert({
      id: addonId,
      url,
      manifestJson: JSON.stringify(manifest),
      enabled: existing ? existing.enabled : true,
      addedAt: existing ? existing.addedAt : this.now(),
    });
    this.notifyChanged();
    return manifest;
  }

  async listInstalled(): Promise<InstalledAddonRow[]> {
    const rows = await this.store.listInstalled();
    return rows
      .map((row) => {
        try {
          return { row, manifest: JSON.parse(row.manifestJson) as AddonManifest };
        } catch {
          return { row, manifest: null };
        }
      })
      .filter(
        (entry): entry is { row: RegistryStoreRow; manifest: AddonManifest } =>
          entry.manifest !== null,
      )
      .map(({ row, manifest }) => ({
        id: row.id,
        url: row.url,
        manifest,
        enabled: row.enabled,
        addedAt: row.addedAt,
      }))
      .sort((a, b) => a.addedAt - b.addedAt || a.id.localeCompare(b.id));
  }

  async setEnabled(id: string, enabled: boolean): Promise<void> {
    try {
      await this.store.setEnabled(id, enabled);
    } finally {
      this.notifyChanged();
    }
  }

  async uninstall(id: string): Promise<void> {
    try {
      await this.store.uninstall(id);
    } finally {
      this.notifyChanged();
    }
  }

  /**
   * Live-composite hook: fires after every registry mutation (install,
   * enable/disable, uninstall) with a monotonically increasing version.
   * Returns an unsubscribe function.
   */
  onChanged(listener: (version: number) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private notifyChanged(): void {
    this.mutationVersion += 1;
    for (const listener of [...this.listeners]) {
      try {
        listener(this.mutationVersion);
      } catch {
        // listener failures must never break the mutation flow
      }
    }
  }
}

/** Shared singleton so onChanged listeners stay consistent across consumers */
let sharedRegistry: AddonRegistry | null = null;
export function getAddonRegistry(): AddonRegistry {
  sharedRegistry ??= new AddonRegistry();
  return sharedRegistry;
}
