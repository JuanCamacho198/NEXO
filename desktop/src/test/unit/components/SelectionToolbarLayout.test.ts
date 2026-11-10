import { render, fireEvent } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';

// The width cascade, and the one thing about a narrow window that has to be
// true at EVERY rung: the toolbar stays on screen.
//
// The ladder itself (`resolveFloatingMenuLayout`) is pinned in
// `services/floatingMenuPlacement.test.ts`. What only a component test can add
// is that the RENDERED pill agrees with the rung — and that the placement never
// produces a negative or out-of-window coordinate, which is what a person sees.
//
// The viewport is a prop rather than `window.innerWidth` on purpose (see the
// component's docstring): a `$derived` that reads the ambient `window` is
// cached for the module's lifetime, so the second mount in a test process would
// silently re-use the first mount's width. Passing it keeps each case measured
// against its own band.

const t = (key: string, params?: Record<string, string | number>) => {
  const translations: Record<string, string> = {
    'highlight.menuAriaLabel': 'Selection actions',
    'highlight.selectColor': 'Select {{color}} highlight',
    'highlight.color.yellow': 'Yellow',
    'reader.copiar': 'Copy',
    'reader.addToDictionary': 'Add to Dictionary',
    'reader.translate': 'Translate',
    'reader.translating': 'Translating…',
    'reader.translationNoResult': 'No translation available for this selection',
    'reader.translationUnavailable': 'Translation service unavailable',
    'reader.dismissTranslation': 'Close translation',
    'reader.define': 'Define',
    'reader.dismissDefinition': 'Close definition',
  };
  let value = translations[key] ?? key;
  if (params) {
    for (const [paramKey, paramValue] of Object.entries(params)) {
      value = value.replace(`{{${paramKey}}}`, String(paramValue));
    }
  }
  return value;
};

const baseProps = () => ({
  selectedText: 'Selection text',
  selectionBounds: { left: 40, top: 600, right: 160, bottom: 620 },
  containerRect: { left: 0, top: 0, width: 600, height: 400 },
  selectionData: {
    text: 'Selection text',
    bounds: { left: 40, top: 600, right: 160, bottom: 620 },
    rects: [{ left: 40, top: 600, width: 120, height: 20 }],
    pageNumber: 1,
    cfi: null,
  } satisfies SelectionData,
  onCopy: () => undefined,
  onAddToDictionary: async (): Promise<DictionaryCaptureFeedback> => 'created',
  onTranslate: async (): Promise<TranslationOutcome> => ({
    status: 'ok',
    text: 'x',
    source: 'en',
    target: 'es',
  }),
  onChangeLanguages: async () => undefined,
  onDefine: async () => ({
    word: 'w',
    definitions: [],
    source: 'native' as const,
    attribution: 'a',
  }),
  onColorSelect: () => undefined,
  t,
});

/** The toolbar's inline placement, and the pill's own class list. */
function readToolbar(container: HTMLElement): {
  left: number;
  width: number;
  rowClasses: string[];
} {
  const toolbar = container.querySelector('.selection-toolbar') as HTMLElement | null;
  const style = toolbar?.getAttribute('style') ?? '';
  const row = toolbar?.querySelector('[role="toolbar"]');
  return {
    left: Number(/left:\s*(-?[\d.]+)px/.exec(style)?.[1]),
    width: Number(/width:\s*(-?[\d.]+)px/.exec(style)?.[1]),
    rowClasses: (row?.getAttribute('class') ?? '').split(/\s+/).filter(Boolean),
  };
}

/** Every color circle in the main row — the five-swatch palette. */
function paletteSwatches(container: HTMLElement): number {
  const row = container.querySelector('[role="toolbar"]');
  const first = row?.firstElementChild;
  return first ? first.querySelectorAll('button').length : 0;
}

