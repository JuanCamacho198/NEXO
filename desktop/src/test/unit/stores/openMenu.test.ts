import { describe, expect, it } from 'vitest';
import {
  closeMenu,
  openMenu,
  type OpenMenu,
  type OpenMenuReason,
} from '$lib/features/reader/chrome/openMenu';

/**
 * The workspace shows at most ONE floating menu: the selection toolbar or the
 * highlight context menu. `openMenu`/`closeMenu` are the whole of that decision
 * — pure, DOM-free, so they can be asserted directly instead of through a live
 * app.
 *
 * The reported defect these tests pin down: selecting text that CONTAINS an
 * existing highlight raised both menus at once and then closed both. Two
 * independent open flags made "one menu" an accident of ordering; here it is an
 * invariant.
 */
describe('openMenu', () => {
  it('opening highlight closes selection -> only highlight', () => {
    const opened: OpenMenu = openMenu('selection', 'highlight');
    expect(opened).toBe('highlight');
  });

  it('opening selection closes highlight -> only selection', () => {
    const opened: OpenMenu = openMenu('highlight', 'selection');
    expect(opened).toBe('selection');
  });

  it('opening from none shows the requested menu', () => {
    expect(openMenu('none', 'selection')).toBe('selection');
    expect(openMenu('none', 'highlight')).toBe('highlight');
  });

  it('opening selection while selection is open stays selection (idempotent)', () => {
    expect(openMenu('selection', 'selection')).toBe('selection');
  });

  it('opening highlight while highlight is open stays highlight (idempotent)', () => {
    expect(openMenu('highlight', 'highlight')).toBe('highlight');
  });

  it('never leaves both menus open, from any state', () => {
    const states: OpenMenu[] = ['none', 'selection', 'highlight'];
    for (const from of states) {
      for (const next of ['selection', 'highlight'] as const) {
        expect(openMenu(from, next)).toBe(next);
      }
    }
  });
});

describe('closeMenu', () => {
  it('closes to none regardless of the reason', () => {
    const reasons: OpenMenuReason[] = [
      'workspace-pointerdown',
      'color-applied',
      'inside-click',
      'escape',
      'scroll',
      'page-turn',
      'toc-navigate',
      'header-goto-page',
      'prev-page',
      'next-page',
      'pdf-onselectionclear',
      'highlight-deleted',
    ];
    for (const reason of reasons) {
      expect(closeMenu('selection', reason)).toBe('none');
      expect(closeMenu('highlight', reason)).toBe('none');
    }
  });

  it('closing when already none stays none (idempotent, no throw)', () => {
    expect(closeMenu('none', 'workspace-pointerdown')).toBe('none');
    expect(closeMenu('none', 'unknown')).toBe('none');
  });

  it('an unknown reason is still a close', () => {
    expect(closeMenu('highlight', 'some-new-path')).toBe('none');
  });
});
