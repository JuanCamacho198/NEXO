import type { MessageKey } from '$lib/shared/i18n';
import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
import { bulkImportState } from '$lib/shared/stores/BulkImportDomainState.svelte';
import { SyncService } from '$lib/shared/services/SyncService';
import { toggleTheme } from '$lib/shared/stores/theme';
import { toggleAppFullscreen } from './fullscreen';
import { helpState } from './helpState.svelte';
import type { CommandHandlerKey } from './handlers';

export type CommandGroupId = 'navigation' | 'actions' | 'view';

/**
 * The real effects a palette row can run. Same shape as `GlobalShortcutActions`:
 * the registry names a handler key, this map resolves it to a method, and the
 * method performs the action. Tests inject spies to prove every row runs one.
 */
export type CommandPaletteActions = {
  navigateToHome: () => void;
  navigateToLibrary: () => void;
  navigateToDiscover: () => void;
  navigateToAddons: () => void;
  navigateToStats: () => void;
  navigateToHighlights: () => void;
  navigateToSettings: () => void;
  navigateToDictionary: () => void;
  navigateToStorage: () => void;
  navigateToSync: () => void;
  importBooks: () => void;
  syncNow: () => void;
  toggleTheme: () => void;
  toggleFullscreen: () => void;
  showShortcuts: () => void;
};

export const defaultCommandPaletteActions: CommandPaletteActions = {
  navigateToHome: () => navigationState.navigateToHome(),
  navigateToLibrary: () => navigationState.navigateToLibrary(),
  navigateToDiscover: () => navigationState.navigateToDiscover(),
  navigateToAddons: () => navigationState.navigateToAddons(),
  navigateToStats: () => navigationState.navigateToStats(),
  navigateToHighlights: () => navigationState.navigateToHighlights(),
  navigateToSettings: () => navigationState.navigateToSettings(),
  navigateToDictionary: () => navigationState.navigateToDictionary(),
  navigateToStorage: () => navigationState.navigateToStorage(),
  navigateToSync: () => navigationState.navigateToSync(),
  importBooks: () => void bulkImportState.handleImportFile(),
  // The same call the Sync tab's "sync now" button makes.
  syncNow: () => void SyncService.syncMetadata(),
  toggleTheme: () => toggleTheme(),
  toggleFullscreen: () => void toggleAppFullscreen(),
  showShortcuts: () => helpState.show(),
};

/** Which actions method runs each command handler. */
export const COMMAND_ACTION_BY_HANDLER: Record<CommandHandlerKey, keyof CommandPaletteActions> = {
  'command.navigateHome': 'navigateToHome',
  'command.navigateLibrary': 'navigateToLibrary',
  'command.navigateDiscover': 'navigateToDiscover',
  'command.navigateAddons': 'navigateToAddons',
  'command.navigateStats': 'navigateToStats',
  'command.navigateHighlights': 'navigateToHighlights',
  'command.navigateSettings': 'navigateToSettings',
  'command.navigateDictionary': 'navigateToDictionary',
  'command.navigateStorage': 'navigateToStorage',
  'command.navigateSync': 'navigateToSync',
  'command.importBooks': 'importBooks',
  'command.syncNow': 'syncNow',
  'command.toggleTheme': 'toggleTheme',
  'command.toggleFullscreen': 'toggleFullscreen',
  'command.showShortcuts': 'showShortcuts',
};

export const COMMAND_HANDLER_KEYS: readonly string[] = Object.keys(COMMAND_ACTION_BY_HANDLER);

export const COMMAND_GROUPS: readonly { id: CommandGroupId; labelKey: MessageKey }[] = [
  { id: 'navigation', labelKey: 'commandPalette.group.navigation' },
  { id: 'actions', labelKey: 'commandPalette.group.actions' },
  { id: 'view', labelKey: 'commandPalette.group.view' },
];

export type CommandEntry = {
  id: string;
  handlerKey: CommandHandlerKey;
  group: CommandGroupId;
  descriptionKey: MessageKey;
};

/**
 * The palette's source of truth. Every row is a navigation action, an app
 * action, or a view toggle the app really performs — nothing decorative.
 */
export const COMMAND_REGISTRY: readonly CommandEntry[] = [
  // ── Navigation ───────────────────────────────────────────────────────
  {
    id: 'nav-home',
    handlerKey: 'command.navigateHome',
    group: 'navigation',
    descriptionKey: 'sidebar.home',
  },
  {
    id: 'nav-library',
    handlerKey: 'command.navigateLibrary',
    group: 'navigation',
    descriptionKey: 'sidebar.library',
  },
  {
    id: 'nav-discover',
    handlerKey: 'command.navigateDiscover',
    group: 'navigation',
    descriptionKey: 'sidebar.discover',
  },
  {
    id: 'nav-addons',
    handlerKey: 'command.navigateAddons',
    group: 'navigation',
    descriptionKey: 'sidebar.addons',
  },
  {
    id: 'nav-stats',
    handlerKey: 'command.navigateStats',
    group: 'navigation',
    descriptionKey: 'sidebar.stats',
  },
  {
    id: 'nav-highlights',
    handlerKey: 'command.navigateHighlights',
    group: 'navigation',
    descriptionKey: 'sidebar.highlights',
  },
  {
    id: 'nav-dictionary',
    handlerKey: 'command.navigateDictionary',
    group: 'navigation',
    descriptionKey: 'sidebar.dictionary',
  },
  {
    id: 'nav-settings',
    handlerKey: 'command.navigateSettings',
    group: 'navigation',
    descriptionKey: 'sidebar.settings',
  },
  {
    id: 'nav-storage',
    handlerKey: 'command.navigateStorage',
    group: 'navigation',
    descriptionKey: 'sidebar.storage',
  },
  {
    id: 'nav-sync',
    handlerKey: 'command.navigateSync',
    group: 'navigation',
    descriptionKey: 'sidebar.sync',
  },

  // ── Actions ──────────────────────────────────────────────────────────
  {
    id: 'action-import-books',
    handlerKey: 'command.importBooks',
    group: 'actions',
    descriptionKey: 'library.import',
  },
  {
    id: 'action-sync-now',
    handlerKey: 'command.syncNow',
    group: 'actions',
    descriptionKey: 'settings.sync.syncNow',
  },

  // ── View ─────────────────────────────────────────────────────────────
  {
    id: 'view-toggle-theme',
    handlerKey: 'command.toggleTheme',
    group: 'view',
    descriptionKey: 'settings.shortcuts.toggleDarkMode',
  },
  {
    id: 'view-toggle-fullscreen',
    handlerKey: 'command.toggleFullscreen',
    group: 'view',
    descriptionKey: 'settings.shortcuts.appFullscreen',
  },
  {
    id: 'view-show-shortcuts',
    handlerKey: 'command.showShortcuts',
    group: 'view',
    descriptionKey: 'settings.shortcuts.showHelp',
  },
];

export function runCommand(entry: CommandEntry, actions: CommandPaletteActions): void {
  actions[COMMAND_ACTION_BY_HANDLER[entry.handlerKey]]();
}
