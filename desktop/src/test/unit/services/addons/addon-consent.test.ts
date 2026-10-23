/**
 * Addon consent model (slice 8, task 8.12): the fail-closed per-addon gate.
 *
 * A fresh addon has no consent; consent is per addon (granting one leaves
 * the other absent); grant/revoke persist through the store port; and the
 * state survives a "restart" (a new service over the same store) — including
 * a withdrawal, which re-blocks immediately.
 */
import { describe, expect, it, vi } from 'vitest';

import {
  AddonConsentService,
  InMemoryAddonConsentStore,
} from '$lib/shared/services/addons/AddonConsent';

describe('AddonConsentService (fail-closed per-addon gate)', () => {
  it('a fresh addon has no consent (absent ⇒ denied by default)', async () => {
    const service = new AddonConsentService(new InMemoryAddonConsentStore());
    await service.ensureLoaded();
    expect(service.hasConsent('a1b2c3d4e5f60718')).toBe(false);
  });

  it('the gate stays fail-closed before hydration (unknown/unhydrated ⇒ false)', () => {
    const service = new AddonConsentService(new InMemoryAddonConsentStore());
    expect(service.hasConsent('a1b2c3d4e5f60718')).toBe(false);
  });

  it('consent is per addon: granting one leaves the other absent', async () => {
    const service = new AddonConsentService(new InMemoryAddonConsentStore());
    await service.grant('a1b2c3d4e5f60718');
    expect(service.hasConsent('a1b2c3d4e5f60718')).toBe(true);
    expect(service.hasConsent('b1b2c3d4e5f60718')).toBe(false);
  });

  it('grant/revoke persist through the store port', async () => {
    const store = new InMemoryAddonConsentStore();
    const setSpy = vi.spyOn(store, 'set');
    const service = new AddonConsentService(store);

    await service.grant('a1b2c3d4e5f60718');
    expect(setSpy).toHaveBeenLastCalledWith('a1b2c3d4e5f60718', true);
    expect(service.hasConsent('a1b2c3d4e5f60718')).toBe(true);

    await service.revoke('a1b2c3d4e5f60718');
    expect(setSpy).toHaveBeenLastCalledWith('a1b2c3d4e5f60718', false);
    expect(service.hasConsent('a1b2c3d4e5f60718')).toBe(false);
  });

  it('state survives a restart: a new service over the same store keeps the grant', async () => {
    const store = new InMemoryAddonConsentStore();
    const before = new AddonConsentService(store);
    await before.grant('a1b2c3d4e5f60718');

    const after = new AddonConsentService(store);
    expect(after.hasConsent('a1b2c3d4e5f60718')).toBe(false);
    await after.ensureLoaded();
    expect(after.hasConsent('a1b2c3d4e5f60718')).toBe(true);
  });

  it('a withdrawal survives a restart and re-blocks immediately', async () => {
    const store = new InMemoryAddonConsentStore();
    const before = new AddonConsentService(store);
    await before.grant('a1b2c3d4e5f60718');
    await before.revoke('a1b2c3d4e5f60718');

    const after = new AddonConsentService(store);
    await after.ensureLoaded();
    expect(after.hasConsent('a1b2c3d4e5f60718')).toBe(false);
  });

  it('ensureLoaded is memoized: concurrent and repeated calls share one store read', async () => {
    const store = new InMemoryAddonConsentStore();
    const listSpy = vi.spyOn(store, 'list');
    const service = new AddonConsentService(store);

    await Promise.all([service.ensureLoaded(), service.ensureLoaded(), service.ensureLoaded()]);
    await service.ensureLoaded();
    expect(listSpy).toHaveBeenCalledTimes(1);
  });
});

/**
 * `library_meta` is the disclosure that lets a metadata addon be asked about
 * books the reader already owns. It is a SECOND scope on purpose: the network
 * grant must not imply it, it must not imply the network grant, and nothing a
 * collection installs may set it.
 */
describe('AddonConsentService library_meta scope', () => {
  const ADDON = 'a1b2c3d4e5f60718';

  it('defaults off even when the addon is installed and consumed', async () => {
    const service = new AddonConsentService(new InMemoryAddonConsentStore());
    await service.ensureLoaded();
    expect(service.hasLibraryMetaConsent(ADDON)).toBe(false);
  });

  it('a stream grant never implies library_meta', async () => {
    const service = new AddonConsentService(new InMemoryAddonConsentStore());
    await service.grant(ADDON);
    expect(service.hasConsent(ADDON)).toBe(true);
    expect(service.hasLibraryMetaConsent(ADDON)).toBe(false);
  });

  it('a library_meta grant never implies stream', async () => {
    const service = new AddonConsentService(new InMemoryAddonConsentStore());
    await service.grantLibraryMeta(ADDON);
    expect(service.hasLibraryMetaConsent(ADDON)).toBe(true);
    expect(service.hasConsent(ADDON)).toBe(false);
  });

  it('grant and withdraw persist under the library_meta scope only', async () => {
    const store = new InMemoryAddonConsentStore();
    const setSpy = vi.spyOn(store, 'set');
    const service = new AddonConsentService(store);

    await service.grantLibraryMeta(ADDON);
    expect(setSpy).toHaveBeenLastCalledWith(ADDON, true, 'library_meta');
    expect(service.hasLibraryMetaConsent(ADDON)).toBe(true);

    await service.revokeLibraryMeta(ADDON);
    expect(setSpy).toHaveBeenLastCalledWith(ADDON, false, 'library_meta');
    expect(service.hasLibraryMetaConsent(ADDON)).toBe(false);
    // The stream scope was never written.
    expect(setSpy.mock.calls.every((call) => call[2] === 'library_meta')).toBe(true);
  });

  it('the library_meta grant survives a restart', async () => {
    const store = new InMemoryAddonConsentStore();
    await new AddonConsentService(store).grantLibraryMeta(ADDON);

    const restarted = new AddonConsentService(store);
    expect(restarted.hasLibraryMetaConsent(ADDON)).toBe(false);
    await restarted.ensureLoaded();
    expect(restarted.hasLibraryMetaConsent(ADDON)).toBe(true);
    expect(restarted.hasConsent(ADDON)).toBe(false);
  });

  it('forget clears both scopes, so a reinstall inherits neither', async () => {
    const store = new InMemoryAddonConsentStore();
    const service = new AddonConsentService(store);
    await service.grant(ADDON);
    await service.grantLibraryMeta(ADDON);

    await service.forget(ADDON);
    expect(service.hasConsent(ADDON)).toBe(false);
    expect(service.hasLibraryMetaConsent(ADDON)).toBe(false);

    const restarted = new AddonConsentService(store);
    await restarted.ensureLoaded();
    expect(restarted.hasConsent(ADDON)).toBe(false);
    expect(restarted.hasLibraryMetaConsent(ADDON)).toBe(false);
  });
});
