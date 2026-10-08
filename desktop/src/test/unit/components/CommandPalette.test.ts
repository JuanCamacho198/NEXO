import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { tick } from 'svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CommandPalette from '$lib/shared/shortcuts/CommandPalette.svelte';
import { commandPaletteState } from '$lib/shared/shortcuts/commandPaletteState.svelte';
import type { CommandPaletteActions } from '$lib/shared/shortcuts/commands';

const dictionary: Record<string, string> = {
  'settings.shortcuts.commandPalette': 'Command palette',
  'settings.shortcuts.toggleDarkMode': 'Toggle dark mode',
  'settings.shortcuts.appFullscreen': 'Toggle window fullscreen',
  'settings.shortcuts.showHelp': 'Show keyboard shortcuts',
  'settings.sync.syncNow': 'Sync now',
  'library.import': 'Import Books',
  'sidebar.home': 'Home',
  'sidebar.library': 'Library',
  'sidebar.discover': 'Discover',
  'sidebar.addons': 'Addons',
  'sidebar.stats': 'Stats',
  'sidebar.highlights': 'Highlights',
  'sidebar.dictionary': 'Dictionary',
  'sidebar.settings': 'Settings',
  'sidebar.storage': 'Storage',
  'sidebar.sync': 'Sync',
  'commandPalette.searchLabel': 'Search commands',
  'commandPalette.searchPlaceholder': 'Type a command…',
  'commandPalette.empty': 'No matching commands',
  'commandPalette.resultsCount': 'Results: {{count}}',
  'commandPalette.listLabel': 'Commands',
  'commandPalette.group.navigation': 'Navigation',
  'commandPalette.group.actions': 'Actions',
  'commandPalette.group.view': 'View',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  const template = dictionary[key] ?? key;
  if (!params) return template;
  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, token) =>
    String(params[token] ?? ''),
  );
};

function makeActions(): CommandPaletteActions {
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

async function openPalette(actions: CommandPaletteActions): Promise<HTMLInputElement> {
  render(CommandPalette, { props: { t, actions } });
  commandPaletteState.show();
  await tick();
  await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
  return screen.getByRole('combobox') as HTMLInputElement;
}

describe('CommandPalette', () => {
  beforeEach(() => {
    commandPaletteState.hide();
  });

  it('renders the listbox, its options and the combobox wiring when open', async () => {
    const actions = makeActions();
    const input = await openPalette(actions);

    expect(input).toHaveAttribute('aria-expanded', 'true');
    expect(input).toHaveAttribute('aria-controls', 'command-palette-listbox');
    expect(screen.getByRole('listbox', { name: 'Commands' })).toBeInTheDocument();
    expect(screen.getAllByRole('option').length).toBeGreaterThan(0);

    const activeId = input.getAttribute('aria-activedescendant');
    expect(activeId).toBeTruthy();
    const active = document.getElementById(activeId as string);
    expect(active?.getAttribute('role')).toBe('option');
    expect(active?.getAttribute('aria-selected')).toBe('true');
  });

  it('filters commands by their label', async () => {
    const actions = makeActions();
    const input = await openPalette(actions);

    await fireEvent.input(input, { target: { value: 'library' } });
    await tick();

    const options = screen.getAllByRole('option');
    expect(options.length).toBe(1);
    expect(options[0].textContent).toBe('Library');

    await fireEvent.input(input, { target: { value: 'nothing-here' } });
    await tick();

    expect(screen.queryAllByRole('option').length).toBe(0);
    expect(screen.getByText('No matching commands')).toBeInTheDocument();
  });

  it('finds commands through a fuzzy subsequence match', async () => {
    const actions = makeActions();
    const input = await openPalette(actions);

    await fireEvent.input(input, { target: { value: 'librry' } });
    await tick();

    const options = screen.getAllByRole('option');
    expect(options.length).toBe(1);
    expect(options[0].textContent).toBe('Library');
  });

  it('orders label matches above description-only matches, stable within a rank', async () => {
    const actions = makeActions();
    const input = await openPalette(actions);

    // "a" hits several navigation labels and also the group name "Navigation",
    // so the label matches must surface before the group-only matches.
    await fireEvent.input(input, { target: { value: 'a' } });
    await tick();

    const texts = screen.getAllByRole('option').map((option) => option.textContent?.trim());
    const libraryIndex = texts.indexOf('Library');
    const homeIndex = texts.indexOf('Home');
    expect(libraryIndex).toBeGreaterThanOrEqual(0);
    expect(homeIndex).toBeGreaterThan(libraryIndex);

    // Registry order is preserved inside the label-match rank.
    expect(texts.slice(0, 5)).toEqual(['Library', 'Addons', 'Stats', 'Dictionary', 'Storage']);
  });

  it('moves the active option with arrows, Home and End', async () => {
    const actions = makeActions();
    const input = await openPalette(actions);
    const options = screen.getAllByRole('option');

    expect(document.getElementById(input.getAttribute('aria-activedescendant') as string)).toBe(
      options[0],
    );

    await fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(document.getElementById(input.getAttribute('aria-activedescendant') as string)).toBe(
      options[1],
    );

    await fireEvent.keyDown(input, { key: 'ArrowUp' });
    expect(document.getElementById(input.getAttribute('aria-activedescendant') as string)).toBe(
      options[0],
    );

    await fireEvent.keyDown(input, { key: 'End' });
    expect(document.getElementById(input.getAttribute('aria-activedescendant') as string)).toBe(
      options[options.length - 1],
    );

    await fireEvent.keyDown(input, { key: 'Home' });
    expect(document.getElementById(input.getAttribute('aria-activedescendant') as string)).toBe(
      options[0],
    );
  });

  it('runs the active command on Enter and closes the palette', async () => {
    const actions = makeActions();
    const input = await openPalette(actions);

    await fireEvent.input(input, { target: { value: 'Library' } });
    await tick();

    await fireEvent.keyDown(input, { key: 'Enter' });

    expect(actions.navigateToLibrary).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('runs a command when its row is clicked', async () => {
    const actions = makeActions();
    await openPalette(actions);

    await fireEvent.click(screen.getByRole('option', { name: 'Toggle dark mode' }));

    expect(actions.toggleTheme).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('closes on Escape and returns focus to the element that was focused before', async () => {
    const actions = makeActions();
    const opener = document.createElement('button');
    opener.textContent = 'opener';
    document.body.appendChild(opener);
    opener.focus();

    await openPalette(actions);
    expect(document.activeElement).not.toBe(opener);

    await fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(opener));
    opener.remove();
  });

  it('falls back to the main content region when the opener is gone', async () => {
    const actions = makeActions();
    const main = document.createElement('main');
    main.id = 'main-content';
    main.tabIndex = -1;
    document.body.appendChild(main);

    const opener = document.createElement('button');
    opener.textContent = 'opener';
    document.body.appendChild(opener);
    opener.focus();

    await openPalette(actions);
    expect(document.activeElement).not.toBe(opener);

    // The element that opened the palette disappears while it is open: bits-ui
    // has nothing to restore to, so the palette must fall back to main content
    // instead of leaving focus on <body>.
    opener.remove();

    await fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(main));
    main.remove();
  });

  it('announces the result count through a live region', async () => {
    const actions = makeActions();
    const input = await openPalette(actions);

    expect(screen.getByRole('status').textContent).toContain('Results:');

    await fireEvent.input(input, { target: { value: 'nothing-here' } });
    await tick();

    expect(screen.getByRole('status').textContent).toContain('Results: 0');
  });
});
