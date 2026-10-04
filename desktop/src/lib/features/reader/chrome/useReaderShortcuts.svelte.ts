import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
import {
  getShortcutEntry,
  matchesBinding,
  resolveBinding,
  type ShortcutPlatform,
} from '$lib/shared/shortcuts/registry';
import { getShortcutPlatform } from '$lib/shared/shortcuts/platform';

export type ReaderShortcutDeps = {
  onToggleSearch: () => void;
  platform?: ShortcutPlatform;
};

/**
 * Reader-scoped shortcuts that are not already owned by a viewer or the zoom
 * hook: today, Ctrl+F / ⌘F opens the in-book search. Scoped to the reader
 * because the workspace is the only surface that registers it. The binding is
 * read from the shared registry, so the documented combo and the live handler
 * cannot drift.
 */
export function createReaderShortcuts(deps: ReaderShortcutDeps): {
  handleGlobalKeydown: (event: KeyboardEvent) => void;
} {
  function handleGlobalKeydown(event: KeyboardEvent): void {
    if (hasEditableContext(event.target as Element | null)) return;
    const entry = getShortcutEntry('reader-search');
    if (!entry) return;
    const platform = deps.platform ?? getShortcutPlatform();
    if (!matchesBinding(event, resolveBinding(entry, platform), platform)) return;
    event.preventDefault();
    deps.onToggleSearch();
  }

  return { handleGlobalKeydown };
}

export type ReaderShortcutsState = ReturnType<typeof createReaderShortcuts>;
