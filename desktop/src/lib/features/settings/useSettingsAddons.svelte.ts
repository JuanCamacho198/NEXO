import { pushToast as defaultPushToast } from '$lib/shared/stores/ToastQueue.svelte';
import type { MessageKey } from '$lib/shared/i18n';
import {
  AddonRegistry as DefaultAddonRegistry,
  type InstalledAddonRow,
} from '$lib/shared/services/addons/AddonRegistry';
import { AddonFetchError, AddonFetchErrorCode } from '@nexo/manifest-validator';

export type AddonsDeps = {
  registry?: {
    listInstalled(): Promise<InstalledAddonRow[]>;
    install(url: string): Promise<unknown>;
    setEnabled(id: string, enabled: boolean): Promise<void>;
    uninstall(id: string): Promise<void>;
    /** Registry-mutation hook: fires after install/enable/disable/uninstall. */
    onChanged?: (listener: (version: number) => void) => () => void;
  };
  /** Called when the registry mutates so the live Discover composite rebuilds. */
  onAddonsChanged?: () => void;
  pushToast?: typeof defaultPushToast;
  t?: (key: MessageKey, params?: Record<string, string | number>) => string;
};

/** Inline install state for the Addons screen (Settings keeps its toast). */
export type InstallOutcome =
  | { kind: 'idle' }
  | { kind: 'installing' }
  | { kind: 'error'; code?: AddonFetchErrorCode }
  | { kind: 'offline' };

export function createSettingsAddons(deps: AddonsDeps = {}): {
  url: string;
  installed: InstalledAddonRow[];
  isBusy: boolean;
  installOutcome: InstallOutcome;
  refresh: () => Promise<void>;
  handleInstall: () => Promise<void>;
  handleToggle: (id: string, enabled: boolean) => Promise<void>;
  handleUninstall: (id: string) => Promise<void>;
} {
  const registry = deps.registry ?? new DefaultAddonRegistry();
  // t/pushToast resolve PER CALL (not once at construction) so the app-wide
  // singleton (created at module load) still uses the mounted surface's
  // translator and toast host.
  const translate = (key: MessageKey, params?: Record<string, string | number>): string =>
    (deps.t ?? ((k: MessageKey) => k))(key, params);
  const notify = (kind: 'success' | 'error', message: string): void =>
    (deps.pushToast ?? defaultPushToast)(kind, message);
  registry.onChanged?.(() => deps.onAddonsChanged?.());

  let url = $state('');
  let installed = $state<InstalledAddonRow[]>([]);
  let isBusy = $state(false);
  let installOutcome = $state<InstallOutcome>({ kind: 'idle' });

  async function refresh(): Promise<void> {
    try {
      installed = await registry.listInstalled();
    } catch (e) {
      // A registry read failure must surface through the existing toast
      // plumbing, never escape as an unhandled rejection from a mount effect.
      notify('error', e instanceof Error ? e.message : translate('settings.addons.installFailed'));
    }
  }

  async function handleInstall(): Promise<void> {
    const target = url.trim();
    if (target === '') return;
    // Fail fast when offline: no registry I/O is attempted.
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      installOutcome = { kind: 'offline' };
      notify('error', translate('settings.addons.installFailed'));
      return;
    }
    isBusy = true;
    installOutcome = { kind: 'installing' };
    try {
      await registry.install(target);
      url = '';
      installOutcome = { kind: 'idle' };
      await refresh();
      notify('success', translate('settings.addons.installedToast'));
    } catch (e) {
      const code = e instanceof AddonFetchError ? e.code : undefined;
      installOutcome =
        code === AddonFetchErrorCode.NETWORK ? { kind: 'offline' } : { kind: 'error', code };
      notify('error', e instanceof Error ? e.message : translate('settings.addons.installFailed'));
    } finally {
      isBusy = false;
    }
  }

  async function handleToggle(id: string, enabled: boolean): Promise<void> {
    isBusy = true;
    try {
      await registry.setEnabled(id, enabled);
      await refresh();
    } finally {
      isBusy = false;
    }
  }

  async function handleUninstall(id: string): Promise<void> {
    isBusy = true;
    try {
      await registry.uninstall(id);
      await refresh();
    } finally {
      isBusy = false;
    }
  }

  return {
    get url() {
      return url;
    },
    set url(v: string) {
      url = v;
    },
    get installed() {
      return installed;
    },
    get isBusy() {
      return isBusy;
    },
    get installOutcome() {
      return installOutcome;
    },
    refresh,
    handleInstall,
    handleToggle,
    handleUninstall,
  };
}
