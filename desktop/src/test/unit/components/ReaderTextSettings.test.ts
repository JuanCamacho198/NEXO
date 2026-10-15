import { render, screen, fireEvent, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ReaderTextSettings from '$lib/features/reader/chrome/ReaderTextSettings.svelte';
import type { ReaderSettings } from '$lib/shared/types';

const t = (key: string) => key;

/**
 * The panel's selects are the `Dropdown` atom (bits-ui), so the DOM holds a
 * button trigger with a portalled listbox rather than a native `<select>`.
 * Opening goes through pointer events because bits-ui opens on `pointerdown`;
 * picking dispatches pointer + click like `Dropdown.test.ts` does.
 */
function triggerOf(container: HTMLElement, testid: string): HTMLElement {
  const wrapper = container.querySelector(`[data-testid="${testid}"]`);
  expect(wrapper).toBeTruthy();
  const trigger = wrapper!.querySelector('[aria-haspopup="listbox"]');
  expect(trigger).toBeTruthy();
  return trigger as HTMLElement;
}

async function openDropdown(trigger: HTMLElement): Promise<void> {
  await fireEvent.pointerDown(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.pointerUp(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeNull());
}

async function pickOption(label: string): Promise<void> {
  const item = screen.getAllByRole('option').find((o) => o.textContent?.trim() === label);
  expect(item).toBeTruthy();
  await fireEvent.pointerDown(item!, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.pointerUp(item!, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.click(item!);
  await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
}

/** Selects one option end to end: open the atom, then pick by visible label. */
async function chooseOption(container: HTMLElement, testid: string, label: string): Promise<void> {
  await openDropdown(triggerOf(container, testid));
  await pickOption(label);
}

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
    const container = document.body;
    await chooseOption(container, 'alignment-select', 'reader.alignJustify');
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ textAlign: 'justify' }),
    );
  });

  it('offers named margin presets instead of a lone left value', async () => {
    renderEpub();
    const container = document.body;
    await openDropdown(triggerOf(container, 'margins-select'));
    const opts = screen.getAllByRole('option').map((o) => o.textContent?.trim());
    expect(opts.length).toBe(5);
    expect(opts[0]).toBe('reader.margin_compact');
    await pickOption('reader.margin_compact');
  });

  it('toggles hyphenation through a real switch', async () => {
    const props = renderEpub();
    await fireEvent.click(screen.getByLabelText('reader.hyphenation'));
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ hyphenation: true }),
    );
  });

  it('exposes four atom-backed selects with a listbox trigger', () => {
    renderEpub();
    const container = document.body;
    for (const id of ['direction-select', 'alignment-select', 'margins-select', 'spacing-select']) {
      expect(triggerOf(container, id).getAttribute('aria-haspopup')).toBe('listbox');
    }
  });
});

describe('ReaderTextSettings (U5) — font preview and book exit', () => {
  function renderEpub(fontFamily = 'serif') {
    const props = {
      open: true,
      format: 'epub',
      readerSettings: {
        themeMode: 'paper',
        brightness: 100,
        contrast: 100,
        selectionColor: '#3388ff',
        epub: { fontSize: 100, fontFamily },
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

  it('offers the publisher font as the first option', async () => {
    renderEpub();
    const container = document.body;
    await openDropdown(triggerOf(container, 'font-select'));
    const opts = screen.getAllByRole('option');
    expect(opts[0]?.textContent?.trim()).toBe('reader.fontDefault');
    expect(opts.length).toBe(6);
    await pickOption('reader.fontDefault');
  });

  it('previews the current face on the closed select', () => {
    renderEpub('Georgia');
    const trigger = triggerOf(document.body, 'font-select');
    expect(trigger.innerHTML).toContain('Georgia');
  });

  it('returns to the publisher font on demand', async () => {
    const props = renderEpub('monospace');
    await chooseOption(document.body, 'font-select', 'reader.fontDefault');
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ epub: expect.objectContaining({ fontFamily: '' }) }),
    );
  });
});

describe('ReaderTextSettings (U6) — guarded reset', () => {
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

  it('labels the action as reset, not saved settings', () => {
    renderEpub();
    const btn = screen.getByTestId('reset-defaults');
    expect(btn).toHaveTextContent('reader.reset_defaults');
    expect(btn).not.toHaveTextContent('reader.saved_settings');
  });

  it('arms on first tap and resets only on the second', async () => {
    const props = renderEpub();
    const btn = screen.getByTestId('reset-defaults');
    await fireEvent.click(btn);
    expect(props.onSettingsChange).not.toHaveBeenCalled();
    expect(btn).toHaveTextContent('reader.reset_confirm');
    await fireEvent.click(btn);
    expect(props.onSettingsChange).toHaveBeenCalledWith(
      expect.objectContaining({ themeMode: 'paper' }),
    );
  });
});
