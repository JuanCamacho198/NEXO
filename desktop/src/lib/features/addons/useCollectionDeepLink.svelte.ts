/**
 * useCollectionDeepLink — collection deep-link state machine (task 15).
 *
 * `nexo://collection?url=…` → fetch the collection → confirm the whole set → one
 * registry install per entry. Svelte 5 runes store, mirroring
 * `useInstallDeepLink` for the single-addon flow.
 *
 * The confirmation lists every addon and marks the ones that declare `stream`,
 * because those need the reader's own consent later. Confirming installs the set
 * and grants nothing: consent is per addon and never inherited from a collection.
 */
import {
  AddonFetchError,
  AddonFetchErrorCode,
  assertHttpsInstallUrl,
} from '@nexo/manifest-validator';
import type { AddonManifestV3 } from '@nexo/addon-protocol';

import type { AddonTransport } from '$lib/shared/services/addons/AddonRegistry';
import {
  fetchCollection,
  installCollection,
  requestsStream,
  type CollectionInstallRegistry,
} from '$lib/shared/services/addons/collection';

/** One row of the confirmation: the addon plus what it will ask for later. */
export interface CollectionPreviewEntry {
  manifest: AddonManifestV3;
  transportUrl: string;
  requestsStream: boolean;
}

export interface CollectionDeepLinkDeps {
  registry: CollectionInstallRegistry;
  transport?: AddonTransport;
}

export type CollectionDeepLinkState =
  'idle' | 'fetching' | 'confirming' | 'installing' | 'done' | 'error';

export function createCollectionDeepLink(deps: CollectionDeepLinkDeps): {
  state: CollectionDeepLinkState;
  entries: CollectionPreviewEntry[];
  skipped: number;
  alreadyInstalled: string[];
  errorCode: AddonFetchErrorCode | null;
  dialogOpen: boolean;
  handleCollectionUrl: (collectionUrl: string) => Promise<void>;
  confirm: () => Promise<void>;
  cancel: () => void;
} {
  const registry = deps.registry;
  const transport =
    deps.transport ??
    (async () => {
      throw new AddonFetchError(AddonFetchErrorCode.NETWORK, 'no transport');
    });

  let state = $state<CollectionDeepLinkState>('idle');
  let entries = $state<CollectionPreviewEntry[]>([]);
  let skipped = $state(0);
  let alreadyInstalled = $state<string[]>([]);
  let errorCode = $state<AddonFetchErrorCode | null>(null);
  let dialogOpen = $state(false);
  let pendingCollectionUrl: string | null = null;
  let busy = false;

  function reset(): void {
    entries = [];
    skipped = 0;
    alreadyInstalled = [];
    pendingCollectionUrl = null;
  }

  async function handleCollectionUrl(collectionUrl: string): Promise<void> {
    if (busy) return;
    busy = true;
    state = 'fetching';
    errorCode = null;
    try {
      assertHttpsInstallUrl(collectionUrl);
      const parsed = await fetchCollection(transport, collectionUrl);
      if (parsed.entries.length === 0) {
        throw new AddonFetchError(
          AddonFetchErrorCode.INVALID_MANIFEST,
          'collection holds no importable addons',
        );
      }
      const installedUrls = new Set((await registry.listInstalled()).map((row) => row.url));
      entries = parsed.entries.map((entry) => ({
        manifest: entry.manifest,
        transportUrl: entry.transportUrl,
        requestsStream: requestsStream(entry.manifest),
      }));
      skipped = parsed.skipped;
      alreadyInstalled = entries
        .filter((entry) => installedUrls.has(entry.transportUrl))
        .map((entry) => entry.transportUrl);
      pendingCollectionUrl = collectionUrl;
      state = 'confirming';
      dialogOpen = true;
    } catch (error) {
      reset();
      state = 'error';
      errorCode =
        error instanceof AddonFetchError ? error.code : AddonFetchErrorCode.INVALID_MANIFEST;
      dialogOpen = true;
    } finally {
      busy = false;
    }
  }

  async function confirm(): Promise<void> {
    const collectionUrl = pendingCollectionUrl;
    if (!collectionUrl || entries.length === 0 || busy) return;
    busy = true;
    state = 'installing';
    try {
      const result = await installCollection(registry, entries);
      if (result.installed === 0) {
        state = 'error';
        errorCode = AddonFetchErrorCode.INVALID_MANIFEST;
        return;
      }
      reset();
      state = 'done';
      dialogOpen = false;
    } finally {
      busy = false;
    }
  }

  function cancel(): void {
    state = 'idle';
    errorCode = null;
    dialogOpen = false;
    reset();
  }

  return {
    get state() {
      return state;
    },
    get entries() {
      return entries;
    },
    get skipped() {
      return skipped;
    },
    get alreadyInstalled() {
      return alreadyInstalled;
    },
    get errorCode() {
      return errorCode;
    },
    get dialogOpen() {
      return dialogOpen;
    },
    handleCollectionUrl,
    confirm,
    cancel,
  };
}
