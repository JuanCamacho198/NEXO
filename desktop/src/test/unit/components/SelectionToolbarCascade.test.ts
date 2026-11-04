import { fireEvent, render } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';

// F6: the layout cascade.
//
// Order is the contract, not an implementation detail: collapse the palette
// FIRST (discreet — one swatch instead of five circles) and only break the row
// onto two lines when even that does not fit. A narrow window must never lose an
// action, and a NORMAL window must never change at all — every test here that
// expects the collapsed or wrapped layout also asserts the normal window still
// renders the five circles.

const t = (key: string, params?: Record<string, string | number>) => {
  const translations: Record<string, string> = {
    'highlight.menuAriaLabel': 'Selection actions',
    'highlight.selectColor': 'Select {{color}} highlight',
    'highlight.changeColor': 'Change color',
    'highlight.color.yellow': 'Yellow',
    'highlight.color.green': 'Green',
    'highlight.color.blue': 'Blue',
    'highlight.color.orange': 'Orange',
    'highlight.color.red': 'Red',
    'reader.copiar': 'Copy',
    'reader.addToDictionary': 'Add to Dictionary',
    'reader.translate': 'Translate',
    'reader.define': 'Define',
  };
  let value = translations[key] ?? key;
  if (params) {
    for (const [paramKey, paramValue] of Object.entries(params)) {
      value = value.replace(`{{${paramKey}}}`, String(paramValue));
    }
  }
  return value;
};

/** jsdom's window is 1024x768 — the width the "normal window" cases run at. */
const WINDOW_WIDTH = 1024;
const WINDOW_HEIGHT = 768;
/** `TOOLBAR_MAX_WIDTH` — the MEASURED full pill (5 colors + sep + 4 actions). */
const FULL_WIDTH = 380;
/** `TOOLBAR_COLLAPSED_WIDTH` — the same pill with one swatch instead of five. */
const COLLAPSED_WIDTH = 240;
/** `TOOLBAR_ACTIONS_MIN_WIDTH` — the four actions on one row, measured at 148. */
const ACTIONS_MIN_WIDTH = 148;

/**
 * The viewport is passed as a prop rather than set on `window`.
 *
 * Writing `window.innerWidth` is not enough: a `$derived` that reads the ambient
 * `window` is evaluated once and cached for the module's lifetime, so the first
 * test's width would pin every later test in the file. Measured — see the
 * `viewport` prop's own note in `SelectionToolbar.svelte`.
 */
const baseProps = () => ({
  selectedText: 'Selection text',
  selectionBounds: { left: 40, top: 120, right: 160, bottom: 140 },
  containerRect: { left: 100, top: 200, width: 320, height: 480 },
  selectionData: {
    text: 'Selection text',
    bounds: { left: 40, top: 120, right: 160, bottom: 140 },
    rects: [{ left: 40, top: 120, width: 120, height: 20 }],
    pageNumber: 1,
    cfi: null,
  } satisfies SelectionData,
  // Wide enough for the 380px full row: the default case is the unchanged pill.
  viewport: { width: WINDOW_WIDTH, height: WINDOW_HEIGHT },
  onCopy: () => undefined,
  onAddToDictionary: async (): Promise<DictionaryCaptureFeedback> => 'created',
  onTranslate: async (): Promise<TranslationOutcome> => ({ status: 'ok', text: 'x' }),
  onDefine: async () => ({
    word: 'w',
    definitions: [],
    source: 'native' as const,
    attribution: 'a',
  }),
  onColorSelect: () => undefined,
  t,
});

/**
 * The color swatches the reader can currently see.
 *
 * Counted by accessible name rather than by element, so a swatch inside the
 * collapsed popover still counts: the cascade may hide screen space, never a
 * color.
 */
const visibleSwatches = (container: HTMLElement): Element[] =>
  Array.from(container.querySelectorAll('button[aria-label*="highlight"]'));

