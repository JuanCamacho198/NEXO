/**
 * useInstallDeepLink — install deep-link state machine (sdd/addon-deeplink-v1
 * Work Unit A). Svelte 5 runes store mirroring useSettingsAddons patterns:
 * idle → parsing → fetching → confirming → installing → done | error.
 * Fetches + validates the manifest for preview (transport + validateManifest),
 * then installs via AddonRegistry.installManifest on confirm (single fetch).
 */
import {
  AddonFetchError,
  AddonFetchErrorCode,
  assertHttpsInstallUrl,
  validateManifest,
  type AddonManifest,
} from '@nexo/manifest-validator';
import type { AddonTransport } from '$lib/shared/services/addons/AddonRegistry';

export interface InstallDeepLinkRegistry {
  installManifest(url: string, manifest: AddonManifest): Promise<AddonManifest>;
  listInstalled(): Promise<{ id: string; url: string }[]>;
}

export interface InstallDeepLinkDeps {
  registry: InstallDeepLinkRegistry;
  transport?: AddonTransport;
}

export type InstallDeepLinkState =
  'idle' | 'parsing' | 'fetching' | 'confirming' | 'installing' | 'done' | 'error';

export function createInstallDeepLink(deps: InstallDeepLinkDeps): {
  state: InstallDeepLinkState;
  manifest: AddonManifest | null;
  errorCode: AddonFetchErrorCode | null;
  dialogOpen: boolean;
  alreadyInstalled: boolean;
  handleInstallUrl: (url: string) => Promise<void>;
  confirm: () => Promise<void>;
  cancel: () => void;
} {
  const registry = deps.registry;
  const transport =
    deps.transport ??
    (async () => {
      throw new AddonFetchError(AddonFetchErrorCode.NETWORK, 'no transport');
    });
  let state = $state<InstallDeepLinkState>('idle');
  let manifest = $state<AddonManifest | null>(null);
  let errorCode = $state<AddonFetchErrorCode | null>(null);
  let dialogOpen = $state(false);
  let alreadyInstalled = $state(false);
  let pendingUrl: string | null = null;
  let busy = false;

  async function handleInstallUrl(url: string): Promise<void> {
    if (busy) return;
    busy = true;
    state = 'parsing';
    errorCode = null;
    try {
      assertHttpsInstallUrl(url);
      pendingUrl = url;
      state = 'fetching';
      const fetched = await transport(url);
      if (fetched.status < 200 || fetched.status >= 300) {
        throw new AddonFetchError(
          AddonFetchErrorCode.NETWORK,
          `addon fetch status ${fetched.status}`,
        );
      }
      manifest = validateManifest(fetched.body, fetched.contentType);
      const rows = await registry.listInstalled();
      alreadyInstalled = rows.some((row) => row.url === url);
      state = 'confirming';
      dialogOpen = true;
    } catch (e) {
      state = 'error';
      errorCode = e instanceof AddonFetchError ? e.code : AddonFetchErrorCode.INVALID_MANIFEST;
      dialogOpen = true;
      pendingUrl = null;
    } finally {
      busy = false;
    }
  }

  async function confirm(): Promise<void> {
    if (!pendingUrl || !manifest || busy) return;
    busy = true;
    state = 'installing';
    try {
      await registry.installManifest(pendingUrl, manifest);
      state = 'done';
      dialogOpen = false;
      pendingUrl = null;
      manifest = null;
    } catch {
      state = 'error';
      errorCode = AddonFetchErrorCode.INVALID_MANIFEST;
      dialogOpen = true;
    } finally {
      busy = false;
    }
  }

  function cancel(): void {
    state = 'idle';
    errorCode = null;
    dialogOpen = false;
    pendingUrl = null;
    manifest = null;
  }

  return {
    get state() {
      return state;
    },
    get manifest() {
      return manifest;
    },
    get errorCode() {
      return errorCode;
    },
    get dialogOpen() {
      return dialogOpen;
    },
    get alreadyInstalled() {
      return alreadyInstalled;
    },
    handleInstallUrl,
    confirm,
    cancel,
  };
}
