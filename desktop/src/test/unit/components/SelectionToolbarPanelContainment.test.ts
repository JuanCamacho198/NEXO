import { fireEvent, render } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';

// F5: the RESULT PANELS must obey the same containment the pill does.
//
// `definitionPanelWidth` and `resultPanelMaxHeight` are the two values that keep
// a result panel on screen. These cases read them back off the rendered panel in
// the conditions where the risk is real — a window too narrow for the natural
// panel, and an anchor at the bottom of a short one — rather than asserting the
// pill above them.

const t = (key: string): string => key;

const baseProps = () => ({
  selectedText: 'house',
  selectionBounds: { left: 40, top: 120, right: 160, bottom: 140 },
  containerRect: { left: 0, top: 0, width: 600, height: 400 },
  selectionData: {
    text: 'house',
    bounds: { left: 40, top: 120, right: 160, bottom: 140 },
    rects: [{ left: 40, top: 120, width: 120, height: 20 }],
    pageNumber: 1,
    cfi: null,
  } satisfies SelectionData,
  onCopy: () => undefined,
  onAddToDictionary: async (): Promise<DictionaryCaptureFeedback> => 'created',
  onTranslate: async (): Promise<TranslationOutcome> => ({ status: 'ok', text: 'casa' }),
  onDefine: async () => ({
    word: 'house',
    definitions: [{ text: 'A building for living in.', partOfSpeech: 'noun' }],
    source: 'native' as const,
    attribution: 'Wiktionary',
  }),
  onColorSelect: () => undefined,
  t,
});

function panelStyle(container: HTMLElement): string {
  const panel = container.querySelector('[role="status"]');
  return panel?.getAttribute('style') ?? '';
}

describe('SelectionToolbar result panels stay inside the window', () => {
  it('bounds the translation panel by the available band, not a fixed 320px', async () => {
    // A 298px window is the narrowest the desktop app was measured at. The
    // panel's natural width is 320, so it has to be given the band instead.
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 298, height: 800 },
    });

    await fireEvent.click(getByLabelText('reader.translate'));

    const width = Number(/width:\s*(-?[\d.]+)px/.exec(panelStyle(container))?.[1]);
    expect(width).toBeGreaterThan(0);
    expect(width).toBeLessThanOrEqual(298);
  });

  it('bounds the DEFINITION panel the same way', async () => {
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 298, height: 800 },
    });

    await fireEvent.click(getByLabelText('reader.define'));

    const width = Number(/width:\s*(-?[\d.]+)px/.exec(panelStyle(container))?.[1]);
    expect(width).toBeGreaterThan(0);
    expect(width).toBeLessThanOrEqual(298);
  });

  it('never lets a panel exceed the window height when the anchor sits at the bottom', async () => {
    // The pill is placed `above` a bottom-anchored selection, so the panel that
    // follows has only the pill's own top edge to spend. A fixed 320px ceiling
    // would be taller than the 300px window.
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectionBounds: { left: 40, top: 260, right: 160, bottom: 280 },
      containerRect: { left: 0, top: 0, width: 600, height: 300 },
      viewport: { width: 1200, height: 300 },
    });

    await fireEvent.click(getByLabelText('reader.translate'));

    const maxHeight = Number(/max-height:\s*(-?[\d.]+)px/.exec(panelStyle(container))?.[1]);
    expect(maxHeight).toBeGreaterThan(0);
    expect(maxHeight).toBeLessThanOrEqual(300);
  });
});
