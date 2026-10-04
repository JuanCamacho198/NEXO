import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ShortcutList from '$lib/shared/shortcuts/ShortcutList.svelte';

const dictionary: Record<string, string> = {
  'settings.shortcuts.group.readerNav': 'Reader navigation',
  'settings.shortcuts.group.readerView': 'Reader view',
  'settings.shortcuts.group.app': 'Application',
  'settings.shortcuts.readerPrev': 'Previous page',
  'settings.shortcuts.readerNext': 'Next page',
  'settings.shortcuts.readerScrollUp': 'Scroll up',
  'settings.shortcuts.readerScrollDown': 'Scroll down',
  'settings.shortcuts.search': 'Search within book',
  'settings.shortcuts.readerFullscreen': 'Reader fullscreen',
  'settings.shortcuts.zoomIn': 'Zoom in',
  'settings.shortcuts.zoomOut': 'Zoom out',
  'settings.shortcuts.appFullscreen': 'Toggle window fullscreen',
  'settings.shortcuts.showHelp': 'Show keyboard shortcuts',
  'settings.shortcuts.toggleDarkMode': 'Toggle dark mode',
  'settings.shortcuts.focusSearch': 'Focus the search field',
  'settings.shortcuts.commandPalette': 'Command palette',
  'settings.shortcuts.closeDialog': 'Close open dialogs and menus',
  'settings.shortcuts.context.global': 'Anywhere in the app',
  'settings.shortcuts.context.reader': 'In the reader',
  'settings.shortcuts.context.readerEpub': 'EPUB reader',
  'settings.shortcuts.context.readerPdf': 'PDF viewer (focused)',
  'settings.shortcuts.context.dialog': 'With a dialog or menu open',
  'settings.shortcuts.context.library': 'Library and highlights',
};

const t = (key: string): string => dictionary[key] ?? key;

describe('ShortcutList', () => {
  it('renders keycaps as <kbd> with a real shadow edge', () => {
    const { container } = render(ShortcutList, { t });
    const caps = container.querySelectorAll('kbd');
    expect(caps.length).toBeGreaterThan(0);
    for (const cap of caps) {
      expect(cap.className).toContain('font-mono');
      expect(cap.className).toContain('shadow-[');
    }
  });

  it('canonicalises the combo column width (no min-w-[86px])', () => {
    const { container } = render(ShortcutList, { t });
    const html = container.innerHTML;
    expect(html).not.toContain('min-w-[86px]');
    expect(html).toContain('min-w-21.5');
  });

  it('groups the list instead of one flat run', () => {
    render(ShortcutList, { t });
    expect(screen.getByText('Reader navigation')).toBeInTheDocument();
    expect(screen.getByText('Reader view')).toBeInTheDocument();
    expect(screen.getByText('Application')).toBeInTheDocument();
  });

  it('lists the command palette and the `/` field search as separate rows', () => {
    render(ShortcutList, { t });
    expect(screen.getByText('Command palette')).toBeInTheDocument();
    expect(screen.getByText('Focus the search field')).toBeInTheDocument();
    expect(screen.getByText('/')).toBeInTheDocument();
    // The field row must not still advertise the old Ctrl+K combo.
    expect(screen.queryByText('Ctrl + K')).not.toBeInTheDocument();
  });

  it('shows the real reader fullscreen key and the app fullscreen key', () => {
    render(ShortcutList, { t });
    expect(screen.getByText('Reader fullscreen')).toBeInTheDocument();
    expect(screen.getByText('Toggle window fullscreen')).toBeInTheDocument();
    expect(screen.getByText('F11')).toBeInTheDocument();
  });

  it('labels the context of every row', () => {
    render(ShortcutList, { t });
    expect(screen.getAllByText(/In the reader/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/PDF viewer \(focused\)/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Library and highlights/).length).toBeGreaterThan(0);
  });
});