describe('SelectionToolbar width cascade', () => {
  it('renders the full five-swatch row in a normal window', () => {
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 1200, height: 800 },
    });

    expect(paletteSwatches(container)).toBe(5);
    expect(readToolbar(container).rowClasses).not.toContain('flex-wrap');
  });

  it('collapses the palette into one trigger when the full row no longer fits', () => {
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 360, height: 800 },
    });

    // One trigger instead of five swatches, and it opens the same palette.
    expect(paletteSwatches(container)).toBe(1);
    expect(readToolbar(container).rowClasses).not.toContain('flex-wrap');
  });

  it('wraps the row when the band holds neither the full row nor the pill', () => {
    // 220px is under the 240px collapsed pill, so the last rung is the answer:
    // the row breaks onto two lines and the palette folds into one trigger. The
    // placement still has to keep the box on screen.
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 220, height: 800 },
    });

    const { left, rowClasses } = readToolbar(container);
    expect(paletteSwatches(container)).toBe(1);
    expect(rowClasses).toContain('flex-wrap');
    expect(left).toBeGreaterThanOrEqual(0);
    expect(left).toBeLessThanOrEqual(220);
  });

  it('wraps once the pill cannot fit, at every band below it', () => {
    // `wrapped` is the last rung, reached only when BOTH the full row and the
    // collapsed pill are too wide for the band. This pins the cascade as
    // full -> collapsed -> wrapped, each rung genuinely narrower than the one
    // before, with no case that stops on a rung it cannot hold.
    for (const width of [240, 220, 140, 5]) {
      const { container } = render(SelectionToolbar, {
        ...baseProps(),
        viewport: { width, height: 800 },
      });

      const { rowClasses, left } = readToolbar(container);
      expect(paletteSwatches(container), `at a ${width}px window the palette still folds`).toBe(1);
      expect(rowClasses, `at a ${width}px window the row must wrap`).toContain('flex-wrap');
      // The clamp pins to the edge padding and never goes negative.
      expect(left).toBeGreaterThanOrEqual(0);
    }
  });

  it('keeps the wrapped row at the actions floor rather than shrinking to a sliver', () => {
    // A 140px window is under the pill, so the wrapped row is chosen — and the
    // caller's `min-w-37` floor keeps it from collapsing to whatever the longest
    // label happens to be. The placement pins it rather than going negative.
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      selectionBounds: { left: 560, top: 600, right: 600, bottom: 620 },
      viewport: { width: 140, height: 800 },
    });

    const { left, rowClasses } = readToolbar(container);
    expect(rowClasses).toContain('min-w-37');
    expect(left).toBeGreaterThanOrEqual(0);
    expect(left).toBeLessThanOrEqual(140);
  });

  it('stays inside a viewport narrower than its own edge padding', () => {
    // 20px wide leaves no usable band at all (`floatingMenuAvailableWidth` is
    // 0). The clamp still has to answer with a coordinate a person can see.
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 20, height: 800 },
    });

    const { left } = readToolbar(container);
    expect(left).toBeGreaterThanOrEqual(0);
    expect(left).toBeLessThanOrEqual(20);
  });

  it('keeps the whole box on screen vertically when the selection sits at the bottom', () => {
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 1200, height: 700 },
    });

    const toolbar = container.querySelector('.selection-toolbar') as HTMLElement | null;
    const top = Number(/top:\s*(-?[\d.]+)px/.exec(toolbar?.getAttribute('style') ?? '')?.[1]);
    expect(top).toBeGreaterThanOrEqual(0);
    expect(top).toBeLessThanOrEqual(700);
  });
});

describe('SelectionToolbar result panels', () => {
  it('opens the translation panel below the pill at the menu width', async () => {
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 1200, height: 800 },
    });

    await fireEvent.click(getByLabelText('Translate'));

    const panel = container.querySelector('[role="status"]') as HTMLElement | null;
    expect(panel?.getAttribute('style')).toContain('max-height:');
    expect(panel?.className).toContain('overflow-y-auto');
  });

  it('never lets a panel be wider than the window', async () => {
    // A 298px window is the narrowest the desktop app was ever measured at. The
    // collapsed pill fits there; the 320px panel it used to render did not.
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 298, height: 800 },
    });

    await fireEvent.click(getByLabelText('Translate'));

    const panel = container.querySelector('[role="status"]') as HTMLElement;
    const width = Number(/width:\s*(-?[\d.]+)px/.exec(panel.getAttribute('style') ?? '')?.[1]);
    expect(width).toBeLessThanOrEqual(298);
    expect(width).toBeGreaterThan(0);
  });

  it('bounds the panel by the room left in the window, not by a fixed guess', async () => {
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      selectionBounds: { left: 40, top: 20, right: 160, bottom: 40 },
      viewport: { width: 1200, height: 300 },
    });

    await fireEvent.click(getByLabelText('Translate'));

    const panel = container.querySelector('[role="status"]') as HTMLElement;
    const maxHeight = Number(
      /max-height:\s*(-?[\d.]+)px/.exec(panel.getAttribute('style') ?? '')?.[1],
    );
    expect(maxHeight).toBeGreaterThan(0);
    expect(maxHeight).toBeLessThanOrEqual(300);
  });
});
