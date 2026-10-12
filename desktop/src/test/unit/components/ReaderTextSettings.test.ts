import { render, screen } from '@testing-library/svelte';
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
