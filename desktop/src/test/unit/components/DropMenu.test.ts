import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ShelfBookActions from '$lib/features/library/components/ShelfBookActions.svelte';
import type { ShelfBook } from '$lib/features/library/utils';
import { stubElementRect } from '../../harness/jsdomHarness';
import DropMenuNestedItemsStub from '../../stubs/DropMenuNestedItemsStub.svelte';

/**
 * `DropMenu` now runs on bits-ui `DropdownMenu`, with the declared deviation
 * from design D10: the callers own their trigger button and hand the menu plain
 * `<button>` children, so `menuItemSemantics` supplies the item registration
 * bits-ui cannot. These tests drive the real `ShelfBookActions` consumer (the
 * five-plain-buttons case) and a stub mirroring `HighlightsView` (items nested
 * in a layout div), never a simplified stand-in for either.
 */

const book: ShelfBook = {
  id: 'book-1',
  title: 'Dune',
  author: 'Frank Herbert',
  format: 'epub',
  currentPage: 0,
  totalPages: 100,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: '2026-01-01T00:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
  filePath: '/books/dune.epub',
  collectionIds: [],
};

const t = (key: string): string => key;

const ITEM_LABELS = [
  'shelf.openBook',
  'shelf.markFavorite',
  'shelf.markCompleted',
  'shelf.viewDetails',
  'shelf.removeLibrary',
];

function renderShelf(overrides: Record<string, unknown> = {}) {
  return render(ShelfBookActions, { props: { book, t, ...overrides } });
}

async function openMenu(name = 'shelf.bookOptions'): Promise<HTMLElement> {
  const trigger = screen.getByRole('button', { name });
  // jsdom has no layout engine: the portalled content is measured against the
  // trigger's rect, and floating-ui's hide middleware falls back to
  // `visibility: hidden` while that rect is all zeros. Every opening in this
  // file gives the trigger a geometry first, so nothing here asserts against
  // content the a11y tree was told to ignore.
  stubElementRect(trigger, { left: 100, top: 100, width: 36, height: 36 });
  await fireEvent.click(trigger);
  await waitFor(() => expect(screen.getByRole('menu')).toBeTruthy());
  return trigger;
}

function menuitems(): HTMLElement[] {
  return screen.getAllByRole('menuitem');
}

async function press(key: string): Promise<void> {
  await fireEvent.keyDown(document.activeElement ?? document.body, { key });
}

describe('DropMenu — trigger contract', () => {
  it("uses the caller's own button as the trigger element, with no interactive wrapper", async () => {
    renderShelf();

    const trigger = screen.getByRole('button', { name: 'shelf.bookOptions' });
    expect(trigger.tagName).toBe('BUTTON');
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveAttribute('data-dropdown-menu-trigger');

    // The old wrapper was a `div role="button" tabindex="0"` around this button.
    // Nothing between the button and the document may be interactive again.
    let ancestor = trigger.parentElement;
    while (ancestor && ancestor !== document.body) {
      expect(ancestor.getAttribute('role')).not.toBe('button');
      expect(ancestor.hasAttribute('tabindex')).toBe(false);
      ancestor = ancestor.parentElement;
    }
  });

  it('reports its open state on that same button', async () => {
    renderShelf();
    const trigger = await openMenu();

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(trigger).toHaveAttribute('data-state', 'open');
  });

  it('maps position="bottom-right" onto side=bottom and align=end', async () => {
    renderShelf();
    await openMenu();

    const content = screen.getByRole('menu');
    expect(content).toHaveAttribute('data-side', 'bottom');
    expect(content).toHaveAttribute('data-align', 'end');
  });
});

