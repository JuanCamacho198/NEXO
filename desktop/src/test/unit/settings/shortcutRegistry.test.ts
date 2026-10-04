import { describe, expect, it } from 'vitest';
import {
  SHORTCUT_CONTEXT_LABELS,
  SHORTCUT_GROUPS,
  SHORTCUT_REGISTRY,
  bindingToCaps,
  getShortcutEntry,
  matchesBinding,
  resolveBinding,
  type ShortcutBinding,
  type ShortcutPlatform,
} from '$lib/shared/shortcuts/registry';
import { WIRED_HANDLER_KEYS } from '$lib/shared/shortcuts/handlers';
import { GLOBAL_HANDLER_KEYS, handleGlobalShortcut } from '$lib/shared/shortcuts/globalShortcuts';

function eventFor(binding: ShortcutBinding, platform: ShortcutPlatform): KeyboardEvent {
  const init: KeyboardEventInit = {
    key: binding.keys[0],
    ctrlKey: false,
    metaKey: false,
    shiftKey: false,
    altKey: false,
  };
  if (binding.mod) {
    if (platform === 'mac') init.metaKey = true;
    else init.ctrlKey = true;
  }
  if (binding.shift) init.shiftKey = true;
  if (binding.alt) init.altKey = true;
  return new KeyboardEvent('keydown', init);
}

describe('shortcut registry — coherence with real handlers', () => {
  it('every listed row points at a handler that exists', () => {
    for (const entry of SHORTCUT_REGISTRY) {
      expect(
        WIRED_HANDLER_KEYS.has(entry.handlerKey),
        `row "${entry.id}" names handler "${entry.handlerKey}" which is not wired`,
      ).toBe(true);
    }
  });

  it('every global row is runnable by the global dispatcher', () => {
    for (const entry of SHORTCUT_REGISTRY.filter((row) => row.context === 'global')) {
      expect(
        GLOBAL_HANDLER_KEYS.includes(entry.handlerKey),
        `global row "${entry.id}" has no dispatcher action`,
      ).toBe(true);
    }
  });

  it('every row declares a binding per platform, per group and a known context', () => {
    for (const entry of SHORTCUT_REGISTRY) {
      expect(entry.combos.default.keys.length).toBeGreaterThan(0);
      expect(entry.combos.mac.keys.length).toBeGreaterThan(0);
      expect(SHORTCUT_GROUPS.map((g) => g.id)).toContain(entry.group);
      expect(SHORTCUT_CONTEXT_LABELS[entry.context]).toBeTruthy();
    }
  });

  it('every group id is used by at least one row', () => {
    for (const group of SHORTCUT_GROUPS) {
      expect(SHORTCUT_REGISTRY.some((row) => row.group === group.id)).toBe(true);
    }
  });
});

describe('shortcut binding matcher', () => {
  it('round-trips every registry combo on both platforms', () => {
    for (const entry of SHORTCUT_REGISTRY) {
      for (const platform of ['default', 'mac'] as const) {
        const binding = resolveBinding(entry, platform);
        const event = eventFor(binding, platform);
        expect(
          matchesBinding(event, binding, platform),
          `row "${entry.id}" (${platform}) does not match its own binding`,
        ).toBe(true);
      }
    }
  });

  it('does not match a wrong key', () => {
    for (const entry of SHORTCUT_REGISTRY) {
      const binding = resolveBinding(entry, 'default');
      const event = eventFor(binding, 'default');
      const wrong = new KeyboardEvent('keydown', {
        key: 'F13',
        ctrlKey: event.ctrlKey,
        metaKey: event.metaKey,
        shiftKey: event.shiftKey,
        altKey: event.altKey,
      });
      expect(matchesBinding(wrong, binding, 'default')).toBe(false);
    }
  });

  it('requires the modifier when the binding declares one', () => {
    const entry = getShortcutEntry('app-toggle-dark');
    expect(entry).toBeDefined();
    const binding = resolveBinding(entry!, 'default');
    const withoutMod = new KeyboardEvent('keydown', { key: 'd' });
    expect(matchesBinding(withoutMod, binding, 'default')).toBe(false);
    const withMod = new KeyboardEvent('keydown', { key: 'd', ctrlKey: true });
    expect(matchesBinding(withMod, binding, 'default')).toBe(true);
  });

  it('uses Cmd on macOS and Ctrl on the default platform', () => {
    const entry = getShortcutEntry('app-toggle-dark')!;
    const binding = resolveBinding(entry, 'mac');
    expect(
      matchesBinding(new KeyboardEvent('keydown', { key: 'd', metaKey: true }), binding, 'mac'),
    ).toBe(true);
    expect(
      matchesBinding(new KeyboardEvent('keydown', { key: 'd', ctrlKey: true }), binding, 'mac'),
    ).toBe(false);
  });

  it('renders the modifier cap per platform', () => {
    const entry = getShortcutEntry('app-toggle-dark')!;
    expect(bindingToCaps(resolveBinding(entry, 'default'), 'default')).toEqual(['Ctrl', 'D']);
    expect(bindingToCaps(resolveBinding(entry, 'mac'), 'mac')).toEqual(['⌘', 'D']);
  });
});

describe('registry truth (regression guards)', () => {
  it('does not list a command palette while none exists', () => {
    const row = SHORTCUT_REGISTRY.find((entry) => entry.id === 'library-search');
    expect(row?.descriptionKey).toBe('settings.shortcuts.focusSearch');
    expect(row?.combos.default.keys).toEqual(['k']);
  });

  it('lists the real reader fullscreen key and the app window fullscreen separately', () => {
    expect(getShortcutEntry('reader-toggle-fullscreen')?.combos.default.keys).toEqual(['f']);
    expect(getShortcutEntry('app-fullscreen')?.combos.default.keys).toEqual(['F11']);
  });

  it('scopes vertical scroll to the PDF viewer', () => {
    expect(getShortcutEntry('reader-scroll-up')?.context).toBe('reader-pdf');
    expect(getShortcutEntry('reader-scroll-down')?.context).toBe('reader-pdf');
  });

  it('exposes no-op-safe global handling for non-matching keys', () => {
    expect(handleGlobalShortcut(new KeyboardEvent('keydown', { key: 'q' }))).toBe(false);
  });
});
