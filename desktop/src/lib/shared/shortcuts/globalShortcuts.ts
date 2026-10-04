import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
import { helpState } from './helpState.svelte';
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

export type GlobalShortcutActions = {
  toggleTheme: () => void;
  toggleAppFullscreen: () => void;
  toggleHelp: () => void;
};

export const defaultGlobalShortcutActions: GlobalShortcutActions = {
  toggleTheme: () => toggleTheme(),
  toggleAppFullscreen: () => void toggleAppFullscreen(),
  toggleHelp: () => helpState.toggle(),
};

/** Which global registry rows the dispatcher is allowed to run. */
const GLOBAL_ACTION_BY_HANDLER: Record<string, keyof GlobalShortcutActions> = {
  'app.toggleTheme': 'toggleTheme',
  'app.fullscreen': 'toggleAppFullscreen',
  'app.toggleHelp': 'toggleHelp',
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
