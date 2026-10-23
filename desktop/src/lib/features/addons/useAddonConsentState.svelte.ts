/**
 * useAddonConsentState — consent snapshots + toggle handlers for the Addons
 * surfaces (slice 8, extended with the `library_meta` scope).
 *
 * The hook owns no persistence: every read/write goes through the injected
 * consent service (default: the production `addonConsent` singleton over the
 * durable Tauri store). `refresh(ids)` hydrates the snapshot once and then
 * re-reads it for the visible rows; the two toggle handlers grant/withdraw one
 * scope each and mirror the outcome into the snapshots so the controls reflect
 * the persisted state on the next render.
 *
 * The scopes are independent by construction: `handleConsentToggle` writes
 * `stream` only, `handleLibraryMetaToggle` writes `library_meta` only. Nothing
 * here derives one from the other.
 */
import {
  addonConsent,
  AddonConsentService,
  type AddonConsentScope,
} from '$lib/shared/services/addons/AddonConsent';

export type AddonConsentDeps = {
  consent?: AddonConsentService;
};

export function createAddonConsentState(deps: AddonConsentDeps = {}): {
  consentById: Record<string, boolean>;
  libraryMetaById: Record<string, boolean>;
  refresh: (ids: readonly string[]) => Promise<void>;
  handleConsentToggle: (id: string, granted: boolean) => Promise<void>;
  handleLibraryMetaToggle: (id: string, granted: boolean) => Promise<void>;
} {
  const consent = deps.consent ?? addonConsent;

  let consentById = $state<Record<string, boolean>>({});
  let libraryMetaById = $state<Record<string, boolean>>({});

  async function refresh(ids: readonly string[]): Promise<void> {
    if (ids.length === 0) {
      consentById = {};
      libraryMetaById = {};
      return;
    }
    await consent.ensureLoaded();
    const stream: Record<string, boolean> = {};
    const libraryMeta: Record<string, boolean> = {};
    for (const id of ids) {
      stream[id] = consent.hasConsent(id);
      libraryMeta[id] = consent.hasLibraryMetaConsent(id);
    }
    consentById = stream;
    libraryMetaById = libraryMeta;
  }

  async function toggle(scope: AddonConsentScope, id: string, granted: boolean): Promise<void> {
    if (scope === 'library_meta') {
      if (granted) await consent.grantLibraryMeta(id);
      else await consent.revokeLibraryMeta(id);
      libraryMetaById = { ...libraryMetaById, [id]: granted };
      return;
    }
    if (granted) await consent.grant(id);
    else await consent.revoke(id);
    consentById = { ...consentById, [id]: granted };
  }

  return {
    get consentById() {
      return consentById;
    },
    get libraryMetaById() {
      return libraryMetaById;
    },
    refresh,
    handleConsentToggle: (id: string, granted: boolean) => toggle('stream', id, granted),
    handleLibraryMetaToggle: (id: string, granted: boolean) => toggle('library_meta', id, granted),
  };
}
