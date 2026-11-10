import { fireEvent, render } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import {
  reservesResultPanelSpace,
  showsShortFeedback,
  showsTooltips,
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

// The reported defect: "cuando le doy clic... se combina el mensaje de la acción
// con el del resultado". The tooltips revealed on `group-hover` AND
// `group-focus-within`, and pressing a button focuses it — so "Copiar" (the
// hint) and "Copiado al portapapeles" (the answer) were drawn in the same band
// below the pill and read as one unreadable smear.
//
// The rule the reader decided: the tooltip may show while there is nothing below
// the pill (that is what hovering a button is for, and `group-focus-within` is
// the keyboard reader's only way to see it), and it must NOT show while any
// panel — feedback or result — is up. The reveal stays; the overlap goes.

describe('selectionPanelReservation — when a tooltip may show', () => {
  it('allows the tooltip while nothing is below the pill', () => {
    expect(
      showsTooltips(noPanel()),
      'with no answer on screen the tooltip must be allowed — otherwise hovering a button tells ' +
        'the reader nothing, and `group-focus-within` cannot serve the keyboard at all',
    ).toBe(true);
  });

  it('suppresses the tooltip while Copy feedback is up', () => {
    // THE reported case. Pressing Copy sets `copyFeedback`; the button keeps
    // focus, `group-focus-within` would hold the tooltip open, and the message
    // below the pill collides with it.
    expect(showsTooltips({ ...noPanel(), copyFeedback: true })).toBe(false);
  });

  it('suppresses the tooltip while Dictionary feedback is up', () => {
    expect(showsTooltips({ ...noPanel(), dictionaryFeedback: true })).toBe(false);
  });

  it('suppresses the tooltip while Translate is working or showing its result', () => {
    expect(showsTooltips({ ...noPanel(), translating: true })).toBe(false);
    expect(showsTooltips({ ...noPanel(), translation: true })).toBe(false);
  });

  it('suppresses the tooltip while Define is working, succeeds or fails', () => {
    expect(showsTooltips({ ...noPanel(), defining: true })).toBe(false);
    expect(showsTooltips({ ...noPanel(), definition: true })).toBe(false);
    expect(showsTooltips({ ...noPanel(), definitionFailed: true })).toBe(false);
  });

  it('suppresses the tooltip for EVERY state that draws something below the pill', () => {
    // The table above is the rule; this is the rule stated as the invariant the
    // rule has to satisfy: a box is on screen exactly when the tooltip is off.
    for (const state of [
      { ...noPanel(), copyFeedback: true },
      { ...noPanel(), dictionaryFeedback: true },
      { ...noPanel(), translating: true },
      { ...noPanel(), translation: true },
      { ...noPanel(), defining: true },
      { ...noPanel(), definition: true },
      { ...noPanel(), definitionFailed: true },
    ]) {
      const drawsSomething = showsShortFeedback(state) || reservesResultPanelSpace(state);
      expect(
        showsTooltips(state),
        `a tooltip is allowed over a panel that drew something: ${JSON.stringify(state)}`,
      ).toBe(!drawsSomething);
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
  onTranslate: async (): Promise<TranslationOutcome> => ({
    status: 'ok',
    text: 'casa',
    source: 'en',
    target: 'es',
  }),
  onChangeLanguages: async () => undefined,
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

/**
 * The reveal classes on the tooltip spans, read straight from the DOM.
 *
 * These are the regression guard for the fix: the suppression must be a
 * CONDITION on the panel and not a deletion of the reveal, so the test asserts
 * both halves — present with no panel (hover works, and `group-focus-within`
 * still serves the keyboard), gone once a panel is up.
 */
function tooltipClasses(container: HTMLElement): string[] {
  return [...container.querySelectorAll('[role="tooltip"]')].map((el) => el.className);
}

describe('SelectionToolbar — the tooltip reveal follows the panel', () => {
  it('keeps the hover and focus-within reveal while nothing is below the pill', () => {
    const { container } = render(SelectionToolbar, baseProps());
    const classes = tooltipClasses(container);

    // Four action buttons in the full layout; the collapsed layout adds a fifth
    // for the color trigger. The point of the assertion is that tooltips are
    // PRESENT, not that the layout picker chose a specific rung.
    expect(classes.length, 'the toolbar rendered no tooltips at all').toBeGreaterThan(0);
    for (const className of classes) {
      // Hover is what the reader uses with a pointer...
      expect(
        className,
        `a tooltip lost its hover reveal — hovering the button would show nothing: ${className}`,
      ).toContain('group-hover:opacity-100');
      // ...and `focus-within` is the ONLY reveal a Tab reader gets. Removing it
      // to fix the overlap is the tempting shortcut and the accessibility
      // regression this assertion exists to block.
      expect(
        className,
        `a tooltip lost its focus reveal — a keyboard reader could never see it: ${className}`,
      ).toContain('group-focus-within:opacity-100');
    }
  });

  it('drops the reveal for every tooltip once Copy feedback is up', async () => {
    const { container, getByLabelText } = render(SelectionToolbar, baseProps());

    await fireEvent.click(getByLabelText('Copy'));

    // The answer is on screen...
    expect(container.querySelector('[role="status"]')).not.toBeNull();
    // ...and no tooltip is left revealing into the same band. The press left the
    // button focused, so without this the `group-focus-within` span would still
    // be at `opacity-100` on top of the message — the reported overlap.
    for (const className of tooltipClasses(container)) {
      expect(
        className,
        `a tooltip is still revealing while the Copy message is up: ${className}`,
      ).not.toContain('group-focus-within:opacity-100');
      expect(className).not.toContain('group-hover:opacity-100');
    }
  });

  it('drops the reveal once a Translate result is up', async () => {
    const { container, getByLabelText } = render(SelectionToolbar, baseProps());

    await fireEvent.click(getByLabelText('Translate'));
    await vi.waitFor(() => {
      expect(container.querySelector('[role="status"]')).not.toBeNull();
    });

    for (const className of tooltipClasses(container)) {
      expect(className).not.toContain('group-hover:opacity-100');
      expect(className).not.toContain('group-focus-within:opacity-100');
    }
  });

  it('restores the reveal when the Copy message clears itself', async () => {
    // The other half of "the tooltip must not stick": the focus left by the press
    // must not keep it suppressed forever. Once the message expires and the band
    // below the pill is empty again, the reveal is back — so the keyboard reader
    // who stays on the button gets their tooltip when the answer is gone.
    vi.useFakeTimers();
    try {
      const { container, getByLabelText } = render(SelectionToolbar, baseProps());

      await fireEvent.click(getByLabelText('Copy'));
      expect(tooltipClasses(container)[0]).not.toContain('group-focus-within:opacity-100');

      await vi.advanceTimersByTimeAsync(1_600);

      expect(
        container.querySelector('[role="status"]'),
        'the Copy message never cleared — the toolbar would keep suppressing tooltips',
      ).toBeNull();
      for (const className of tooltipClasses(container)) {
        expect(
          className,
          `the reveal did not come back after the message cleared: ${className}`,
        ).toContain('group-focus-within:opacity-100');
      }
    } finally {
      vi.useRealTimers();
    }
  });
});
