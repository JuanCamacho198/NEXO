import { describe, expect, it, vi } from 'vitest';
import { createReaderShortcuts } from '$lib/features/reader/chrome/useReaderShortcuts.svelte';

describe('reader shortcuts (Ctrl+F search)', () => {
  it('opens the reader search on Ctrl+F', () => {
    const onToggleSearch = vi.fn();
    const shortcuts = createReaderShortcuts({ onToggleSearch, platform: 'default' });
    shortcuts.handleGlobalKeydown(
      new KeyboardEvent('keydown', { key: 'f', ctrlKey: true }) as KeyboardEvent,
    );
    expect(onToggleSearch).toHaveBeenCalledTimes(1);
  });

  it('opens the reader search on ⌘F on macOS', () => {
    const onToggleSearch = vi.fn();
    const shortcuts = createReaderShortcuts({ onToggleSearch, platform: 'mac' });
    shortcuts.handleGlobalKeydown(
      new KeyboardEvent('keydown', { key: 'f', metaKey: true }) as KeyboardEvent,
    );
    expect(onToggleSearch).toHaveBeenCalledTimes(1);
  });

  it('does not hijack Ctrl+Shift+F (window fullscreen)', () => {
    const onToggleSearch = vi.fn();
    const shortcuts = createReaderShortcuts({ onToggleSearch, platform: 'default' });
    shortcuts.handleGlobalKeydown(
      new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, shiftKey: true }) as KeyboardEvent,
    );
    expect(onToggleSearch).not.toHaveBeenCalled();
  });

  it('does not fire on bare f (reader fullscreen) or arrows', () => {
    const onToggleSearch = vi.fn();
    const shortcuts = createReaderShortcuts({ onToggleSearch, platform: 'default' });
    shortcuts.handleGlobalKeydown(new KeyboardEvent('keydown', { key: 'f' }) as KeyboardEvent);
    shortcuts.handleGlobalKeydown(
      new KeyboardEvent('keydown', { key: 'ArrowLeft' }) as KeyboardEvent,
    );
    expect(onToggleSearch).not.toHaveBeenCalled();
  });

  it('never fires while the user types in an input', () => {
    const onToggleSearch = vi.fn();
    const shortcuts = createReaderShortcuts({ onToggleSearch, platform: 'default' });
    const input = document.createElement('input');
    document.body.appendChild(input);

    input.addEventListener('keydown', (event) =>
      shortcuts.handleGlobalKeydown(event as KeyboardEvent),
    );
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true }));

    expect(onToggleSearch).not.toHaveBeenCalled();
    input.remove();
  });
});
