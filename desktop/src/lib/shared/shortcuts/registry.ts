import type { MessageKey } from '$lib/shared/i18n';
import type { ShortcutHandlerKey } from './handlers';

export type ShortcutContext =
  'global' | 'reader' | 'reader-epub' | 'reader-pdf' | 'dialog' | 'library';

export type ShortcutGroupId = 'reader-nav' | 'reader-view' | 'app';

export type ShortcutPlatform = 'default' | 'mac';

/**
 * A platform-agnostic binding. `mod` is Ctrl on desktop and Cmd on macOS;
 * `keys` are the accepted `KeyboardEvent.key` values (single letters match
 * case-insensitively); `label` is the single glyph shown on the keycap.
 *
 * `mod`/`shift`/`alt` use three states: `true` = required, `false` = must be
 * absent, `undefined` = not constrained. Zoom binds `+`/`-`, which arrive with
 * Shift on many layouts, so those bindings leave `shift` unconstrained.
 */
export type ShortcutBinding = {
  keys: readonly string[];
  label: string;
  mod?: boolean;
  shift?: boolean;
  alt?: boolean;
};

export type ShortcutEntry = {
  id: string;
  handlerKey: ShortcutHandlerKey;
  group: ShortcutGroupId;
  context: ShortcutContext;
  combos: { default: ShortcutBinding; mac: ShortcutBinding };
  descriptionKey: MessageKey;
};

export const SHORTCUT_GROUPS: readonly { id: ShortcutGroupId; labelKey: MessageKey }[] = [
  { id: 'reader-nav', labelKey: 'settings.shortcuts.group.readerNav' },
  { id: 'reader-view', labelKey: 'settings.shortcuts.group.readerView' },
  { id: 'app', labelKey: 'settings.shortcuts.group.app' },
];

export const SHORTCUT_CONTEXT_LABELS: Record<ShortcutContext, MessageKey> = {
  global: 'settings.shortcuts.context.global',
  reader: 'settings.shortcuts.context.reader',
  'reader-epub': 'settings.shortcuts.context.readerEpub',
  'reader-pdf': 'settings.shortcuts.context.readerPdf',
  dialog: 'settings.shortcuts.context.dialog',
  library: 'settings.shortcuts.context.library',
};

/** Ctrl (`default`) or Cmd (`mac`) binding; identical keys, platform-resolved. */
const ctrl = (key: string, label: string): { default: ShortcutBinding; mac: ShortcutBinding } => ({
  default: { keys: [key], label, mod: true, shift: false, alt: false },
  mac: { keys: [key], label, mod: true, shift: false, alt: false },
});

const deck = (b: ShortcutBinding): { default: ShortcutBinding; mac: ShortcutBinding } => ({
  default: b,
  mac: b,
});

/**
 * Single source of truth for the documented shortcuts. The Ajustes tab and the
 * `?` help surface both render this list, and the global dispatcher reads its
 * global rows from here, so the list cannot drift from what the app does.
 */
