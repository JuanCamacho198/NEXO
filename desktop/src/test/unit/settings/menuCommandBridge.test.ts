import { describe, expect, it, vi } from 'vitest';
import {
  MENU_COMMAND_IDS,
  MENU_SHORTCUT_IDS,
  installMenuCommands,
  runAppCommand,
  runMenuCommand,
  runShortcutId,
  type GlobalShortcutActions,
} from '$lib/shared/shortcuts/globalShortcuts';
import {
  COMMAND_REGISTRY,
  defaultCommandPaletteActions,
  type CommandPaletteActions,
} from '$lib/shared/shortcuts/commands';
import { SHORTCUT_REGISTRY } from '$lib/shared/shortcuts/registry';

function spyPalette(): CommandPaletteActions {
  return Object.fromEntries(
    Object.keys(defaultCommandPaletteActions).map((key) => [key, vi.fn()]),
  ) as unknown as CommandPaletteActions;
}

function spyShortcuts(): GlobalShortcutActions {
  return {
    toggleTheme: vi.fn(),
    toggleAppFullscreen: vi.fn(),
    toggleHelp: vi.fn(),
    toggleCommandPalette: vi.fn(),
  };
}

describe('native menu command bridge', () => {
  // The Rust menu emits these ids verbatim (`desktop/src-tauri/src/menu.rs`,
  // `APP_COMMANDS`). If a row is renamed on either side this fails instead of
  // producing a dead menu click.
  it('every palette id the Rust menu emits exists in COMMAND_REGISTRY', () => {
    const paletteIds = new Set(COMMAND_REGISTRY.map((entry) => entry.id));
    for (const id of MENU_COMMAND_IDS) {
      expect(paletteIds.has(id), `${id} is not in COMMAND_REGISTRY`).toBe(true);
    }
  });

  it('every shortcut id the Rust menu emits is a wired global shortcut', () => {
    for (const id of MENU_SHORTCUT_IDS) {
      const entry = SHORTCUT_REGISTRY.find((e) => e.id === id);
      expect(entry, `${id} is not in SHORTCUT_REGISTRY`).toBeDefined();
      // Only global rows are reachable from a menu: a reader-context row would
      // need the reader focused, which a menu click cannot assume.
      expect(entry!.context, id).toBe('global');
    }
  });

  it('matches the Rust APP_COMMANDS list exactly', () => {
    // Same order as `APP_COMMANDS` in menu.rs.
    expect([...MENU_COMMAND_IDS, ...MENU_SHORTCUT_IDS]).toEqual([
      'action-import-books',
      'nav-library',
      'nav-discover',
      'nav-settings',
      'app-toggle-dark',
      'app-help',
      'app-command-palette',
    ]);
  });

  it('resolves each menu id to the action its keybinding runs', () => {
    // Menu row -> the shortcut id for the same action. Both must end at the
    // same named action, which is the anti-divergence guarantee.
    const cases: [string, string, keyof GlobalShortcutActions][] = [
      ['app-toggle-dark', 'app-toggle-dark', 'toggleTheme'],
      ['app-help', 'app-help', 'toggleHelp'],
      ['app-command-palette', 'app-command-palette', 'toggleCommandPalette'],
    ];
    for (const [menuId, shortcutId, expected] of cases) {
      const shortcuts = spyShortcuts();
      expect(runShortcutId(menuId, shortcuts), menuId).toBe(true);
      expect(shortcuts[expected], `${menuId} -> ${expected}`).toHaveBeenCalledTimes(1);
      expect(shortcutId).toBe(menuId);
    }
  });

  it('dispatches a palette menu id to its action', () => {
    const palette = spyPalette();
    expect(runAppCommand('nav-library', palette)).toBe(true);
    expect(palette.navigateToLibrary).toHaveBeenCalledTimes(1);
    expect(palette.navigateToSettings).not.toHaveBeenCalled();
  });

  it('reports unknown ids instead of silently doing nothing', () => {
    const palette = spyPalette();
    const shortcuts = spyShortcuts();
    expect(runMenuCommand('nav-does-not-exist', palette, shortcuts)).toBe(false);
    for (const fn of Object.values({ ...palette, ...shortcuts })) {
      expect(fn).not.toHaveBeenCalled();
    }
  });

  it('routes every id the menu can emit to exactly one action', () => {
    for (const id of MENU_COMMAND_IDS) {
      const palette = spyPalette();
      const shortcuts = spyShortcuts();
      expect(runMenuCommand(id, palette, shortcuts), id).toBe(true);
      const called = [
        ...Object.values(palette),
        ...Object.values(shortcuts),
      ].filter((fn) => (fn as ReturnType<typeof vi.fn>).mock.calls.length > 0);
      expect(called, id).toHaveLength(1);
    }
    for (const id of MENU_SHORTCUT_IDS) {
      const palette = spyPalette();
      const shortcuts = spyShortcuts();
      expect(runMenuCommand(id, palette, shortcuts), id).toBe(true);
      const called = [
        ...Object.values(palette),
        ...Object.values(shortcuts),
      ].filter((fn) => (fn as ReturnType<typeof vi.fn>).mock.calls.length > 0);
      expect(called, id).toHaveLength(1);
    }
  });

  it('subscribes to the menu event and cleans up', async () => {
    const palette = spyPalette();
    const shortcuts = spyShortcuts();
    const unlisten = vi.fn();
    let captured: ((payload: string) => void) | undefined;
    let capturedEvent: string | undefined;

    const cleanup = await installMenuCommands({
      palette,
      shortcuts,
      listen: (event, handler) => {
        capturedEvent = event;
        captured = handler;
        return Promise.resolve(unlisten);
      },
    });

    expect(capturedEvent).toBe('nexo://menu-command');
    captured!('app-toggle-dark');
    expect(shortcuts.toggleTheme).toHaveBeenCalledTimes(1);
    captured!('nav-settings');
    expect(palette.navigateToSettings).toHaveBeenCalledTimes(1);

    cleanup();
    expect(unlisten).toHaveBeenCalledTimes(1);
  });

  it('resolves to a no-op cleanup when the host has no event bridge', async () => {
    const cleanup = await installMenuCommands({
      listen: () => Promise.reject(new Error('not tauri')),
    });
    expect(() => cleanup()).not.toThrow();
  });
});
