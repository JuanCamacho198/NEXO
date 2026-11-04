import { describe, expect, it } from 'vitest';
import {
  PANEL_HEIGHT_FACTOR,
  floatingMenuAvailableWidth,
  placeFloatingMenu,
  resolveFloatingMenuLayout,
} from '$lib/shared/services/placement/floatingMenuPlacement';

// A 1000x800 viewport is the reference frame for the cases below. Every
// expected number is written as an arithmetic expression over it, so the test
// states the RULE (edge, half-menu) instead of a magic constant.
const viewport = { width: 1000, height: 800 };
const menuSize = { width: 260, height: 56 };
const options = { edgePadding: 16, offset: 16 };

describe('placeFloatingMenu', () => {
  it('centers the menu on the anchor when it fits', () => {
    const result = placeFloatingMenu(
      { left: 490, right: 510, top: 400, bottom: 420 },
      menuSize,
      viewport,
      options,
    );

    // center 500 - 260/2 = 370
    expect(result.x).toBe(500 - menuSize.width / 2);
    expect(result.placement).toBe('above');
    expect(result.y).toBe(400 - menuSize.height - options.offset);
  });

  it('clamps to the right edge when the centered menu would overflow', () => {
    const result = placeFloatingMenu(
      { left: 960, right: 980, top: 400, bottom: 420 },
      menuSize,
      viewport,
      options,
    );

    // center 970 -> would be 840..1100; clamp so right edge is 16 from the border.
    expect(result.x).toBe(viewport.width - menuSize.width - options.edgePadding);
  });

  it('clamps to the left edge when the centered menu would overflow', () => {
    const result = placeFloatingMenu(
      { left: 10, right: 30, top: 400, bottom: 420 },
      menuSize,
      viewport,
      options,
    );

    expect(result.x).toBe(options.edgePadding);
  });

  it('narrows the menu to the available width when it is wider than the viewport', () => {
    const narrowViewport = { width: 100, height: 800 };
    const wideMenu = { width: 260, height: 56 };

    const result = placeFloatingMenu(
      { left: 40, right: 60, top: 400, bottom: 420 },
      wideMenu,
      narrowViewport,
      options,
    );

    expect(result.x).toBe(options.edgePadding);
    expect(result.x).toBeGreaterThanOrEqual(0);
    // The max width the caller can render without going negative.
    expect(result.x + (narrowViewport.width - 2 * options.edgePadding)).toBe(
      narrowViewport.width - options.edgePadding,
    );
  });

  it('never returns a negative coordinate in a very narrow viewport', () => {
    const tinyViewport = { width: 20, height: 30 };
    const result = placeFloatingMenu(
      { left: 5, right: 15, top: 10, bottom: 20 },
      { width: 260, height: 56 },
      tinyViewport,
      options,
    );

    expect(result.x).toBeGreaterThanOrEqual(0);
    expect(result.y).toBeGreaterThanOrEqual(0);
  });

  it('places the menu below when there is no room above', () => {
    const result = placeFloatingMenu(
      { left: 490, right: 510, top: 20, bottom: 40 },
      menuSize,
      viewport,
      options,
    );

    expect(result.placement).toBe('below');
    expect(result.y).toBe(40 + options.offset);
  });

  it('picks the side with more space when neither side fits', () => {
    // Anchor sits near the top of a short viewport: 44 above (100 - 40 - 16),
    // 84 below (200 - 100 - 16). Neither fits a 100px menu, so the larger wins.
    const shortViewport = { width: 1000, height: 200 };
    const tallMenu = { width: 260, height: 100 };

    const result = placeFloatingMenu(
      { left: 490, right: 510, top: 40, bottom: 100 },
      tallMenu,
      shortViewport,
      options,
    );

    expect(result.placement).toBe('below');
    expect(result.y).toBeGreaterThanOrEqual(0);
    expect(result.y).toBeLessThanOrEqual(shortViewport.height);
  });

  it('picks above when above has more space and neither side fits', () => {
    // Anchor sits near the bottom: 84 above, 44 below. Above has more, so above.
    const shortViewport = { width: 1000, height: 200 };
    const tallMenu = { width: 260, height: 100 };

    const result = placeFloatingMenu(
      { left: 490, right: 510, top: 100, bottom: 140 },
      tallMenu,
      shortViewport,
      options,
    );

    expect(result.placement).toBe('above');
  });

  it('keeps the menu inside the viewport on the Y axis', () => {
    const result = placeFloatingMenu(
      { left: 490, right: 510, top: 400, bottom: 420 },
      menuSize,
      viewport,
      options,
    );

    expect(result.y).toBeGreaterThanOrEqual(0);
    expect(result.y + menuSize.height).toBeLessThanOrEqual(viewport.height);
  });

  /**
   * The vertical clamp is "never outside the viewport", not "glue to the top
   * edge".
   *
   * The reported symptom: a selection sitting low on the page raised a menu
   * pinned to the very top of the book, nowhere near the word. Two different
   * causes were on the table and both are pinned here, because the fix has to
   * hold whether the distance comes from the flip or from the clamp.
   */
  describe('the menu stays NEAR the anchor, not at the viewport ceiling', () => {
    it('places the menu directly above the anchor when the side has room', () => {
      const result = placeFloatingMenu(
        { left: 490, right: 510, top: 400, bottom: 420 },
        menuSize,
        viewport,
        options,
      );

      expect(result.placement).toBe('above');
      // Exactly `offset` above the anchor's top edge: 400 - 56 - 16.
      expect(result.y + menuSize.height + options.offset).toBe(400);
    });

    it('flips below instead of pinning to y=0 when the anchor is too close to the top', () => {
      // Anchor at y 150 with a 56px menu and a 16px offset: 150 - 56 - 16 = 78
      // (fits). Push it to 60 and above no longer has room — the honest answer is
      // to render BELOW the anchor, never at the ceiling.
      const result = placeFloatingMenu(
        { left: 490, right: 510, top: 30, bottom: 50 },
        menuSize,
        viewport,
        options,
      );

      expect(result.placement).toBe('below');
      expect(result.y).toBe(50 + options.offset);
      // The bottom edge of the menu is what the reader sees above the word.
      expect(result.y).toBeGreaterThan(0);
    });

    it('never resolves to the viewport ceiling while the anchor is on screen', () => {
      // The property the bug violated. For any anchor the reader could have
      // selected — `top` anywhere inside the viewport — the menu must not be
      // thrown to y 0 with a large gap to the text.
      for (const top of [40, 80, 120, 160, 200, 300, 500, 700]) {
        const result = placeFloatingMenu(
          { left: 490, right: 510, top, bottom: top + 18 },
          menuSize,
          viewport,
          options,
        );
        const gap =
          result.placement === 'above' ? top - (result.y + menuSize.height) : result.y - (top + 18);
        expect(
          gap,
          `an anchor at y ${top} put the menu ${gap}px away (y ${result.y}, ${result.placement})`,
        ).toBeLessThanOrEqual(menuSize.height + options.offset);
      }
    });

    it('places the menu by the reserved height, so an honest budget is what closes the gap', () => {
      // `renderedHeight` bounds the CLAMP (how low the menu may sit before its
      // bottom edge leaves the viewport); it does not move the menu closer to
      // the anchor. The distance from the word is `menuSize.height`, so the
      // caller has to reserve what it renders: with the 176px panel budget the
      // menu sits 176+8 above the word, and with the real 46px row it sits 46+8.
      const withPanelBudget = placeFloatingMenu(
        { left: 490, right: 510, top: 241, bottom: 259 },
        { width: 380, height: 176 },
        { width: 1200, height: 779 },
        { edgePadding: 16, offset: 8, renderedHeight: 46 },
      );
      expect(withPanelBudget.y).toBe(57);

      const withHonestRow = placeFloatingMenu(
        { left: 490, right: 510, top: 241, bottom: 259 },
        { width: 380, height: 46 },
        { width: 1200, height: 779 },
        { edgePadding: 16, offset: 8, renderedHeight: 46 },
      );
      expect(withHonestRow.y).toBe(187);
      expect(withHonestRow.y + 46 + 8).toBe(241);
    });
  });
});

