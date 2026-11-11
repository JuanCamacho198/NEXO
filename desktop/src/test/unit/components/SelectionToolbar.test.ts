import { render, fireEvent, screen, waitFor } from '@testing-library/svelte';
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
    // The refusal copy, including the per-provenance variants the toolbar picks
    // through `translationRefusalKey` (T15).
    'reader.translationSameLanguage':
      'The book is already in this language, so there is nothing to translate',
    'reader.translationSameLanguageChosen':
      'The language you chose for the source is the same as the target. Change either one.',
    'reader.translationUnknownSource':
      'We could not detect the language of the selected text. Choose a source language in Settings › Translation.',
    'reader.translationInvalidSource':
      'The source language in Settings is not valid. Choose another one.',
    'reader.translationInvalidSourceChosen':
      'The source language you chose is not valid. Choose another one.',
    'reader.translationInvalidTarget':
      'The target language in Settings is not valid. Choose another one.',
    'reader.dismissTranslation': 'Close translation',
    'reader.define': 'Define',
    'reader.defining': 'Looking up…',
    'reader.definitionNoResult': 'No definition found for this selection.',
    'reader.definitionUnavailable': 'The dictionary could not be reached.',
    'reader.definitionTranslated': 'Machine-translated definition',
    'reader.dismissDefinition': 'Close definition',
    'error.somethingWrong': 'Something went wrong',
    // The language bar's own copy. `LanguageBar` reads these and the pair's
    // automatic labels; the language NAMES are deliberately absent so
    // `languageLabel` falls back to the uppercased code (`ES`, `FR`, …), which is
    // deterministic and does not depend on the app's language table.
    'settings.translation.sourceLabel': 'Source language',
    'settings.translation.targetLabel': 'Target language',
    'settings.translation.sourceAuto': 'Automatic',
    'settings.translation.targetAuto': 'Automatic',
    'reader.translation.sourceAuto': 'Automatic (detect)',
    'reader.translation.autoWithLanguage': 'Automatic ({{language}})',
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

  // ── Refusal copy, split by provenance (T15) ────────────────────────────
  // A refusal whose source the READER chose must never be reported as a
  // detection failure: there was nothing to detect, and the copy has to name
  // the choice as something they can revise instead.

  it('names a chosen source as a choice when it collides with the target', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () =>
        ({
          status: 'refused',
          reason: 'same-language',
          source: 'es',
          target: 'es',
          sourceOrigin: 'preference',
        }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(
      queryByText(
        'The language you chose for the source is the same as the target. Change either one.',
      ),
    ).toBeTruthy();
    expect(
      queryByText('The book is already in this language, so there is nothing to translate'),
    ).toBeNull();
  });

  it('names a per-book choice as a choice when it collides with the target', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () =>
        ({
          status: 'refused',
          reason: 'same-language',
          source: 'es',
          target: 'es',
          sourceOrigin: 'bookPreference',
        }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(
      queryByText(
        'The language you chose for the source is the same as the target. Change either one.',
      ),
    ).toBeTruthy();
  });

  it('keeps the detection copy for an inferred source that collided', async () => {
    // The collision was the APP's inference, so the existing book copy stands —
    // telling the reader to revise a choice they never made would be a lie.
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () =>
        ({
          status: 'refused',
          reason: 'same-language',
          source: 'es',
          target: 'es',
          sourceOrigin: 'book',
        }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(
      queryByText('The book is already in this language, so there is nothing to translate'),
    ).toBeTruthy();
  });

  it('keeps the settings copy for an unusable source with no provenance', async () => {
    // No origin resolved, so the toolbar has nothing to call a choice: the copy
    // points back at Settings, which is the only place that value can live.
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () =>
        ({
          status: 'refused',
          reason: 'invalid-source',
          source: null,
          target: 'es',
          sourceOrigin: null,
        }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(
      queryByText('The source language in Settings is not valid. Choose another one.'),
    ).toBeTruthy();
  });

  it('names an unusable chosen source as a choice', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      onTranslate: async () =>
        ({
          status: 'refused',
          reason: 'invalid-source',
          source: null,
          target: 'es',
          sourceOrigin: 'preference',
        }) as TranslationOutcome,
    });

    await fireEvent.click(getByLabelText('Translate'));

    expect(
      queryByText('The source language you chose is not valid. Choose another one.'),
    ).toBeTruthy();
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

  // ── Language bar: the change payload (T6) ───────────────────────────────
  //
  // The bar reports the WHOLE pair it is showing plus `changed`, which names the
  // side the reader actually moved. Both parts are load-bearing:
  //
  //   - the pair is what the caller must be able to reconcile, and
  //   - `changed` is what it is allowed to persist.
  //
  // The regression these pin is a WRITE bug, not a rendering bug: the previous
  // payload was the resolved pair with no side marker, so the reader's single
  // change to ONE side was persisted as a choice on BOTH. The measured
  // consequence was a degenerate `es -> es` stored as an explicit preference,
  // which pinned `sourceOrigin` to `preference`, disabled the book/detection
  // fallback for the account, and made every Spanish selection refuse as
  // `same-language` with no way out from the UI.
  //
  // These assert the BAR's half of the contract: it must say which side moved
  // and must not silently drop a side. What the parent then writes is
  // `ReaderWorkspace`'s half and is pinned there.

  /**
   * Drives the bar's selector the way a reader does: open the trigger, then
   * press the option. bits-ui opens on `pointerdown` and commits on the press,
   * which is the pattern already used for the settings selectors.
   */
  async function pickSide(container: HTMLElement, sideIndex: number, label: string): Promise<void> {
    const triggers = Array.from(
      container.querySelectorAll<HTMLElement>('[aria-haspopup="listbox"]'),
    );
    expect(triggers.length).toBe(2); // source, then target
    const trigger = triggers[sideIndex];

    await fireEvent.pointerDown(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await fireEvent.pointerUp(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeNull());

    const option = screen.getAllByRole('option').find((item) => item.textContent?.trim() === label);
    expect(option, `option "${label}" not found`).toBeTruthy();

    await fireEvent.pointerDown(option as HTMLElement, {
      pointerId: 1,
      pointerType: 'mouse',
      button: 0,
    });
    await fireEvent.pointerUp(option as HTMLElement, {
      pointerId: 1,
      pointerType: 'mouse',
      button: 0,
    });
    await fireEvent.click(option as HTMLElement);
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
  }

  /**
   * The change callback the bar reports through. Typed so a payload assertion is
   * checked against the real contract rather than against a zero-arg mock.
   */
  function languageChangeSpy() {
    return vi.fn<
      (next: {
        source: string | null;
        target: string | null;
        changed: 'source' | 'target';
      }) => Promise<void>
    >(async () => undefined);
  }

  /** Opens the translation panel so the bar — and its two selectors — exist. */
  async function openLanguageBar(
    onChangeLanguages: (next: {
      source: string | null;
      target: string | null;
      changed: 'source' | 'target';
    }) => Promise<void>,
  ): Promise<HTMLElement> {
    const { container, getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      onChangeLanguages,
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
    // The bar renders once the outcome lands, which is where the two selectors
    // come from. Waiting on the triggers is the honest condition — the panel is
    // open before that, with no bar in it.
    await waitFor(() =>
      expect(container.querySelectorAll('[aria-haspopup="listbox"]').length).toBe(2),
    );
    return container;
  }

  it('marks a source change as the source side, carrying the pair shown', async () => {
    const onChangeLanguages = languageChangeSpy();
    const container = await openLanguageBar(onChangeLanguages);

    await pickSide(container, 0, 'FR');

    expect(onChangeLanguages).toHaveBeenCalledOnce();
    expect(onChangeLanguages).toHaveBeenCalledWith({
      source: 'fr',
      target: 'es',
      changed: 'source',
    });
  });

  it('marks a target change as the target side, carrying the pair shown', async () => {
    const onChangeLanguages = languageChangeSpy();
    const container = await openLanguageBar(onChangeLanguages);

    await pickSide(container, 1, 'FR');

    expect(onChangeLanguages).toHaveBeenCalledOnce();
    expect(onChangeLanguages).toHaveBeenCalledWith({
      source: 'en',
      target: 'fr',
      changed: 'target',
    });
  });

  it('reports Automatic as null on the side the reader cleared', async () => {
    // The untouched side keeps the resolved code — the bar shows the whole pair,
    // and the caller separates "moved" from "shown" by `changed`, not by null.
    //
    // The SOURCE entry's label is the bar's own (`Automatic (detect)`), not the
    // Settings pair's: choosing it clears whatever source was picked for THIS
    // book, so it must not read as the Settings default.
    const onChangeLanguages = languageChangeSpy();
    const container = await openLanguageBar(onChangeLanguages);

    await pickSide(container, 0, 'Automatic (detect)');

    expect(onChangeLanguages).toHaveBeenCalledWith({
      source: null,
      target: 'es',
      changed: 'source',
    });
  });

  it('never sends the same side as changed when the target moved', async () => {
    const onChangeLanguages = languageChangeSpy();
    const container = await openLanguageBar(onChangeLanguages);

    await pickSide(container, 1, 'Automatic');

    expect(onChangeLanguages).toHaveBeenCalledOnce();
    expect(onChangeLanguages).toHaveBeenCalledWith({
      source: 'en',
      target: null,
      changed: 'target',
    });
  });
});
