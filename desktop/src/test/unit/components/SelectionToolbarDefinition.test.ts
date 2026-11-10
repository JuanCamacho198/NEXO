import { render, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';
import type { DefinitionResult, DefinitionSource } from '$lib/shared/ports/DictionaryLookupPort';

const t = (key: string, params?: Record<string, string | number>) => {
  const translations: Record<string, string> = {
    'highlight.menuAriaLabel': 'Selection actions',
    'highlight.selectColor': 'Select {{color}} highlight',
    'highlight.color.yellow': 'Yellow',
    'reader.copiar': 'Copy',
    'reader.copiedToClipboard': 'Copied to clipboard',
    'reader.addToDictionary': 'Add to Dictionary',
    'reader.addedToDictionary': 'Saved',
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

const ATTRIBUTION = 'Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)';

function result(
  text: string,
  source: DefinitionSource | null,
  definitions: DefinitionResult['definitions'],
): DefinitionResult {
  return { word: text, definitions, source, attribution: ATTRIBUTION };
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
  }),
  onChangeLanguages: async () => undefined,
  onDefine: async (): Promise<DefinitionResult> =>
    result('Selection', 'native', [{ text: 'a definition', partOfSpeech: 'noun' }]),
  onColorSelect: () => undefined,
  t,
});

const nativeResult = () =>
  result('house', 'native', [{ text: 'A building for living in.', partOfSpeech: 'noun' }]);

const translatedResult = () =>
  result('juxtaposition', 'translated', [
    { text: 'La acción de poner cosas juntas.', partOfSpeech: 'sustantivo' },
  ]);

describe('SelectionToolbar — Definir button and C+A limit', () => {
  it('renders the Define action with its label and tooltip', () => {
    const { getByLabelText } = render(SelectionToolbar, baseProps());

    expect(getByLabelText('Define')).toBeTruthy();
  });

  it('enables Define for a single word', () => {
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
    });

    expect((getByLabelText('Define') as HTMLButtonElement).disabled).toBe(false);
  });

  it('enables Define for a two-word phrasal verb', () => {
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'give up',
      selectionData: makeSelectionData({ text: 'give up' }),
    });

    expect((getByLabelText('Define') as HTMLButtonElement).disabled).toBe(false);
  });

  it('disables Define for an eight-word phrase', () => {
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'this is a much longer phrase of eight words',
      selectionData: makeSelectionData({ text: 'this is a much longer phrase of eight words' }),
    });

    expect((getByLabelText('Define') as HTMLButtonElement).disabled).toBe(true);
  });

  it('disables Define for a lone punctuation mark', () => {
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: '.',
      selectionData: makeSelectionData({ text: '.' }),
    });

    expect((getByLabelText('Define') as HTMLButtonElement).disabled).toBe(true);
  });

  it('disables Define for digits with no letters', () => {
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: '123',
      selectionData: makeSelectionData({ text: '123' }),
    });

    expect((getByLabelText('Define') as HTMLButtonElement).disabled).toBe(true);
  });

  it('disables Define for a sentence', () => {
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'The house is big.',
      selectionData: makeSelectionData({ text: 'The house is big.' }),
    });

    expect((getByLabelText('Define') as HTMLButtonElement).disabled).toBe(true);
  });

  it('does not call onDefine when the button is disabled', async () => {
    const onDefine = vi.fn(async () => nativeResult());
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: '123',
      selectionData: makeSelectionData({ text: '123' }),
      onDefine,
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(onDefine).not.toHaveBeenCalled();
  });

  it('forwards the whole SelectionData to onDefine and shows the definitions', async () => {
    const onDefine = vi.fn(async () => nativeResult());
    const data = makeSelectionData({ text: 'house' });
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: data,
      onDefine,
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(onDefine).toHaveBeenCalledOnce();
    expect(onDefine).toHaveBeenCalledWith(data);
    expect(queryByText('A building for living in.')).toBeTruthy();
  });

  it('toggles the panel off on a second click without re-requesting', async () => {
    const onDefine = vi.fn(async () => nativeResult());
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine,
    });

    await fireEvent.click(getByLabelText('Define'));
    expect(queryByText('A building for living in.')).toBeTruthy();

    await fireEvent.click(getByLabelText('Define'));
    expect(queryByText('A building for living in.')).toBeNull();
    expect(onDefine).toHaveBeenCalledOnce();
  });
});

