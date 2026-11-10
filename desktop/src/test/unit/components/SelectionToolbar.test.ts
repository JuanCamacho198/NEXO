import { render, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';

const t = (key: string, params?: Record<string, string | number>) => {
  const translations: Record<string, string> = {
    'highlight.menuAriaLabel': 'Selection actions',
    // The component calls t('highlight.selectColor', { color: t('highlight.color.yellow') }),
    // so the placeholder MUST be present for the substituted aria-label
    // to actually contain the color name (e.g. "Select Yellow highlight").
    'highlight.selectColor': 'Select {{color}} highlight',
    'highlight.color.yellow': 'Yellow',
    'highlight.color.red': 'Red',
    'reader.copiar': 'Copy',
    'reader.copiedToClipboard': 'Copied to clipboard',
    'reader.addToDictionary': 'Add to Dictionary',
    'reader.addedToDictionary': 'Saved',
    'reader.dictionaryEvidenceUpdated': 'Evidence updated',
    'reader.dictionaryNoMatch': 'No dictionary entry matches this selection',
    'reader.dictionarySeveralMatches': 'Several entries match - select a single word',
    'reader.dictionaryAlreadyInDictionary': 'Already in your dictionary',
    'reader.translate': 'Translate',
    'reader.translating': 'Translating…',
    'reader.translationNoResult': 'No translation available for this selection',
    'reader.translationUnavailable': 'Translation service unavailable',
    'reader.dismissTranslation': 'Close translation',
    'reader.define': 'Define',
    'reader.defining': 'Looking up…',
    'reader.definitionNoResult': 'No definition found for this selection.',
    'reader.definitionUnavailable': 'The dictionary could not be reached.',
    'reader.definitionTranslated': 'Machine-translated definition',
    'reader.dismissDefinition': 'Close definition',
    'error.somethingWrong': 'Something went wrong',
  };
  let value = translations[key] ?? key;
  if (params) {
    for (const [paramKey, paramValue] of Object.entries(params)) {
      value = value.replace(`{{${paramKey}}}`, String(paramValue));
    }
  }
  return value;
};

function makeSelectionData(overrides: Partial<SelectionData> = {}): SelectionData {
  return {
    text: 'Selection text',
    bounds: { left: 40, top: 120, right: 160, bottom: 140 },
    rects: [{ left: 40, top: 120, width: 120, height: 20 }],
    pageNumber: 1,
    cfi: null,
    ...overrides,
  };
}

const baseProps = () => ({
  selectedText: 'Selection text',
  selectionBounds: { left: 40, top: 120, right: 160, bottom: 140 },
  containerRect: { left: 100, top: 200, width: 320, height: 480 },
  selectionData: makeSelectionData(),
  onCopy: () => undefined,
  onAddToDictionary: async (): Promise<DictionaryCaptureFeedback> => 'created',
  onTranslate: async (): Promise<TranslationOutcome> => ({
    status: 'ok',
    text: 'Translated',
    source: 'en',
    target: 'es',
    sourceOrigin: 'book',
  }),
  onChangeLanguages: async () => undefined,
  onDefine: async () => ({
    word: 'Selection text',
    definitions: [{ text: 'a definition', partOfSpeech: null }],
    source: 'native' as const,
    attribution: 'Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)',
  }),
  onColorSelect: () => undefined,
  t,
});

describe('SelectionToolbar', () => {
  it('positions within the window using the container-offset selection bounds', () => {
    // Selection center in window space = ((100+40) + (100+160)) / 2 = 200.
    // jsdom's 1024px window leaves 992px of room, so the full 380px row fits and
    // no collapse happens. Centered at 200 it would span 10..390 — and 10 is
    // inside the 16px edge padding, so the clamp pins it to 16.
    // Y keeps the above-the-selection rule, against the height the placement
    // actually reserves: with no result panel open that is the row alone — the
    // MEASURED 46px pill — plus the 8px gap: 320 - 46 - 8 = 266.
    //
    // The panel's 120px is reserved only while a panel is on screen. Budgeting it
    // unconditionally is what pushed the menu ~130px away from the word (the
    // reported "the menu appears way up top, far from the selection").
    const { container } = render(SelectionToolbar, baseProps());

    const toolbar = container.querySelector('.selection-toolbar');
    expect(toolbar).toBeTruthy();
    expect(toolbar?.getAttribute('style')).toContain('left: 16px');
    expect(toolbar?.getAttribute('style')).toContain('top: 266px');
  });

  it('clamps the toolbar to the window edge padding', () => {
    // Selection sits at the far left of the window: center = (25 + 45) / 2 =
    // 35, so a centered menu would start at 35 - 190 = -155. It pins to the
    // 16px edge padding instead of going negative.
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      selectionBounds: { left: 5, top: 140, right: 25, bottom: 160 },
      containerRect: { left: 20, top: 100, width: 240, height: 400 },
    });

    const toolbar = container.querySelector('.selection-toolbar');
    expect(toolbar).toBeTruthy();
    expect(toolbar?.getAttribute('style')).toContain('left: 16px');
  });

  it('translates container offset into window space when containerRect.left > 0 (EPUB coords)', () => {
    // EPUB scenario: the iframe lives at left=200 inside the parent window and
    // the toolbar is `position: fixed`, so it is placed in WINDOW space. The
    // selection must be shifted by the container offset BEFORE clamping —
    // clamping container-relative coordinates against the window is the bug
    // this file's sibling (SelectionToolbarPlacement.test.ts) pins down.
    //
    // In this fixture:
    //   selection center in window space = ((100+100) + (200+160)) / 2 = 280
    //   centered menu starts at           280 - 140                   = 140
    //   140 is past the 16px edge padding, so no clamp
    //   toolbarY = 320 - 46 - 8 = 266, where 46 is the MEASURED row height and
    //   the 120px panel budget is not reserved because no panel is open
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      selectionBounds: { left: 100, top: 120, right: 160, bottom: 140 },
      containerRect: { left: 200, top: 200, width: 320, height: 480 },
    });

    const toolbar = container.querySelector('.selection-toolbar');
    expect(toolbar).toBeTruthy();
    const style = toolbar?.getAttribute('style') ?? '';
    expect(style).toContain('left: 140px');
    expect(style).toContain('top: 266px');
  });

  it('forwards the whole SelectionData — text and evidence — to onAddToDictionary', async () => {
    const onAddToDictionary = vi.fn(async () => 'created' as DictionaryCaptureFeedback);
    const data = makeSelectionData({
      text: 'ephemeral',
      quote: 'The ephemeral nature of the artefact was obvious.',
      chapterTitle: 'Chapter 3',
    });
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'ephemeral',
      selectionData: data,
      onAddToDictionary,
    });

    await fireEvent.click(getByLabelText('Add to Dictionary'));

    expect(onAddToDictionary).toHaveBeenCalledOnce();
    expect(onAddToDictionary).toHaveBeenCalledWith(data);
  });

  it('does not call onAddToDictionary when there is no selection data', async () => {
    const onAddToDictionary = vi.fn(async () => 'created' as DictionaryCaptureFeedback);
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: null,
      onAddToDictionary,
    });

    await fireEvent.click(getByLabelText('Add to Dictionary'));

    expect(onAddToDictionary).not.toHaveBeenCalled();
  });

  it('does not call onAddToDictionary for a whitespace-only selection', async () => {
    const onAddToDictionary = vi.fn(async () => 'created' as DictionaryCaptureFeedback);
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: makeSelectionData({ text: '   ' }),
      onAddToDictionary,
    });

    await fireEvent.click(getByLabelText('Add to Dictionary'));

    expect(onAddToDictionary).not.toHaveBeenCalled();
  });

  // ── Dictionary capture feedback (REQ-DRE-014) ──────────────────────────
  // The parent resolves the selection and returns one `DictionaryCaptureFeedback`
  // value; the toolbar owes the user a localized message for every one of them.

  it.each([
    ['created', 'Saved'],
    ['evidence-updated', 'Evidence updated'],
    ['already-in-dictionary', 'Already in your dictionary'],
    ['no-match', 'No dictionary entry matches this selection'],
    ['ambiguous', 'Several entries match - select a single word'],
    ['error', 'Something went wrong'],
  ] as const)('maps the %s feedback to its localized message', async (feedback, message) => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onAddToDictionary: async () => feedback,
    });

    await fireEvent.click(getByLabelText('Add to Dictionary'));

    expect(queryByText(message)).toBeTruthy();
  });

  it('calls onCopy and shows copy feedback when copy button clicked', async () => {
    const onCopy = vi.fn();
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'hello',
      onCopy,
    });

    // Feedback hidden before click
    expect(queryByText('Copied to clipboard')).toBeNull();

    const button = getByLabelText('Copy');
    await fireEvent.click(button);

    expect(onCopy).toHaveBeenCalledOnce();
    expect(queryByText('Copied to clipboard')).toBeTruthy();
  });

  it('renders SVG icons and tooltips for copy, dictionary, translate and define actions', () => {
    const { getByLabelText, getAllByRole } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'hello',
      selectionData: makeSelectionData({ text: 'hello' }),
    });

    // All action buttons must be present with proper aria-labels
    expect(getByLabelText('Copy')).toBeTruthy();
    expect(getByLabelText('Add to Dictionary')).toBeTruthy();
    expect(getByLabelText('Translate')).toBeTruthy();
    expect(getByLabelText('Define')).toBeTruthy();

    // Each icon-only button has an associated tooltip
    const tooltips = getAllByRole('tooltip');
    expect(tooltips).toHaveLength(4);
    expect(tooltips[0]).toHaveTextContent('Copy');
    expect(tooltips[1]).toHaveTextContent('Add to Dictionary');
    expect(tooltips[2]).toHaveTextContent('Translate');
    expect(tooltips[3]).toHaveTextContent('Define');
  });

  // ── Selection data forwarding (race-condition fix) ─────────────────────
  // The toolbar captures selectionData at mount time and forwards it to
  // onColorSelect on click. This way the parent's handler has the data it
  // needs even if its global selection state has been cleared by a
  // selectionchange event in the meantime.

  it('forwards selectionData to onColorSelect when a color is clicked', async () => {
    const onColorSelect = vi.fn();
    const data = makeSelectionData({
      text: 'ephemeral',
      cfi: 'epubcfi(/6/4!/4/2/1:0)',
      pageNumber: 7,
      rects: [{ left: 1, top: 2, width: 30, height: 4 }],
    });
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: data,
      onColorSelect,
    });

    // The color buttons have aria-label "Select color highlight <color>".
    const yellowBtn = container.querySelector('button[aria-label*="Yellow"]') as HTMLButtonElement;
    expect(yellowBtn).toBeTruthy();
    await fireEvent.click(yellowBtn);

    expect(onColorSelect).toHaveBeenCalledOnce();
    const [color, forwarded] = onColorSelect.mock.calls[0];
    expect(color).toBe('#FACC15'); // HIGHLIGHT_COLORS[0].hex
    expect(forwarded).toEqual(data);
  });

  it('does not call onColorSelect when selectionData is null', async () => {
    const onColorSelect = vi.fn();
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: null,
      onColorSelect,
    });

    const yellowBtn = container.querySelector('button[aria-label*="Yellow"]') as HTMLButtonElement;
    expect(yellowBtn).toBeTruthy();
    await fireEvent.click(yellowBtn);

    expect(onColorSelect).not.toHaveBeenCalled();
  });

  // ── Translation (T5) ───────────────────────────────────────────────────
  // The toolbar is presentational: it forwards the selection to `onTranslate`
  // and maps the discriminated outcome to localized copy. `no-result` and
  // `unavailable` must stay distinct — one is the service answering, the other
  // is the service failing.

  it('forwards the whole SelectionData to onTranslate and shows the result', async () => {
    const onTranslate = vi.fn(
      async () =>
        ({
          status: 'ok',
          text: 'and',
          source: 'en',
          target: 'es',
          sourceOrigin: 'book',
        }) as TranslationOutcome,
    );
    const data = makeSelectionData({ text: 'y' });
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: data,
      onTranslate,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(onTranslate).toHaveBeenCalledOnce();
    expect(onTranslate).toHaveBeenCalledWith(data);
    expect(queryByText('and')).toBeTruthy();
  });

  it('shows the no-result copy when the service answered without a translation', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () => ({ status: 'no-result' }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(queryByText('No translation available for this selection')).toBeTruthy();
  });

  it('shows the unavailable copy — not no-result — when the request failed', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () => ({ status: 'unavailable' }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(queryByText('Translation service unavailable')).toBeTruthy();
    expect(queryByText('No translation available for this selection')).toBeNull();
  });

  it('does not call onTranslate when there is no selection', async () => {
    const onTranslate = vi.fn(
      async () =>
        ({
          status: 'ok',
          text: 'x',
          source: 'en',
          target: 'es',
          sourceOrigin: 'book',
        }) as TranslationOutcome,
    );
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: null,
      onTranslate,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(onTranslate).not.toHaveBeenCalled();
  });

  it('does not call onTranslate for a whitespace-only selection', async () => {
    const onTranslate = vi.fn(
      async () =>
        ({
          status: 'ok',
          text: 'x',
          source: 'en',
          target: 'es',
          sourceOrigin: 'book',
        }) as TranslationOutcome,
    );
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: makeSelectionData({ text: '   ' }),
      onTranslate,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(onTranslate).not.toHaveBeenCalled();
  });

  it('a second click clears the panel without re-requesting', async () => {
    const onTranslate = vi.fn(
      async () =>
        ({
          status: 'ok',
          text: 'and',
          source: 'en',
          target: 'es',
          sourceOrigin: 'book',
        }) as TranslationOutcome,
    );
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate,
    });

    await fireEvent.click(getByLabelText('Translate'));
    expect(queryByText('and')).toBeTruthy();

    await fireEvent.click(getByLabelText('Translate'));
    expect(queryByText('and')).toBeNull();
    expect(onTranslate).toHaveBeenCalledOnce();
  });

  it('closes the result panel from its dismiss button', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () =>
        ({
          status: 'ok',
          text: 'and',
          source: 'en',
          target: 'es',
          sourceOrigin: 'book',
        }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));
    expect(queryByText('and')).toBeTruthy();

    await fireEvent.click(getByLabelText('Close translation'));
    expect(queryByText('and')).toBeNull();
  });
});
