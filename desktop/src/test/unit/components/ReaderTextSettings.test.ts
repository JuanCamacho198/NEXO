import { render, screen, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ReaderTextSettings from '$lib/features/reader/chrome/ReaderTextSettings.svelte';
import type { ReaderSettings } from '$lib/shared/types';

const t = (key: string) => key;

function makeSettings(overrides: Partial<ReaderSettings> = {}): ReaderSettings {
  return {
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3388ff',
    epub: { fontSize: 100, fontFamily: 'serif' },
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    verticalScrolling: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
    ...overrides,
  };
}

function makeProps(format: 'pdf' | 'epub') {
  return {
    open: true,
    format,
    readerSettings: makeSettings(),
    onSettingsChange: vi.fn(),
    t,
    onClose: vi.fn(),
  };
}

describe('ReaderTextSettings (U1) — every visible control works', () => {
  it('hides brightness/contrast sliders in EPUB (they never render there)', () => {
    render(ReaderTextSettings, makeProps('epub'));
    expect(screen.queryByLabelText('settings.reader.brightness')).toBeNull();
    expect(screen.queryByLabelText('settings.reader.contrast')).toBeNull();
  });

  it('keeps brightness/contrast sliders in PDF (the pipeline applies them)', () => {
    render(ReaderTextSettings, makeProps('pdf'));
    expect(screen.getByLabelText('settings.reader.brightness')).toBeInTheDocument();
    expect(screen.getByLabelText('settings.reader.contrast')).toBeInTheDocument();
  });

  it('renders no dead showHeader/showFooter/verticalScrolling switches', () => {
    render(ReaderTextSettings, makeProps('epub'));
    expect(screen.queryByLabelText('settings.reading.showHeader')).toBeNull();
    expect(screen.queryByLabelText('settings.reading.showFooter')).toBeNull();
    expect(screen.queryByLabelText('reader.vertical_scroll')).toBeNull();
  });
});

describe('ReaderTextSettings (U2) — honest font-size stepper', () => {
  function renderEpub(fontSize = 100) {
    const props = makeProps('epub');
    props.readerSettings = makeSettings({ epub: { fontSize, fontFamily: 'serif' } });
    render(ReaderTextSettings, props);
    return props;
  }

  it('shows the live value between − and +', () => {
    renderEpub(120);
    expect(screen.getByTestId('font-size-value')).toHaveTextContent('120%');
    expect(screen.getByTestId('font-size-dec')).toBeInTheDocument();
    expect(screen.getByTestId('font-size-inc')).toBeInTheDocument();
  });

  it('steps ±10 through onSettingsChange', async () => {
    const props = renderEpub(100);
    await fireEvent.click(screen.getByTestId('font-size-inc'));
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ epub: expect.objectContaining({ fontSize: 110 }) }),
    );
    await fireEvent.click(screen.getByTestId('font-size-dec'));
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ epub: expect.objectContaining({ fontSize: 90 }) }),
    );
  });

  it('disables at the 80/200 limits instead of silently doing nothing', () => {
    renderEpub(200);
    expect(screen.getByTestId('font-size-inc')).toBeDisabled();
    expect(screen.getByTestId('font-size-dec')).not.toBeDisabled();
  });

  it('has no lone header + button anymore', () => {
    renderEpub();
    expect(screen.getByTestId('font-size-dec')).toBeInTheDocument();
    expect(screen.getByTestId('font-size-inc')).toBeInTheDocument();
  });
});

describe('ReaderTextSettings (U3) — spacing reads at a glance', () => {
  function renderEpub(overrides: Record<string, unknown> = {}) {
    const base = {
      open: true,
      format: 'epub',
      readerSettings: {
        themeMode: 'paper',
        brightness: 100,
        contrast: 100,
        selectionColor: '#3388ff',
        epub: { fontSize: 100, fontFamily: 'serif' },
        lineHeight: 1.8,
        letterSpacing: 2,
        paragraphSpacing: 1,
        textAlign: 'left',
        direction: 'ltr',
        hyphenation: false,
        verticalScrolling: false,
        margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
        showHeader: true,
        showFooter: true,
        showPageNumbers: true,
        progressIndicator: 'percentage',
        ...overrides,
      },
      onSettingsChange: vi.fn(),
      t: (key: string) => key,
      onClose: vi.fn(),
    };
    render(ReaderTextSettings, base as never);
    return base;
  }

  it('shows the live line-height value under the button', () => {
    renderEpub({ lineHeight: 2 });
    expect(screen.getByTestId('line-height-value')).toHaveTextContent('2.0×');
  });

  it('localizes the line-height title (no hardcoded English)', () => {
    renderEpub({ lineHeight: 1.6 });
    expect(screen.getByTestId('line-height-btn').getAttribute('title')).toBe(
      'reader.line_spacing: 1.6×',
    );
  });

  it('shows the live letter-spacing value with sign', () => {
    renderEpub({ letterSpacing: 2 });
    expect(screen.getByTestId('letter-spacing-value')).toHaveTextContent('+2px');
  });
});

describe('ReaderTextSettings (U4) — selects and switches', () => {
  function renderEpub() {
    const props = {
      open: true,
      format: 'epub',
      readerSettings: {
        themeMode: 'paper',
        brightness: 100,
        contrast: 100,
        selectionColor: '#3388ff',
        epub: { fontSize: 100, fontFamily: 'serif' },
        lineHeight: 1.8,
        letterSpacing: 0,
        paragraphSpacing: 1,
        textAlign: 'left',
        direction: 'ltr',
        hyphenation: false,
        verticalScrolling: false,
        margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
        showHeader: true,
        showFooter: true,
        showPageNumbers: true,
        progressIndicator: 'percentage',
      },
      onSettingsChange: vi.fn(),
      t: (key: string) => key,
      onClose: vi.fn(),
    };
    render(ReaderTextSettings, props as never);
    return props;
  }

  it('jumps straight to justify through the alignment select', async () => {
    const props = renderEpub();
    await fireEvent.change(screen.getByTestId('alignment-select'), {
      target: { value: 'justify' },
    });
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ textAlign: 'justify' }),
    );
  });

  it('offers named margin presets instead of a lone left value', () => {
    renderEpub();
    const select = screen.getByTestId('margins-select') as HTMLSelectElement;
    expect(select.options.length).toBe(5);
    expect(select.options[0]?.text).toBe('reader.margin_compact');
    expect(select.value).toBe('2');
  });

  it('toggles hyphenation through a real switch', async () => {
    const props = renderEpub();
    await fireEvent.click(screen.getByLabelText('reader.hyphenation'));
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ hyphenation: true }),
    );
  });

  it('exposes four native selects with full keyboard support', () => {
    renderEpub();
    expect(screen.getByTestId('direction-select').tagName).toBe('SELECT');
    expect(screen.getByTestId('alignment-select').tagName).toBe('SELECT');
    expect(screen.getByTestId('margins-select').tagName).toBe('SELECT');
    expect(screen.getByTestId('spacing-select').tagName).toBe('SELECT');
  });
});