describe('SelectionToolbar — definition panel content', () => {
  it('shows the part of speech when the source carried one', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine: async () =>
        result('house', 'native', [{ text: 'A building.', partOfSpeech: 'noun' }]),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText('noun')).toBeTruthy();
  });

  it('omits the part of speech rather than rendering an empty badge', async () => {
    const { getByLabelText, container } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine: async () =>
        result('house', 'native', [{ text: 'A building.', partOfSpeech: null }]),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(container.querySelectorAll('[data-definition-pos]')).toHaveLength(0);
  });

  it('renders every definition line', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine: async () =>
        result('house', 'native', [
          { text: 'A building for living in.', partOfSpeech: 'noun' },
          { text: 'To contain or cover.', partOfSpeech: 'verb' },
        ]),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText('A building for living in.')).toBeTruthy();
    expect(queryByText('To contain or cover.')).toBeTruthy();
  });
});

describe('SelectionToolbar — the machine-translation mark', () => {
  // This is NOT cosmetic. A translated gloss is usually right and wrong in ways
  // the reader cannot see, so the reader has a right to know which they got.

  it('shows the translated mark when source is translated', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'juxtaposition',
      selectionData: makeSelectionData({ text: 'juxtaposition' }),
      onDefine: async () => translatedResult(),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText('Machine-translated definition')).toBeTruthy();
  });

  it('does NOT show the mark when source is native', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine: async () => nativeResult(),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText('Machine-translated definition')).toBeNull();
  });

  it('does NOT show the mark on an empty result', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'qqq',
      selectionData: makeSelectionData({ text: 'qqq' }),
      onDefine: async () => result('qqq', null, []),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText('Machine-translated definition')).toBeNull();
  });
});

describe('SelectionToolbar — attribution (CC BY-SA is a license obligation)', () => {
  it('renders the attribution the backend sent', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine: async () => nativeResult(),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText(ATTRIBUTION)).toBeTruthy();
  });

  it('renders the attribution on a translated result too', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'juxtaposition',
      selectionData: makeSelectionData({ text: 'juxtaposition' }),
      onDefine: async () => translatedResult(),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText(ATTRIBUTION)).toBeTruthy();
  });

  it('renders the attribution on a MISS — the license travels even with no text', async () => {
    // The backend always carries it, including on the empty result, precisely
    // so the UI cannot forget it. The component must not condition it on having
    // definitions to show.
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'qqq',
      selectionData: makeSelectionData({ text: 'qqq' }),
      onDefine: async () => result('qqq', null, []),
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText(ATTRIBUTION)).toBeTruthy();
  });
});

describe('SelectionToolbar — empty result and failure are different truths', () => {
  it('shows the understandable empty state when nothing was found', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'qqqzz',
      selectionData: makeSelectionData({ text: 'qqqzz' }),
      onDefine: async () => result('qqqzz', null, []),
    });

    await fireEvent.click(getByLabelText('Define'));

    // Useful copy, never a blank panel and never a technical error string.
    expect(queryByText('No definition found for this selection.')).toBeTruthy();
    expect(queryByText('The dictionary could not be reached.')).toBeNull();
  });

  it('shows the unreachable copy — not the empty state — when the request failed', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine: async () => {
        throw new Error('DEFINE_NETWORK: wiktionary request failed');
      },
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(queryByText('The dictionary could not be reached.')).toBeTruthy();
    expect(queryByText('No definition found for this selection.')).toBeNull();
  });

  it('closes the definition panel from its dismiss button', async () => {
    const { getByLabelText, queryByText } = render(SelectionToolbar, {
      ...baseProps(),
      selectedText: 'house',
      selectionData: makeSelectionData({ text: 'house' }),
      onDefine: async () => nativeResult(),
    });

    await fireEvent.click(getByLabelText('Define'));
    expect(queryByText('A building for living in.')).toBeTruthy();

    await fireEvent.click(getByLabelText('Close definition'));
    expect(queryByText('A building for living in.')).toBeNull();
  });

  it('does not call onDefine when there is no selection data', async () => {
    const onDefine = vi.fn(async () => nativeResult());
    const { getByLabelText } = render(SelectionToolbar, {
      ...baseProps(),
      selectionData: null,
      onDefine,
    });

    await fireEvent.click(getByLabelText('Define'));

    expect(onDefine).not.toHaveBeenCalled();
  });
});
