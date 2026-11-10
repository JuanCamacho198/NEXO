import { render } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import SelectionToolbar, {
  type SelectionData,
} from '$lib/features/reader/highlight/SelectionToolbar.svelte';
import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';

// F3: the toolbar must never leave the window.
//
// The component's clamp compares the selection against `containerRect.width`
// — the EPUB iframe, not the window. When that iframe is offset to the right
// (or is simply as wide as the window), the container-relative clamp happily
// produces a parent-viewport X past `window.innerWidth`, and the menu opens
// half off-screen. jsdom's default window is 1024x768.
//
// The width is the MEASURED one (380px), not the 260px estimate that preceded
// it: measuring the live pill over CDP showed the constant was 118px short, so
// the clamp was letting a 378px row overflow an assumed 260px box. That is the
// defect these cases exist to catch, so they assert against the real width.
const TOOLBAR_MAX_WIDTH = 380;

const t = (key: string): string => key;

function styleOf(container: HTMLElement): { left: number; top: number } {
  const toolbar = container.querySelector('.selection-toolbar');
  const style = toolbar?.getAttribute('style') ?? '';
  const left = Number(/left:\s*(-?[\d.]+)px/.exec(style)?.[1]);
  const top = Number(/top:\s*(-?[\d.]+)px/.exec(style)?.[1]);
  return { left, top };
}

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
  onCopy: () => undefined,
  onAddToDictionary: async (): Promise<DictionaryCaptureFeedback> => 'created',
  onTranslate: async (): Promise<TranslationOutcome> => ({
    status: 'ok',
    text: 'x',
    source: 'en',
    target: 'es',
    sourceOrigin: 'book',
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

describe('SelectionToolbar window clamping', () => {
  it('keeps the toolbar inside the window when the selection is at the right edge', () => {
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      // The iframe occupies the right half of the window and the selection is
      // at its far right: container.left + centered toolbar lands past the
      // window's right border.
      containerRect: { left: 800, top: 100, width: 220, height: 400 },
      selectionBounds: { left: 200, top: 120, right: 220, bottom: 140 },
    });

    const { left } = styleOf(container);
    expect(left + TOOLBAR_MAX_WIDTH).toBeLessThanOrEqual(window.innerWidth);
  });

  it('keeps the toolbar inside the window when the selection is at the left edge', () => {
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      containerRect: { left: 0, top: 100, width: 220, height: 400 },
      selectionBounds: { left: 0, top: 120, right: 4, bottom: 140 },
    });

    expect(styleOf(container).left).toBeGreaterThanOrEqual(0);
  });

  it('keeps the toolbar on screen vertically when the selection is at the bottom', () => {
    const { container } = render(SelectionToolbar, {
      ...baseProps(),
      containerRect: { left: 100, top: 600, width: 320, height: 400 },
      selectionBounds: { left: 40, top: 700, right: 160, bottom: 740 },
    });

    const { top } = styleOf(container);
    expect(top).toBeGreaterThanOrEqual(0);
    expect(top).toBeLessThanOrEqual(window.innerHeight);
  });
});
