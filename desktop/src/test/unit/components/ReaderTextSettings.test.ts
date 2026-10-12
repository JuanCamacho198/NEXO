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
