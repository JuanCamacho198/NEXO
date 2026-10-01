import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ReaderTextSettings from '$lib/features/reader/chrome/ReaderTextSettings.svelte';
import {
  READER_BRIGHTNESS_MAX,
  READER_BRIGHTNESS_MIN,
  useReaderTextSettings,
} from '$lib/features/reader/chrome/useReaderTextSettings.svelte';
import type { MessageKey } from '$lib/shared/i18n';
import type { ReaderSettings } from '$lib/shared/types';

const t = (key: MessageKey): string => key;

const baseSettings = (overrides: Partial<ReaderSettings> = {}): ReaderSettings => ({
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
});

describe('useReaderTextSettings brightness/contrast setters', () => {
  it('writes in-range values through onSettingsChange', () => {
    const onChange = vi.fn();
    const hook = useReaderTextSettings({
      getSettings: () => baseSettings(),
      onSettingsChange: onChange,
    });

    hook.setBrightness(120);
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ brightness: 120 }));

    hook.setContrast(80);
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ contrast: 80 }));

    hook.cleanup();
  });

  it('clamps brightness and contrast to the 50-150 reader range', () => {
    const onChange = vi.fn();
    const hook = useReaderTextSettings({
      getSettings: () => baseSettings(),
      onSettingsChange: onChange,
    });

    hook.setBrightness(999);
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ brightness: READER_BRIGHTNESS_MAX }),
    );

    hook.setBrightness(-10);
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ brightness: READER_BRIGHTNESS_MIN }),
    );

    hook.setContrast(999);
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ contrast: READER_BRIGHTNESS_MAX }),
    );

    hook.setContrast(-10);
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ contrast: READER_BRIGHTNESS_MIN }),
    );

    expect(READER_BRIGHTNESS_MIN).toBe(50);
    expect(READER_BRIGHTNESS_MAX).toBe(150);

    hook.cleanup();
  });

  it('does not emit when the clamped value is unchanged', () => {
    const onChange = vi.fn();
    const current = baseSettings({ brightness: 150, contrast: 50 });
    const hook = useReaderTextSettings({
      getSettings: () => current,
      onSettingsChange: onChange,
    });

    hook.setBrightness(999);
    hook.setContrast(-10);

    expect(onChange).not.toHaveBeenCalled();
    hook.cleanup();
  });

  it('resetToDefaults restores brightness and contrast to 100', () => {
    const onChange = vi.fn();
    const hook = useReaderTextSettings({
      getSettings: () => baseSettings({ brightness: 70, contrast: 130 }),
      onSettingsChange: onChange,
    });

    hook.resetToDefaults();

    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ brightness: 100, contrast: 100 }),
    );
    hook.cleanup();
  });
});

describe('ReaderTextSettings brightness/contrast controls', () => {
  it('writes the slider value and clamps out-of-range input', async () => {
    const onChange = vi.fn();
    render(ReaderTextSettings, {
      open: true,
      format: 'epub',
      readerSettings: baseSettings(),
      onSettingsChange: onChange,
      t,
      onClose: () => {},
    });

    const brightness = screen.getByLabelText('settings.reader.brightness');
    await fireEvent.input(brightness, { target: { value: '120' } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ brightness: 120 }));

    const contrast = screen.getByLabelText('settings.reader.contrast');
    await fireEvent.input(contrast, { target: { value: '999' } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ contrast: 150 }));

    await fireEvent.input(contrast, { target: { value: '0' } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ contrast: 50 }));
  });
});
