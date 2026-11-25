import { render } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import HighlightContextMenu from '$lib/features/reader/highlight/HighlightContextMenu.svelte';

// Non-regression pin for the context menu's placement.
//
// These assertions capture the behaviour AS IT IS on purpose: the menu's clamp
// is correct today, so the point of this file is to make any later refactor
// (routing the placement through `placeFloatingMenu`) prove it did not move a
// single pixel. The numbers below are the menu's own constants — width 204 (the row computed without lookups),
// height estimate 56, padding 8 — read from the fixture's 1000x800 window, and
// they must not be "improved" without also re-deriving the whole file.

const t = (key: string) => key;

function renderAt(position: { x: number; y: number }) {
  return render(HighlightContextMenu, {
    highlightId: 'hl-1',
    position,
    assignedTags: [],
    onCopy: () => undefined,
    onTag: () => undefined,
    onNote: () => undefined,
    onDelete: () => undefined,
    onClose: () => undefined,
    t,
  });
}

function styleOf(container: HTMLElement): string {
  const menu = container.querySelector('[role="menu"]');
  return menu?.getAttribute('style') ?? '';
}

describe('HighlightContextMenu placement (non-regression pin)', () => {
  it('keeps the position untouched when the menu fits', () => {
    // jsdom defaults to 1024x768. x=100 is inside, y=100 + 56 < 768 - 8.
    const { container } = renderAt({ x: 100, y: 100 });
    const style = styleOf(container);

    expect(style).toContain('left: 100px');
    expect(style).toContain('top: 108px');
    expect(style).toContain('width: 204px');
  });

  it('clamps the left edge to the 8px padding', () => {
    const { container } = renderAt({ x: -50, y: 100 });

    expect(styleOf(container)).toContain('left: 8px');
  });

  it('clamps the right edge to viewport width - 204 - 8', () => {
    const expectedX = window.innerWidth - 204 - 8;
    const { container } = renderAt({ x: 99999, y: 100 });

    expect(styleOf(container)).toContain(`left: ${expectedX}px`);
  });

  it('flips above the anchor when the menu would overflow the bottom', () => {
    const { container } = renderAt({ x: 100, y: 99999 });

    // position.y - 56 - 8
    expect(styleOf(container)).toContain('top: 99935px');
  });

  // EPUB: with the highlight's box the menu leaves the click point and sits
  // centred above the text, like the selection toolbar.
  it('centres the menu above the highlight when it knows its box', () => {
    const anchor = { left: 400, right: 520, top: 300, bottom: 320 };
    const { container } = render(HighlightContextMenu, {
      props: {
        highlightId: 'hl-1',
        position: { x: 10, y: 10 },
        anchor,
        assignedTags: [],
        onCopy: () => undefined,
        onTag: () => undefined,
        onNote: () => undefined,
        onDelete: () => undefined,
        onClose: () => undefined,
        t,
      },
    });
    const style = styleOf(container);
    // centre 460 - 204 / 2 = 358; top 300 - 56 - 12 = 232
    expect(style).toContain('left: 358px');
    expect(style).toContain('top: 232px');
    expect(container.querySelector('[role="menu"]')?.getAttribute('data-placement')).toBe('above');
  });

  it('drops below the highlight when there is no room above it', () => {
    const { container } = render(HighlightContextMenu, {
      props: {
        highlightId: 'hl-1',
        position: { x: 10, y: 10 },
        anchor: { left: 400, right: 520, top: 20, bottom: 40 },
        assignedTags: [],
        onCopy: () => undefined,
        onTag: () => undefined,
        onNote: () => undefined,
        onDelete: () => undefined,
        onClose: () => undefined,
        t,
      },
    });
    expect(styleOf(container)).toContain('top: 52px');
    expect(container.querySelector('[role="menu"]')?.getAttribute('data-placement')).toBe('below');
  });
});