describe('floatingMenuAvailableWidth', () => {
  it('removes the edge padding from both sides', () => {
    expect(floatingMenuAvailableWidth({ width: 1000, height: 800 }, { edgePadding: 16 })).toBe(968);
  });

  it('never goes negative when the viewport is smaller than the padding', () => {
    expect(floatingMenuAvailableWidth({ width: 20, height: 800 }, { edgePadding: 16 })).toBe(0);
  });

  it('falls back to the default padding when the caller names none', () => {
    expect(floatingMenuAvailableWidth({ width: 1000, height: 800 })).toBe(1000 - 2 * 8);
  });
});

describe('PANEL_HEIGHT_FACTOR', () => {
  it('reserves room for a result panel below the menu, not just the row', () => {
    // The toolbar renders its definition / translation panel BELOW the pill and
    // only knows about it through this factor. A factor of 1 would budget the
    // row alone, which is the state that let a 300px definition panel hang off
    // the bottom edge.
    expect(PANEL_HEIGHT_FACTOR).toBeGreaterThan(1);
  });

  it('keeps a wrapped row plus its panel inside a short viewport', () => {
    // The two-line row (2 x 56) plus one panel's worth of room, placed above an
    // anchor with just enough space: the budget must fit exactly, so the clamp
    // has nothing to correct.
    const budget = 2 * 56 * PANEL_HEIGHT_FACTOR;
    const result = placeFloatingMenu(
      { left: 490, right: 510, top: budget, bottom: budget + 20 },
      { width: 240, height: budget },
      { width: 1000, height: budget + 40 },
      { edgePadding: 16, offset: 16 },
    );

    expect(result.placement).toBe('above');
    expect(result.y).toBeGreaterThanOrEqual(0);
    expect(result.y + budget).toBeLessThanOrEqual(budget + 40);
  });
});