describe('DropMenu — children render verbatim', () => {
  it('renders the five caller-owned buttons and tags exactly those as menuitems', async () => {
    renderShelf();
    await openMenu();

    const items = menuitems();
    expect(items).toHaveLength(5);
    expect(items.map((item) => item.textContent?.trim())).toEqual(ITEM_LABELS);

    for (const item of items) {
      expect(item.tagName).toBe('BUTTON');
      // The caller's own classes survived; the menu did not substitute markup.
      expect(item.className).toContain('rounded-lg');
    }
    expect(items[0].parentElement?.className).toBe('py-1');
  });

  it('tags nested caller markup (the HighlightsView shape) without touching its layout', async () => {
    const onPick = vi.fn();
    render(DropMenuNestedItemsStub, { props: { position: 'bottom-left', onPick } });

    const trigger = await openMenu('Opciones');
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');

    const content = screen.getByRole('menu');
    expect(content).toHaveAttribute('data-side', 'bottom');
    expect(content).toHaveAttribute('data-align', 'start');

    const items = menuitems();
    expect(items.map((item) => item.textContent?.trim())).toEqual(['Copy text', 'View in book']);
    // The caller's layout div is still a div; only its a11y role changed, so
    // the menuitems stay owned by `role="menu"`.
    expect(items[0].parentElement?.tagName).toBe('DIV');
    expect(items[0].parentElement?.className).toBe('flex flex-col');
    expect(items[0].parentElement?.getAttribute('role')).toBe('none');

    await fireEvent.keyDown(items[1], { key: 'Enter' });
    expect(onPick).toHaveBeenCalledWith('view');
  });
});

describe('DropMenu — keyboard contract', () => {
  it('focuses the first item on open and moves the active item in DOM order', async () => {
    renderShelf();
    await openMenu();

    const items = menuitems();
    expect(document.activeElement).toBe(items[0]);

    await press('ArrowDown');
    expect(document.activeElement).toBe(items[1]);

    await press('ArrowDown');
    expect(document.activeElement).toBe(items[2]);

    await press('ArrowUp');
    expect(document.activeElement).toBe(items[1]);

    await press('ArrowUp');
    await press('ArrowUp');
    expect(document.activeElement).toBe(items[4]);

    await press('ArrowDown');
    expect(document.activeElement).toBe(items[0]);
  });

  it('jumps with Home and End', async () => {
    renderShelf();
    await openMenu();

    const items = menuitems();
    await press('End');
    expect(document.activeElement).toBe(items[4]);

    await press('Home');
    expect(document.activeElement).toBe(items[0]);
  });

  it('activates the active item with Enter, once, and nothing else', async () => {
    const onViewDetails = vi.fn();
    const onOpenBook = vi.fn();
    const onRemoveBook = vi.fn();
    renderShelf({ onViewDetails, onOpenBook, onRemoveBook });
    await openMenu();

    await press('Home');
    await press('ArrowDown');
    await press('ArrowDown');
    await press('ArrowDown');
    expect(document.activeElement).toBe(menuitems()[3]);

    await press('Enter');

    expect(onViewDetails).toHaveBeenCalledTimes(1);
    expect(onViewDetails).toHaveBeenCalledWith(book);
    expect(onOpenBook).not.toHaveBeenCalled();
    expect(onRemoveBook).not.toHaveBeenCalled();
  });

  it('activates the active item with Space', async () => {
    const onOpenBook = vi.fn();
    renderShelf({ onOpenBook });
    await openMenu();

    await press('Home');
    await press(' ');
    expect(onOpenBook).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape without activating anything', async () => {
    const onRemoveBook = vi.fn();
    renderShelf({ onRemoveBook });
    await openMenu();

    const items = menuitems();
    expect(document.activeElement).toBe(items[0]);

    await fireEvent.keyDown(document.body, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());

    expect(onRemoveBook).not.toHaveBeenCalled();
  });

  it('returns focus to the trigger when the menu closes', async () => {
    renderShelf();
    const trigger = await openMenu();

    // `tabbable` reads `getClientRects().length` as its display gate, and jsdom
    // reports zero rects for every element; without a stubbed geometry bits-ui
    // skips its focus restore and this assertion would only prove that nothing
    // was restored.
    expect(trigger.getClientRects()).toHaveLength(1);

    await fireEvent.keyDown(document.body, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());

    expect(document.activeElement).toBe(trigger);
  });
});
