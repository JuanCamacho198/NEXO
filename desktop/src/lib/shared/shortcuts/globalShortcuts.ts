import { listen } from '@tauri-apps/api/event';
import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
import { helpState } from './helpState.svelte';
import { commandPaletteState } from './commandPaletteState.svelte';
import { toggleAppFullscreen } from './fullscreen';
import { toggleTheme } from '$lib/shared/stores/theme';
import {
  SHORTCUT_REGISTRY,
  getShortcutEntry,
  matchesBinding,
  resolveBinding,
  type ShortcutEntry,
  type ShortcutPlatform,
} from './registry';
import { getShortcutPlatform } from './platform';
import {
  COMMAND_ACTION_BY_HANDLER,
  COMMAND_REGISTRY,
  defaultCommandPaletteActions,
  type CommandEntry,
  type CommandPaletteActions,
} from './commands';

/** Event the Rust menu emits, with a frontend command id as its payload. */
export const MENU_EVENT = 'nexo://menu-command';

export type GlobalShortcutActions = {
  toggleTheme: () => void;
  toggleAppFullscreen: () => void;
  toggleHelp: () => void;
  toggleCommandPalette: () => void;
};

export const defaultGlobalShortcutActions: GlobalShortcutActions = {
  toggleTheme: () => toggleTheme(),
  toggleAppFullscreen: () => void toggleAppFullscreen(),
  toggleHelp: () => helpState.toggle(),
  toggleCommandPalette: () => commandPaletteState.toggle(),
};

/** Which global registry rows the dispatcher is allowed to run. */
const GLOBAL_ACTION_BY_HANDLER: Record<string, keyof GlobalShortcutActions> = {
  'app.toggleTheme': 'toggleTheme',
  'app.fullscreen': 'toggleAppFullscreen',
  'app.toggleHelp': 'toggleHelp',
  'app.commandPalette': 'toggleCommandPalette',
};

export const GLOBAL_HANDLER_KEYS: readonly string[] = Object.keys(GLOBAL_ACTION_BY_HANDLER);

const HELP_ENTRY_ID = 'app-help';

/** A bits-ui dialog (or any `role="dialog"`) currently owns focus. */
export function hasOpenDialog(): boolean {
  if (typeof document === 'undefined') return false;
  return (
    document.querySelector(
      '[data-dialog-content][data-state="open"], [role="dialog"][data-state="open"]',
    ) !== null
  );
}

export type HandleGlobalOptions = {
  platform?: ShortcutPlatform;
  actions?: GlobalShortcutActions;
  isEditable?: (target: Element | null) => boolean;
  dialogOpen?: () => boolean;
};

/**
 * Resolve and run a global shortcut for a keydown event. Returns true when the
 * event was consumed. Pure enough to test: every collaborator can be injected.
 */
export function handleGlobalShortcut(
  event: KeyboardEvent,
  options: HandleGlobalOptions = {},
): boolean {
  const platform = options.platform ?? getShortcutPlatform();
  const actions = options.actions ?? defaultGlobalShortcutActions;
  const isEditable = options.isEditable ?? ((target) => hasEditableContext(target));
  const dialogOpen = options.dialogOpen ?? hasOpenDialog;

  if (isEditable(event.target as Element | null)) return false;

  const helpEntry = getShortcutEntry(HELP_ENTRY_ID);
  const isHelp =
    helpEntry !== undefined && matchesBinding(event, resolveBinding(helpEntry, platform), platform);

  if (isHelp) {
    // `?` toggles the help surface. It must still close itself while it is the
    // open dialog, but must not stack on top of an unrelated dialog.
    if (helpState.open) {
      event.preventDefault();
      actions.toggleHelp();
      return true;
    }
    if (!dialogOpen()) {
      event.preventDefault();
      actions.toggleHelp();
      return true;
    }
    return false;
  }

  if (dialogOpen()) return false;

  const entry = findMatchingGlobal(event, platform);
  if (!entry) return false;
  const action = GLOBAL_ACTION_BY_HANDLER[entry.handlerKey];
  if (!action) return false;

  event.preventDefault();
  actions[action]();
  return true;
}

function findMatchingGlobal(
  event: KeyboardEvent,
  platform: ShortcutPlatform,
): ShortcutEntry | undefined {
  return SHORTCUT_REGISTRY.filter((entry) => entry.context === 'global').find((entry) =>
    matchesBinding(event, resolveBinding(entry, platform), platform),
  );
}