describe('SelectionToolbar — layout cascade', () => {
  it('renders the full palette in a normal window', () => {
    const { container, queryByLabelText } = render(SelectionToolbar, baseProps());

    expect(visibleSwatches(container)).toHaveLength(5);
    // The collapsed trigger exists only as the narrow fallback; in the common
    // case the toolbar is exactly the pill it always was.
    expect(queryByLabelText('Change color')).toBeNull();
  });

  it('keeps the full palette when the window still has room for it', () => {
    // Available = 380 exactly: the narrowest viewport at which the full row
    // still fits, so the collapse rung is not reached.
    const { container, queryByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: FULL_WIDTH + 2 * 16, height: WINDOW_HEIGHT },
    });

    expect(visibleSwatches(container)).toHaveLength(5);
    expect(queryByLabelText('Change color')).toBeNull();
  });

  it('folds the palette into one swatch before wrapping the row', () => {
    // Available = 300: the 380px row does not fit, the 240px collapsed row does.
    const { container, getByLabelText, queryByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: COLLAPSED_WIDTH + 2 * 16 + 28, height: WINDOW_HEIGHT },
    });

    // The five circles are gone, but their trigger is not a dead end: `Define`
    // and the rest of the row are still mounted, and the swatch opens the
    // palette. A narrow window costs screen space, not actions.
    expect(getByLabelText('Change color')).toBeTruthy();
    expect(queryByLabelText('Define')).toBeTruthy();
    expect(queryByLabelText('Copy')).toBeTruthy();
    expect(container.querySelector('button[aria-label*="Yellow"]')).toBeNull();
    // One line: the collapsed rung resolved the case, so the row must NOT wrap.
    expect(container.querySelector('[role="toolbar"]')?.className).toContain('rounded-full');
  });

  it('shows every color inside the collapsed popover, and applies the one clicked', async () => {
    const onColorSelect = vi.fn();
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: COLLAPSED_WIDTH + 60, height: WINDOW_HEIGHT },
      onColorSelect,
    });

    await fireEvent.click(getByLabelText('Change color'));

    // All five colors are reachable again — collapsing hides nothing.
    expect(visibleSwatches(container)).toHaveLength(5);

    const blue = container.querySelector('button[aria-label*="Blue"]') as HTMLButtonElement;
    await fireEvent.click(blue);

    expect(onColorSelect).toHaveBeenCalledOnce();
    expect(onColorSelect.mock.calls[0][0]).toBe('#3B82F6');

    // The popover closes on pick, so the compact pill does not stay expanded.
    expect(visibleSwatches(container)).toHaveLength(0);
    expect(container.querySelector('button[aria-label*="Blue"]')).toBeNull();
  });

  // C2 — the collapsed palette's popover is placed in VIEWPORT coordinates.
  //
  // `getBoundingClientRect()` measures from the viewport, so the popover must be
  // `fixed`: an `absolute` popover resolves `top` against its nearest positioned
  // ancestor (the `group relative` wrapper around the trigger) and the viewport
  // number lands a container offset too low. MEASURED over CDP against the live
  // app, the popover's top was 332 on a 767px window while its own box started at
  // 332 too — below the pill's 56px `max-height` box, inside the toolbar's
  // `overflow-hidden` clip, and painted nowhere at all. The trigger looked dead.
  //
  // jsdom cannot lay anything out — every rect is zeros — so the testable half is
  // the COORDINATE SPACE the popover declares. That is the defect: not a wrong
  // number, a number measured in one space and applied in another.
  it('places the collapsed popover in the viewport coordinate space it measured in', async () => {
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: COLLAPSED_WIDTH + 60, height: WINDOW_HEIGHT },
    });

    await fireEvent.click(getByLabelText('Change color'));

    const popover = container.querySelector('[role="group"]');
    expect(popover, 'the collapsed palette popover did not open').not.toBeNull();
    expect(
      popover!.className,
      'the collapsed popover is positioned against its ancestor while its top is ' +
        'measured from the viewport',
    ).toContain('fixed');
    expect(popover!.className).not.toContain('absolute');
  });

  it("keeps the collapsed popover's left edge inside the window", async () => {
    // The narrow case that produced `collapsed` in the first place: the popover
    // is up to 176px wide, so leaving it at the trigger's left edge would run it
    // off the right border.
    const width = COLLAPSED_WIDTH + 60;
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width, height: WINDOW_HEIGHT },
    });

    await fireEvent.click(getByLabelText('Change color'));

    const popover = container.querySelector('[role="group"]') as HTMLElement;
    const left = Number.parseFloat(popover.style.left);
    expect(
      Number.isFinite(left),
      `the popover declared no usable left (${popover.style.left})`,
    ).toBe(true);
    expect(
      left,
      `the popover starts at ${left}px, outside the ${width}px window — the palette ` +
        `would be clipped by the toolbar that opens it`,
    ).toBeGreaterThanOrEqual(0);
    expect(
      left + 176,
      `the popover spans ${left}..${left + 176}px, past the ${width}px right border`,
    ).toBeLessThanOrEqual(width);
  });

  it('wraps the row onto two lines when the band holds neither full nor collapsed', () => {
    // Available = 116 (a 148px window minus 2 x 16 edge padding), below the
    // 240px collapsed width. The pill cannot fit, so the LAST rung is the only
    // honest answer: the wrapped row breaks the four actions onto their own
    // line, which the caller floors at `min-w-37` (148px) — narrower than the
    // 240px pill it replaces. Returning `collapsed` here hands the reader a menu
    // 2px wider than the band, which is the defect.
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: ACTIONS_MIN_WIDTH, height: WINDOW_HEIGHT },
    });

    const row = container.querySelector('[role="toolbar"]');
    expect(row?.className).toContain('flex-wrap');
    expect(row?.className).toContain('min-w-37');
    expect(row?.className).not.toContain('rounded-full');

    // Every action survives anyway: a narrow window costs screen space, not
    // actions. And the palette is still reachable through one trigger.
    expect(getByLabelText('Define')).toBeTruthy();
    expect(getByLabelText('Copy')).toBeTruthy();
    expect(getByLabelText('Change color')).toBeTruthy();
  });

  it('never lets the pill start at a negative coordinate in a tiny window', () => {
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 40, height: WINDOW_HEIGHT },
      selectionBounds: { left: 0, top: 120, right: 4, bottom: 140 },
      containerRect: { left: 0, top: 0, width: 40, height: 480 },
    });

    const left = Number(
      /left:\s*(-?[\d.]+)px/.exec(
        container.querySelector('.selection-toolbar')?.getAttribute('style') ?? '',
      )?.[1],
    );
    expect(left).toBeGreaterThanOrEqual(0);
  });

  it('reserves room for the result panel while one is open', async () => {
    // The panel budget is reserved only WHILE a panel is on screen. Budgeting it
    // unconditionally is what parked the menu ~130px above the word when nothing
    // was open, so the reservation has to be exercised with the panel actually
    // there — which is also the case it exists for: a pill-plus-panel in a 200px
    // window must not hang off the bottom edge.
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      // The anchor sits at the very bottom of a 200px window: `above` wants the
      // 240px anchor minus the reserved box minus the offset, which is far below
      // the ceiling — so the clamp pulls the box DOWN to the last coordinate that
      // keeps its bottom edge on screen.
      viewport: { width: COLLAPSED_WIDTH + 60, height: 200 },
      selectionBounds: { left: 40, top: 240, right: 160, bottom: 260 },
      containerRect: { left: 0, top: 0, width: 320, height: 480 },
    });

    // Open the panel: the reservation is what the panel's own arrival depends on.
    // `handleTranslate` resolves asynchronously and `resultPanelOpen` follows it,
    // so the panel has to be awaited — measuring before that reads the no-panel
    // placement, which is the case this test exists to distinguish.
    await fireEvent.click(getByLabelText('Translate'));
    await vi.waitFor(() => {
      expect(container.querySelector('[role="status"]')).not.toBeNull();
    });

    const style = container.querySelector('.selection-toolbar')?.getAttribute('style') ?? '';
    const top = Number(/top:\s*(-?[\d.]+)px/.exec(style)?.[1]);

    // The `collapsed` rung reserves the row (46, MEASURED) plus a panel's worth
    // (`PANEL_HEIGHT_FACTOR * 46` = 92), i.e. 138 — the same number the box is
    // capped at, so the clamp and the paint cannot disagree.
    const reserved = 46 + 2 * 46;
    expect(
      style,
      `the placement reserved a box of a different height than the one it caps: ${style}`,
    ).toContain(`max-height: max(100%, ${reserved}px)`);

    expect(
      top,
      `the pill was placed at top ${top}px. The window is 200px and the reserved box is ` +
        `${reserved}px tall, so the last coordinate that keeps its bottom edge on screen is ` +
        `200 - ${reserved} = ${200 - reserved}. A placement that only budgets the 46px ROW ` +
        `would let the panel that follows hang off the window.`,
    ).toBe(200 - reserved);
  });

  it('wraps even at a 5px window, and the clamp still keeps the box on screen', () => {
    // A band narrower than every rung. `wrapped` is the answer once the pill no
    // longer fits, and the placement clamp is what keeps the (now genuinely too
    // wide) box from starting at a negative coordinate — the "never leaves the
    // screen" invariant that has to hold at EVERY rung.
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 5, height: WINDOW_HEIGHT },
      selectionBounds: { left: 0, top: 120, right: 4, bottom: 140 },
      containerRect: { left: 0, top: 0, width: 5, height: 480 },
    });

    const row = container.querySelector('[role="toolbar"]');
    expect(row?.className).toContain('flex-wrap');
    const left = Number(
      /left:\s*(-?[\d.]+)px/.exec(
        container.querySelector('.selection-toolbar')?.getAttribute('style') ?? '',
      )?.[1],
    );
    expect(left).toBeGreaterThanOrEqual(0);
    // No action is lost at any rung.
    expect(getByLabelText('Define')).toBeTruthy();
    expect(getByLabelText('Change color')).toBeTruthy();
  });

  it('sizes the result panel to the menu the placement math was given', async () => {
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: COLLAPSED_WIDTH + 60, height: WINDOW_HEIGHT },
      selectedText: 'house',
      selectionData: {
        ...baseProps().selectionData,
        text: 'house',
      },
      onDefine: async () => ({
        word: 'house',
        definitions: [{ text: 'A building for living in.', partOfSpeech: 'noun' }],
        source: 'native' as const,
        attribution: 'a',
      }),
    });

    await fireEvent.click(getByLabelText('Define'));

    // The panel is a sibling of the placed menu, so it renders at the placed
    // width instead of the 320px `max-w-80` the full layout used.
    const panel = Array.from(container.querySelectorAll('div')).find((el) =>
      (el.getAttribute('style') ?? '').includes(`width: ${COLLAPSED_WIDTH}px`),
    );
    expect(panel).toBeTruthy();
  });
});
