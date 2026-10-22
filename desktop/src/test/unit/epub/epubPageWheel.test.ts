/**
 * The wheel-as-page-turn decision for paginated mode.
 *
 * Five claims are load-bearing, and each has a way of breaking that looks like a
 * working feature:
 *
 *  1. **Ctrl is zoom, not a turn.** `epubZoomWheel.ts` already owns ctrl+wheel.
 *     A wheel listener that claimed it too would zoom and page on one gesture.
 *  2. **Direction follows the sign.** Down is forward, as on every other vertical
 *     scroll surface.
 *  3. **One notch, one page.** Windows emits several events per notch; without
 *     coalescing one gesture advances several pages.
 *  4. **Nothing to turn to is `'none'`.** A chapter whose columns are not measured
 *     (`pageCount === 0`), or a reader in scrolling mode, must be left alone.
 *  5. **A reversal is a new gesture.** A burst filter that ignored direction would
 *     make the wheel feel stuck when the reader turns around.
 */
import { describe, expect, it } from 'vitest';
import {
  WHEEL_GESTURE_WINDOW_MS,
  createEpubPageWheelGesture,
  resolveEpubPageWheelIntent,
  type EpubPageWheelState,
} from '$lib/features/reader/viewer-epub/epubPageWheel';

/** A wheel event as the chapter's document would deliver it. */
function wheelEvent(init: { ctrlKey?: boolean; metaKey?: boolean; deltaY?: number }): {
  ctrlKey: boolean;
  metaKey: boolean;
  deltaY: number;
} {
  return {
    ctrlKey: init.ctrlKey ?? false,
    metaKey: init.metaKey ?? false,
    deltaY: init.deltaY ?? 0,
  };
}

/** A measured paginated chapter. */
const PAGINATED: EpubPageWheelState = { paginated: true, pageCount: 25 };

describe('epubPageWheel', () => {
  it('excludes ctrl and meta: those wheel gestures are the zoom', () => {
    const gesture = createEpubPageWheelGesture();
    expect(
      resolveEpubPageWheelIntent(wheelEvent({ ctrlKey: true, deltaY: 120 }), PAGINATED, gesture, 0),
    ).toBe('none');
    expect(
      resolveEpubPageWheelIntent(wheelEvent({ metaKey: true, deltaY: 120 }), PAGINATED, gesture, 0),
    ).toBe('none');
    // A ctrl+wheel must not open a gesture window either: it ran no turn, so a
    // plain notch immediately afterwards is the reader's FIRST turn, not a
    // repeat of anything.
    expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: 120 }), PAGINATED, gesture, 1)).toBe(
      'next',
    );
  });

  it('follows the sign of deltaY: down is forward, up is back', () => {
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: 120 }),
        PAGINATED,
        createEpubPageWheelGesture(),
        0,
      ),
    ).toBe('next');
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: -120 }),
        PAGINATED,
        createEpubPageWheelGesture(),
        0,
      ),
    ).toBe('prev');
    // The magnitude is irrelevant: a notch is a notch, however hard it was
    // spun. One gesture, one page.
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: 4000 }),
        PAGINATED,
        createEpubPageWheelGesture(),
        0,
      ),
    ).toBe('next');
  });

  it('coalesces a burst from one notch into a single page', () => {
    const gesture = createEpubPageWheelGesture();
    // Measured shape of a high-delta mouse: one physical notch arrives as four
    // events within a handful of milliseconds. Counting each would advance four
    // pages for one click of the wheel.
    const intents = [0, 2, 4, 6].map((at) =>
      resolveEpubPageWheelIntent(wheelEvent({ deltaY: 30 }), PAGINATED, gesture, at),
    );
    expect(intents).toEqual(['next', 'none', 'none', 'none']);
  });

  it('counts a deliberate second notch once the gesture window has passed', () => {
    // Each boundary is measured from a FRESH gesture: a repeat inside the
    // window slides the timestamp forward (that is what keeps a trackpad slide
    // coalesced), so stacking both cases on one gesture would measure the
    // second from the first's refresh rather than from the notch.
    const inside = createEpubPageWheelGesture();
    expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: 120 }), PAGINATED, inside, 0)).toBe(
      'next',
    );
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: 120 }),
        PAGINATED,
        inside,
        WHEEL_GESTURE_WINDOW_MS - 1,
      ),
      'an event just inside the window is part of the same gesture and must not turn again',
    ).toBe('none');

    const past = createEpubPageWheelGesture();
    expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: 120 }), PAGINATED, past, 0)).toBe(
      'next',
    );
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: 120 }),
        PAGINATED,
        past,
        WHEEL_GESTURE_WINDOW_MS,
      ),
      'an event at or past the window is a new notch from the reader’s hand and must turn once',
    ).toBe('next');
  });

  it('keeps a stream of events coalesced for as long as it keeps arriving', () => {
    const gesture = createEpubPageWheelGesture();
    expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: 10 }), PAGINATED, gesture, 0)).toBe(
      'next',
    );
    // A trackpad stream: every event is inside the window measured from the
    // PREVIOUS one, so the gesture never ends mid-slide however many events a
    // long swipe produces.
    let at = 0;
    for (let index = 0; index < 40; index++) {
      at += WHEEL_GESTURE_WINDOW_MS - 20;
      expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: 10 }), PAGINATED, gesture, at)).toBe(
        'none',
      );
    }
  });

  it('treats a reversal as a new gesture instead of swallowing it', () => {
    const gesture = createEpubPageWheelGesture();
    expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: 120 }), PAGINATED, gesture, 0)).toBe(
      'next',
    );
    // The reader turned around mid-burst. Swallowing this would make the wheel
    // feel stuck for the length of the window.
    expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: -120 }), PAGINATED, gesture, 1)).toBe(
      'prev',
    );
  });

  it('does nothing when not paginated: scrolling mode keeps its scroll', () => {
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: 120 }),
        { paginated: false, pageCount: 25 },
        createEpubPageWheelGesture(),
        0,
      ),
    ).toBe('none');
  });

  it('does nothing before the chapter columns are measured', () => {
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: 120 }),
        { paginated: true, pageCount: 0 },
        createEpubPageWheelGesture(),
        0,
      ),
    ).toBe('none');
  });

  it('does nothing for a horizontal-only wheel tilt', () => {
    expect(
      resolveEpubPageWheelIntent(
        wheelEvent({ deltaY: 0 }),
        PAGINATED,
        createEpubPageWheelGesture(),
        0,
      ),
    ).toBe('none');
    // And it must not open a gesture window either: the NEXT vertical notch is
    // still the reader's first turn.
    const gesture = createEpubPageWheelGesture();
    resolveEpubPageWheelIntent(wheelEvent({ deltaY: 0 }), PAGINATED, gesture, 0);
    expect(resolveEpubPageWheelIntent(wheelEvent({ deltaY: 120 }), PAGINATED, gesture, 1)).toBe(
      'next',
    );
  });
});