export const SHORTCUT_REGISTRY: readonly ShortcutEntry[] = [
  // ── Reader navigation ────────────────────────────────────────────────
  {
    id: 'reader-prev',
    handlerKey: 'reader.prev',
    group: 'reader-nav',
    context: 'reader',
    combos: deck({ keys: ['ArrowLeft'], label: '←', mod: false, alt: false }),
    descriptionKey: 'settings.shortcuts.readerPrev',
  },
  {
    id: 'reader-next',
    handlerKey: 'reader.next',
    group: 'reader-nav',
    context: 'reader',
    combos: deck({ keys: ['ArrowRight'], label: '→', mod: false, alt: false }),
    descriptionKey: 'settings.shortcuts.readerNext',
  },
  {
    id: 'reader-scroll-up',
    handlerKey: 'reader.scrollUp',
    group: 'reader-nav',
    context: 'reader-pdf',
    combos: deck({ keys: ['ArrowUp'], label: '↑', mod: false, alt: false }),
    descriptionKey: 'settings.shortcuts.readerScrollUp',
  },
  {
    id: 'reader-scroll-down',
    handlerKey: 'reader.scrollDown',
    group: 'reader-nav',
    context: 'reader-pdf',
    combos: deck({ keys: ['ArrowDown'], label: '↓', mod: false, alt: false }),
    descriptionKey: 'settings.shortcuts.readerScrollDown',
  },

  // ── Reader view ──────────────────────────────────────────────────────
  {
    id: 'reader-search',
    handlerKey: 'reader.search',
    group: 'reader-view',
    context: 'reader',
    combos: ctrl('f', 'F'),
    descriptionKey: 'settings.shortcuts.search',
  },
  {
    id: 'reader-toggle-fullscreen',
    handlerKey: 'reader.toggleFullscreen',
    group: 'reader-view',
    context: 'reader',
    combos: deck({ keys: ['f'], label: 'F', mod: false, shift: false, alt: false }),
    descriptionKey: 'settings.shortcuts.readerFullscreen',
  },
  {
    id: 'reader-zoom-in',
    handlerKey: 'reader.zoomIn',
    group: 'reader-view',
    context: 'reader',
    combos: {
      default: { keys: ['=', '+'], label: '+', mod: true },
      mac: { keys: ['=', '+'], label: '+', mod: true },
    },
    descriptionKey: 'settings.shortcuts.zoomIn',
  },
  {
    id: 'reader-zoom-out',
    handlerKey: 'reader.zoomOut',
    group: 'reader-view',
    context: 'reader',
    combos: {
      default: { keys: ['-', '_'], label: '−', mod: true },
      mac: { keys: ['-', '_'], label: '−', mod: true },
    },
    descriptionKey: 'settings.shortcuts.zoomOut',
  },

  // ── Application ──────────────────────────────────────────────────────
  {
    id: 'app-fullscreen',
    handlerKey: 'app.fullscreen',
    group: 'app',
    context: 'global',
    combos: deck({ keys: ['F11'], label: 'F11', mod: false, alt: false }),
    descriptionKey: 'settings.shortcuts.appFullscreen',
  },
  {
    id: 'app-help',
    handlerKey: 'app.toggleHelp',
    group: 'app',
    context: 'global',
    combos: deck({ keys: ['?'], label: '?', mod: false, alt: false }),
    descriptionKey: 'settings.shortcuts.showHelp',
  },
  {
    id: 'app-toggle-dark',
    handlerKey: 'app.toggleTheme',
    group: 'app',
    context: 'global',
    combos: ctrl('d', 'D'),
    descriptionKey: 'settings.shortcuts.toggleDarkMode',
  },
  {
    id: 'library-search',
    handlerKey: 'library.search',
    group: 'app',
    context: 'library',
    combos: ctrl('k', 'K'),
    descriptionKey: 'settings.shortcuts.focusSearch',
  },
  {
    id: 'dialog-close',
    handlerKey: 'dialog.close',
    group: 'app',
    context: 'dialog',
    combos: deck({ keys: ['Escape'], label: 'Esc', mod: false, alt: false }),
    descriptionKey: 'settings.shortcuts.closeDialog',
  },
];

export function getShortcutEntry(id: string): ShortcutEntry | undefined {
  return SHORTCUT_REGISTRY.find((entry) => entry.id === id);
}

export function resolveBinding(entry: ShortcutEntry, platform: ShortcutPlatform): ShortcutBinding {
  return entry.combos[platform];
}

function keyMatches(eventKey: string, candidate: string): boolean {
  if (candidate.length === 1) return eventKey.toLowerCase() === candidate.toLowerCase();
  return eventKey === candidate;
}

export function matchesBinding(
  event: KeyboardEvent,
  binding: ShortcutBinding,
  platform: ShortcutPlatform,
): boolean {
  const modPressed = event.ctrlKey || event.metaKey;
  if (binding.mod !== undefined && binding.mod !== modPressed) return false;
  if (binding.mod === true) {
    if (platform === 'mac' && !event.metaKey) return false;
    if (platform !== 'mac' && !event.ctrlKey) return false;
  }
  if (binding.shift !== undefined && binding.shift !== event.shiftKey) return false;
  if (binding.alt !== undefined && binding.alt !== event.altKey) return false;
  return binding.keys.some((candidate) => keyMatches(event.key, candidate));
}

/** Display caps for a binding, e.g. Ctrl+D → ['Ctrl', 'D'] (['⌘', 'D'] on mac). */
export function bindingToCaps(binding: ShortcutBinding, platform: ShortcutPlatform): string[] {
  const caps: string[] = [];
  if (binding.mod) caps.push(platform === 'mac' ? '⌘' : 'Ctrl');
  if (binding.shift) caps.push('Shift');
  if (binding.alt) caps.push('Alt');
  caps.push(binding.label);
  return caps;
}

/** Resolve the entry that a global-scope combo maps to, if any. */
export function matchGlobalShortcut(
  event: KeyboardEvent,
  platform: ShortcutPlatform,
): ShortcutEntry | undefined {
  return SHORTCUT_REGISTRY.filter((entry) => entry.context === 'global').find((entry) =>
    matchesBinding(event, resolveBinding(entry, platform), platform),
  );
}
