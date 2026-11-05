import { fireEvent, render } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import {
  reservesResultPanelSpace,
  showsShortFeedback,
  type SelectionPanelState,
} from '$lib/features/reader/highlight/selectionPanelReservation';
import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';

// The reported defect: "cuando le doy a cualquiera de los botones de copiar,
// traducir, definir... el menú sube hacia arriba y luego vuelve hacia abajo".
//
// The menu moved because `resultPanelOpen` put SIX states in one bag, and the
// placement reserved a panel's worth of height whenever ANY of them was set.
// Copy and Dictionary render a one-line message that disappears after 1.5s, so
// pressing Copy lifted the menu to make room for a panel that was never going to
// be tall, and dropped it back when the message expired.
//
// The rule the reader decided: Copiar and Diccionario SHOW feedback but do NOT
// move the menu; Traducir and Definir RESERVE space because their result is a
// real panel. That rule is a pure function, and these are its four cases.

const t = (key: string, params?: Record<string, string | number>) => {
  const translations: Record<string, string> = {
    'highlight.menuAriaLabel': 'Selection actions',
    'highlight.selectColor': 'Select {{color}} highlight',
    'highlight.color.yellow': 'Yellow',
    'highlight.color.green': 'Green',
    'highlight.color.blue': 'Blue',
    'highlight.color.orange': 'Orange',
    'highlight.color.red': 'Red',
    'reader.copiar': 'Copy',
    'reader.copiedToClipboard': 'Copied to clipboard',
    'reader.addToDictionary': 'Add to Dictionary',
    'reader.addedToDictionary': 'Saved',
    'reader.translate': 'Translate',
    'reader.translating': 'Translating…',
    'reader.define': 'Define',
    'reader.defining': 'Looking up…',
    'reader.dismissTranslation': 'Close translation',
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

/** Every state off — the menu with nothing below it. */
function noPanel(): SelectionPanelState {
  return {
    copyFeedback: false,
    dictionaryFeedback: false,
    translating: false,
    translation: false,
    defining: false,
    definition: false,
    definitionFailed: false,
  };
}

describe('selectionPanelReservation — the residence table', () => {
  it('does NOT reserve for Copy feedback', () => {
    expect(reservesResultPanelSpace({ ...noPanel(), copyFeedback: true })).toBe(false);
  });

  it('does NOT reserve for Dictionary feedback', () => {
    expect(reservesResultPanelSpace({ ...noPanel(), dictionaryFeedback: true })).toBe(false);
  });

  it('reserves for Translate, in both its states', () => {
    expect(reservesResultPanelSpace({ ...noPanel(), translating: true })).toBe(true);
    expect(reservesResultPanelSpace({ ...noPanel(), translation: true })).toBe(true);
  });

  it('reserves for Define, in all three of its states', () => {
    // `definitionFailed` is a separate flag from `definition` because a miss and
    // a failure are different truths — but both draw the same panel, so both
    // reserve.
    expect(reservesResultPanelSpace({ ...noPanel(), defining: true })).toBe(true);
    expect(reservesResultPanelSpace({ ...noPanel(), definition: true })).toBe(true);
    expect(reservesResultPanelSpace({ ...noPanel(), definitionFailed: true })).toBe(true);
  });

  it('reserves nothing when nothing is open', () => {
    expect(reservesResultPanelSpace(noPanel())).toBe(false);
    expect(showsShortFeedback(noPanel())).toBe(false);
  });

  it('shows short feedback for Copy and Dictionary, and only those', () => {
    // The render condition is the other half of the pair: a state that is in
    // NEITHER function renders nowhere at all — Copy's message would silently
    // vanish, which is the regression this test exists to make loud.
    for (const state of [
      { ...noPanel(), copyFeedback: true },
      { ...noPanel(), dictionaryFeedback: true },
      { ...noPanel(), translating: true },
      { ...noPanel(), translation: true },
      { ...noPanel(), defining: true },
      { ...noPanel(), definition: true },
      { ...noPanel(), definitionFailed: true },
    ]) {
      const rendered = showsShortFeedback(state) || reservesResultPanelSpace(state);
      expect(rendered, `a state renders nothing: ${JSON.stringify(state)}`).toBe(true);
    }
  });
});

const baseProps = () => ({
  selectedText: 'house',
  selectionBounds: { left: 40, top: 120, right: 160, bottom: 140 },
  containerRect: { left: 0, top: 0, width: 600, height: 400 },
  viewport: { width: 1024, height: 768 },
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

function toolbarStyle(container: HTMLElement): string {
  return container.querySelector('.selection-toolbar')?.getAttribute('style') ?? '';
}

function toolbarTop(container: HTMLElement): number {
  return Number(/top:\s*(-?[\d.]+)px/.exec(toolbarStyle(container))?.[1]);
}

/** The `max(100%, Npx)` ceiling the pill is capped at — the reserved box. */
function reservedHeight(container: HTMLElement): number {
  return Number(/max-height:\s*max\(100%,\s*(\d+)px\)/.exec(toolbarStyle(container))?.[1]);
}

describe('SelectionToolbar — feedback does not move the menu', () => {
  it('keeps the Copy feedback panel rendered', async () => {
    // The message must still appear. Only the RESERVATION was removed.
    const { container, getByLabelText } = render(SelectionToolbar, baseProps());

    await fireEvent.click(getByLabelText('Copy'));

    const status = container.querySelector('[role="status"]');
    expect(status, 'the copy feedback panel stopped rendering').not.toBeNull();
    expect(status!.textContent).toContain('Copied to clipboard');
  });

  it('does NOT reserve panel space when Copy feedback is up', async () => {
    const { container, getByLabelText } = render(SelectionToolbar, baseProps());

    const reservedBefore = reservedHeight(container);
    const topBefore = toolbarTop(container);

    await fireEvent.click(getByLabelText('Copy'));

    // The panel is on screen...
    expect(container.querySelector('[role="status"]')).not.toBeNull();
    // ...and yet the reserved box and the placement are byte-for-byte the ones
    // from before the press. Before this change both numbers moved: the box grew
    // by 120px and the menu was lifted by the same amount.
    expect(
      reservedHeight(container),
      'the reserved box grew for a one-line feedback message — this is the menu moving',
    ).toBe(reservedBefore);
    expect(
      toolbarTop(container),
      'the menu was repositioned by a Copy press. Copy shows a message, it does not reserve a panel',
    ).toBe(topBefore);
  });

  it('does NOT reserve panel space when Dictionary feedback is up', async () => {
    const { container, getByLabelText } = render(SelectionToolbar, baseProps());

    const reservedBefore = reservedHeight(container);
    const topBefore = toolbarTop(container);

    await fireEvent.click(getByLabelText('Add to Dictionary'));

    await vi.waitFor(() => {
      expect(container.querySelector('[role="status"]')).not.toBeNull();
    });
    expect(container.querySelector('[role="status"]')!.textContent).toContain('Saved');
    expect(reservedHeight(container)).toBe(reservedBefore);
    expect(toolbarTop(container)).toBe(topBefore);
  });

  it('DOES reserve panel space when Translate opens its result', async () => {
    // The accepted half: Translate's answer is a real panel, so the menu is
    // moved to make room for it. This case exists so the Copy fix cannot have
    // been bought by removing the reservation entirely.
    const { container, getByLabelText } = render(SelectionToolbar, baseProps());

    const reservedBefore = reservedHeight(container);
    const topBefore = toolbarTop(container);

    await fireEvent.click(getByLabelText('Translate'));
    await vi.waitFor(() => {
      expect(container.querySelector('[role="status"]')).not.toBeNull();
    });

    expect(
      reservedHeight(container),
      'Translate opened its result panel without reserving room for it — the panel will hang off ' +
        'the bottom edge',
    ).toBeGreaterThan(reservedBefore);
    expect(toolbarTop(container)).not.toBe(topBefore);
  });

  it('DOES reserve panel space when Define opens its result', async () => {
    const { container, getByLabelText } = render(SelectionToolbar, baseProps());

    const reservedBefore = reservedHeight(container);
    const topBefore = toolbarTop(container);

    await fireEvent.click(getByLabelText('Define'));
    await vi.waitFor(() => {
      expect(container.querySelector('[role="status"]')).not.toBeNull();
    });

    expect(reservedHeight(container)).toBeGreaterThan(reservedBefore);
    expect(toolbarTop(container)).not.toBe(topBefore);
  });
});

describe('SelectionToolbar — the feedback panel stays on screen', () => {
  it('bounds the short feedback panel by the room the pill leaves below it', async () => {
    // The consequence of "does not move the menu": with the pill no longer
    // lifted, a selection at the bottom edge leaves the message less room. Its
    // ceiling has to be the space that actually exists, not the 120px result
    // budget the placement reserves for a panel that is not there.
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      viewport: { width: 1024, height: 300 },
      selectionBounds: { left: 40, top: 260, right: 160, bottom: 280 },
      containerRect: { left: 0, top: 0, width: 600, height: 300 },
    });

    await fireEvent.click(getByLabelText('Copy'));

    const status = container.querySelector('[role="status"]') as HTMLElement;
    expect(status, 'the copy feedback panel stopped rendering').not.toBeNull();

    const maxHeight = Number(
      /max-height:\s*(-?[\d.]+)px/.exec(status.getAttribute('style') ?? '')?.[1],
    );
    const top = toolbarTop(container);
    // The pill is placed `above` a bottom-anchored selection. Its bottom edge plus
    // the panel's own top edge plus the panel's ceiling must not pass the window.
    const pillBottom = top + 46;
    expect(
      pillBottom + maxHeight,
      `the feedback panel is allowed to reach ${pillBottom + maxHeight}px in a ` +
        `${300}px window — the message would be drawn off the bottom edge`,
    ).toBeLessThanOrEqual(300);
  });
});

describe('SelectionToolbar — the placement transition', () => {
  it('declares a short top transition using the project duration and easing tokens', () => {
    const { container } = render(SelectionToolbar, baseProps());
    const className = container.querySelector('.selection-toolbar')?.className ?? '';

    expect(
      className,
      'the menu has no transition on its position, so a Translate / Define re-placement is an ' +
        'instant jump',
    ).toContain('transition-[top]');
    // The project's own tokens, from `tokens.css` `@theme`: 150ms and
    // cubic-bezier(0.4, 0, 0.2, 1). Not invented values.
    expect(className).toContain('duration-fast');
    expect(className).toContain('ease-smooth');
  });

  it('does not animate the position when the reader asked for reduced motion', () => {
    // The project's pattern (`ReaderTocPanel`, `SearchPanel`, `ShelfActionMenu`)
    // is to read the media query in the component and zero the duration by hand.
    // The global CSS block forces `transition-duration: 1ms` too, but the class
    // itself is what this asserts — an absent class cannot animate if the global
    // block is ever narrowed.
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;

    try {
      const { container } = render(SelectionToolbar, baseProps());
      const className = container.querySelector('.selection-toolbar')?.className ?? '';
      expect(
        className,
        'reduced motion was requested and the menu still animates its position',
      ).not.toContain('transition-[top]');
    } finally {
      window.matchMedia = original;
    }
  });
});
