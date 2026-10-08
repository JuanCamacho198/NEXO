import { describe, expect, it, vi } from 'vitest';
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
import { WIRED_COMMAND_KEYS, WIRED_HANDLER_KEYS } from '$lib/shared/shortcuts/handlers';
import {
  COMMAND_ACTION_BY_HANDLER,
  COMMAND_GROUPS,
  COMMAND_REGISTRY,
  runCommand,
  type CommandPaletteActions,
} from '$lib/shared/shortcuts/commands';
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
  it('documents the Ctrl+K palette as global and `/` as the field search', () => {
    const palette = getShortcutEntry('app-command-palette');
    expect(palette?.handlerKey).toBe('app.commandPalette');
    expect(palette?.context).toBe('global');
    expect(palette?.descriptionKey).toBe('settings.shortcuts.commandPalette');
    expect(palette?.combos.default.keys).toEqual(['k']);

    const fieldSearch = SHORTCUT_REGISTRY.find((entry) => entry.id === 'library-search');
    expect(fieldSearch?.descriptionKey).toBe('settings.shortcuts.focusSearch');
    expect(fieldSearch?.combos.default.keys).toEqual(['/']);
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

function makeCommandActions(): CommandPaletteActions {
  return {
    navigateToHome: vi.fn(),
    navigateToLibrary: vi.fn(),
    navigateToDiscover: vi.fn(),
    navigateToAddons: vi.fn(),
    navigateToStats: vi.fn(),
    navigateToHighlights: vi.fn(),
    navigateToSettings: vi.fn(),
    navigateToDictionary: vi.fn(),
    navigateToStorage: vi.fn(),
    navigateToSync: vi.fn(),
    importBooks: vi.fn(),
    syncNow: vi.fn(),
    toggleTheme: vi.fn(),
    toggleFullscreen: vi.fn(),
    showShortcuts: vi.fn(),
  };
}

describe('command palette registry — every row runs something real', () => {
  it('every row points at a handler in the command manifest', () => {
    for (const entry of COMMAND_REGISTRY) {
      expect(
        WIRED_COMMAND_KEYS.has(entry.handlerKey),
        `command "${entry.id}" names handler "${entry.handlerKey}" which is not wired`,
      ).toBe(true);
    }
  });

  it('every manifest handler resolves to an action that runs exactly once', () => {
    for (const entry of COMMAND_REGISTRY) {
      const actions = makeCommandActions();
      runCommand(entry, actions);
      const called = Object.entries(actions)
        .filter(([, fn]) => vi.mocked(fn).mock.calls.length > 0)
        .map(([name]) => name);
      expect(called, `command "${entry.id}" must run exactly one action`).toEqual([
        COMMAND_ACTION_BY_HANDLER[entry.handlerKey],
      ]);
    }
  });

  it('every command group declares a label and is used by at least one row', () => {
    for (const group of COMMAND_GROUPS) {
      expect(group.labelKey).toBeTruthy();
      expect(COMMAND_REGISTRY.some((entry) => entry.group === group.id)).toBe(true);
    }
  });
});