/** Install the window-level listener. Returns the cleanup function. */
export function installGlobalShortcuts(options: HandleGlobalOptions = {}): () => void {
  const listener = (event: KeyboardEvent): void => {
    handleGlobalShortcut(event, options);
  };
  window.addEventListener('keydown', listener);
  return () => window.removeEventListener('keydown', listener);
}

/** Default `listen`: the Tauri event bridge. Only reached when a caller has no explicit `listen`. */
async function defaultListen(
  event: string,
  handler: (payload: string) => void,
): Promise<() => void> {
  const unlisten = await listen<string>(event, (e) => handler(e.payload));
  return unlisten;
}

/** The id a keyboard press for a handler would run, or `undefined` if it has none. */
export function shortcutIdFor(handlerKey: string): string | undefined {
  return SHORTCUT_REGISTRY.find((entry) => entry.handlerKey === handlerKey)?.id;
}

/**
 * Ids the native menu (`desktop/src-tauri/src/menu.rs`) emits.
 *
 * There are two id registries in the frontend and the menu spans both:
 * command-palette ids (`COMMAND_REGISTRY`) and global shortcut ids
 * (`SHORTCUT_REGISTRY`). `runAppCommand` resolves a palette id; `runShortcutId`
 * resolves a shortcut id. The menu emits whichever one owns the action, so a
 * menu row and its keybinding end in the same handler. The test
 * `menuCommandBridge.test.ts` pins which is which.
 */
export const MENU_COMMAND_IDS: readonly string[] = [
  'action-import-books',
  'nav-library',
  'nav-discover',
  'nav-settings',
];

/** Menu ids that live in `SHORTCUT_REGISTRY` rather than the palette. */
export const MENU_SHORTCUT_IDS: readonly string[] = [
  'app-toggle-dark',
  'app-help',
  'app-command-palette',
];

const COMMANDS_BY_ID = new Map<string, CommandEntry>(
  COMMAND_REGISTRY.map((entry) => [entry.id, entry]),
);

/**
 * Run one app command by id.
 *
 * This is the single dispatch point the native menu uses, and the keyboard
 * path routes to it as well (`installGlobalShortcuts` below), so a menu click
 * and its shortcut cannot diverge. Returns `false` for an id nothing owns,
 * which is how an unknown native-menu row is detected instead of silently
 * doing nothing.
 */
export function runAppCommand(
  id: string,
  actions: CommandPaletteActions = defaultCommandPaletteActions,
): boolean {
  const entry = COMMANDS_BY_ID.get(id);
  if (entry === undefined) return false;
  actions[COMMAND_ACTION_BY_HANDLER[entry.handlerKey]]();
  return true;
}

/**
 * Run a global shortcut's action by its registry id — the second half of the
 * menu bridge, for rows whose action exists only as a shortcut (opening the
 * command palette). Returns `false` when the row is not a wired global one.
 */
export function runShortcutId(
  id: string,
  actions: GlobalShortcutActions = defaultGlobalShortcutActions,
): boolean {
  const entry = getShortcutEntry(id);
  if (entry === undefined || entry.context !== 'global') return false;
  const action = GLOBAL_ACTION_BY_HANDLER[entry.handlerKey];
  if (action === undefined) return false;
  actions[action]();
  return true;
}

/** Run any menu id through whichever registry owns it. */
export function runMenuCommand(
  id: string,
  palette: CommandPaletteActions = defaultCommandPaletteActions,
  shortcuts: GlobalShortcutActions = defaultGlobalShortcutActions,
): boolean {
  return runAppCommand(id, palette) || runShortcutId(id, shortcuts);
}

export type MenuCommandOptions = {
  palette?: CommandPaletteActions;
  shortcuts?: GlobalShortcutActions;
  /** Install listener. Defaults to Tauri's `listen`. */
  listen?: (event: string, handler: (payload: string) => void) => Promise<() => void>;
};

/**
 * Subscribe to the native menu's command events. The Rust side forwards the
 * menu id unchanged; `runMenuCommand` resolves it against the palette registry
 * and the shortcut registry, so a menu row runs the same handler as its
 * keybinding. Resolves to the cleanup function. Never rejects: a non-Tauri host
 * has no menu, and the keyboard path is unaffected.
 */
export async function installMenuCommands(options: MenuCommandOptions = {}): Promise<() => void> {
  const palette = options.palette ?? defaultCommandPaletteActions;
  const shortcuts = options.shortcuts ?? defaultGlobalShortcutActions;
  const listenImpl = options.listen ?? defaultListen;
  try {
    return await listenImpl(MENU_EVENT, (id) => {
      runMenuCommand(id, palette, shortcuts);
    });
  } catch {
    return () => {};
  }
}