describe('resolveFloatingMenuLayout', () => {
  // The toolbar's real numbers, MEASURED against the live app over CDP: a 380px
  // full pill, 240px once the five circles fold into one trigger, and a 148px
  // floor for the four actions on one row.
  const TOOLBAR = { naturalWidth: 380, collapsedWidth: 240 };

  it('renders the full layout when the content fits', () => {
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 600 }, TOOLBAR)).toBe('full');
  });

  it('renders the full layout when it fits exactly', () => {
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 380 }, TOOLBAR)).toBe('full');
  });

  it('collapses before wrapping when the full row no longer fits', () => {
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 300 }, TOOLBAR)).toBe(
      'collapsed',
    );
  });

  it('starts at wrapping when the caller has nothing to collapse', () => {
    const noPalette = { naturalWidth: 240 };

    expect(resolveFloatingMenuLayout({ ...noPalette, availableWidth: 260 })).toBe('full');
    expect(resolveFloatingMenuLayout({ ...noPalette, availableWidth: 200 })).toBe('wrapped');
  });

  it('collapses when the collapsed width fits exactly', () => {
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 240 }, TOOLBAR)).toBe(
      'collapsed',
    );
  });

  it('wraps at the band where neither the full nor the collapsed row fits', () => {
    // The rung the ladder used to be unable to reach. A band narrower than the
    // collapsed pill (240px) is where wrapping is the ONLY thing left that can
    // fit: the two-line row breaks the four actions onto their own line, which
    // the caller floors at `min-w-37` (148px) plus padding — narrower than the
    // 240px pill it replaces.
    //
    // MEASURED: this app's Tauri window boots at ~270px (see
    // `e2e/tauri/readerSelectionCollapsed.spec.ts`), and the toolbar's band is
    // `innerWidth - 2 * edgePadding` = 238px — under the 240px collapsed pill.
    // So the band is not hypothetical: it is the width the app opens at.
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 239 }, TOOLBAR)).toBe('wrapped');
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 238 }, TOOLBAR)).toBe('wrapped');
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 200 }, TOOLBAR)).toBe('wrapped');
    expect(resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: 148 }, TOOLBAR)).toBe('wrapped');
  });

  it('stops on a rung that can actually fit the band it was chosen for', () => {
    // The property the old fall-through violated and this fixes. The rung widths
    // are ordered full 380 > collapsed 240 > wrapped 148, so for any band wide
    // enough for SOME rung, the ladder must answer with one of them that fits —
    // never with a wider one it cannot hold.
    const rungWidth = { full: 380, collapsed: 240, wrapped: 148 };
    for (const band of [800, 400, 300, 272, 260, 250, 240, 238, 200, 182, 148]) {
      const rung = resolveFloatingMenuLayout({ ...TOOLBAR, availableWidth: band }, TOOLBAR);
      expect(
        rungWidth[rung],
        `band ${band} answered with "${rung}" (${rungWidth[rung]}px), wider than the band it was chosen for`,
      ).toBeLessThanOrEqual(band);
    }
  });

  it('wraps when there is nothing to collapse, however wide the band is', () => {
    // The rung a caller with a single row of actions lands on: `collapsedWidth`
    // is what makes the collapse rung reachable, so omitting it skips straight
    // to wrapping — and never to `full` for a band narrower than the row.
    expect(resolveFloatingMenuLayout({ naturalWidth: 380, availableWidth: 300 })).toBe('wrapped');
  });
});
