import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  defaultGlobalShortcutActions,
  handleGlobalShortcut,
  installGlobalShortcuts,
  type GlobalShortcutActions,
} from '$lib/shared/shortcuts/globalShortcuts';
import { helpState } from '$lib/shared/shortcuts/helpState.svelte';
import { commandPaletteState } from '$lib/shared/shortcuts/commandPaletteState.svelte';

function makeActions(): GlobalShortcutActions {
  return {
    toggleTheme: vi.fn(),
    toggleAppFullscreen: vi.fn(),
    toggleHelp: vi.fn(),
    toggleCommandPalette: vi.fn(),
  };
}

describe('global shortcuts dispatcher', () => {
  beforeEach(() => {
    helpState.hide();
    commandPaletteState.hide();
  });

  it('runs Ctrl+D to toggle the theme', () => {
    const actions = makeActions();
    const handled = handleGlobalShortcut(
      new KeyboardEvent('keydown', { key: 'd', ctrlKey: true }),
      {
        actions,
        platform: 'default',
      },
    );
    expect(handled).toBe(true);
    expect(actions.toggleTheme).toHaveBeenCalledTimes(1);
  });

  it('runs ⌘D on macOS', () => {
    const actions = makeActions();
    const handled = handleGlobalShortcut(
      new KeyboardEvent('keydown', { key: 'd', metaKey: true }),
      {
        actions,
        platform: 'mac',
      },
    );
    expect(handled).toBe(true);
    expect(actions.toggleTheme).toHaveBeenCalledTimes(1);
  });

  it('does not run Ctrl+D on macOS (Cmd is required there)', () => {
    const actions = makeActions();
    const handled = handleGlobalShortcut(
      new KeyboardEvent('keydown', { key: 'd', ctrlKey: true }),
      {
        actions,
        platform: 'mac',
      },
    );
    expect(handled).toBe(false);
    expect(actions.toggleTheme).not.toHaveBeenCalled();
  });

  it('runs F11 as the window fullscreen toggle', () => {
    const actions = makeActions();
    const handled = handleGlobalShortcut(new KeyboardEvent('keydown', { key: 'F11' }), {
      actions,
      platform: 'default',
    });
    expect(handled).toBe(true);
    expect(actions.toggleAppFullscreen).toHaveBeenCalledTimes(1);
  });

  it('opens and closes the help surface with ?', () => {
    const first = handleGlobalShortcut(new KeyboardEvent('keydown', { key: '?' }), {
      actions: defaultGlobalShortcutActions,
      platform: 'default',
      dialogOpen: () => false,
    });
    expect(first).toBe(true);
    expect(helpState.open).toBe(true);

    const second = handleGlobalShortcut(new KeyboardEvent('keydown', { key: '?' }), {
      actions: defaultGlobalShortcutActions,
      platform: 'default',
      dialogOpen: () => true,
    });
    expect(second).toBe(true);
    expect(helpState.open).toBe(false);
  });

  it('opens the command palette with Ctrl+K and ⌘K', () => {
    const actions = makeActions();
    const handled = handleGlobalShortcut(
      new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }),
      {
        actions,
        platform: 'default',
        dialogOpen: () => false,
      },
    );
    expect(handled).toBe(true);
    expect(actions.toggleCommandPalette).toHaveBeenCalledTimes(1);

    const macActions = makeActions();
    const macHandled = handleGlobalShortcut(
      new KeyboardEvent('keydown', { key: 'k', metaKey: true }),
      {
        actions: macActions,
        platform: 'mac',
        dialogOpen: () => false,
      },
    );
    expect(macHandled).toBe(true);
    expect(macActions.toggleCommandPalette).toHaveBeenCalledTimes(1);
  });

  it('toggles the command palette closed again with Ctrl+K', () => {
    const first = handleGlobalShortcut(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }), {
      actions: defaultGlobalShortcutActions,
      platform: 'default',
      dialogOpen: () => false,
    });
    expect(first).toBe(true);
    expect(commandPaletteState.open).toBe(true);

    commandPaletteState.hide();
    expect(commandPaletteState.open).toBe(false);
  });

  it('does not open the command palette on top of another dialog', () => {
    const handled = handleGlobalShortcut(
      new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }),
      {
        actions: defaultGlobalShortcutActions,
        platform: 'default',
        dialogOpen: () => true,
      },
    );
    expect(handled).toBe(false);
    expect(commandPaletteState.open).toBe(false);
  });

  it('never opens the command palette while the user types in a field', () => {
    const element = document.createElement('input');
    document.body.appendChild(element);
    const uninstall = installGlobalShortcuts({
      actions: defaultGlobalShortcutActions,
      platform: 'default',
    });

    element.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));

    expect(commandPaletteState.open).toBe(false);
    uninstall();
    element.remove();
  });

  it('never fires while the user types in an input', () => {
    const actions = makeActions();
    const element = document.createElement('input');
    document.body.appendChild(element);
    const uninstall = installGlobalShortcuts({ actions, platform: 'default' });

    element.dispatchEvent(new KeyboardEvent('keydown', { key: 'd', ctrlKey: true, bubbles: true }));

    expect(actions.toggleTheme).not.toHaveBeenCalled();
    uninstall();
    element.remove();
  });

  it('never fires while a dialog owns focus', () => {
    const actions = makeActions();
    const handled = handleGlobalShortcut(
      new KeyboardEvent('keydown', { key: 'd', ctrlKey: true }),
      {
        actions,
        platform: 'default',
        dialogOpen: () => true,
      },
    );
    expect(handled).toBe(false);
    expect(actions.toggleTheme).not.toHaveBeenCalled();
  });

  it('does not open the help surface on top of another dialog', () => {
    const handled = handleGlobalShortcut(new KeyboardEvent('keydown', { key: '?' }), {
      actions: defaultGlobalShortcutActions,
      platform: 'default',
      dialogOpen: () => true,
    });
    expect(handled).toBe(false);
    expect(helpState.open).toBe(false);
  });

  it('ignores unrelated keys', () => {
    expect(
      handleGlobalShortcut(new KeyboardEvent('keydown', { key: 'q' }), {
        actions: makeActions(),
        platform: 'default',
      }),
    ).toBe(false);
  });

  it('installs and removes its window listener', () => {
    const actions = makeActions();
    const uninstall = installGlobalShortcuts({ actions, platform: 'default' });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'd', ctrlKey: true }));
    expect(actions.toggleTheme).toHaveBeenCalledTimes(1);
    uninstall();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'd', ctrlKey: true }));
    expect(actions.toggleTheme).toHaveBeenCalledTimes(1);
  });
});
