import type { ReaderSettings } from '$lib/shared/types';
import {
  ALIGN_CYCLE,
  LINE_HEIGHT_PRESETS,
  PARAGRAPH_SPACING_PRESETS,
  cyclePreset,
} from './readerTextPresets';

export const READER_BRIGHTNESS_MIN = 50;
export const READER_BRIGHTNESS_MAX = 150;

export type UseReaderTextSettingsDeps = {
  getSettings: () => ReaderSettings;
  onSettingsChange: (settings: ReaderSettings) => void;
};

export function useReaderTextSettings(deps: UseReaderTextSettingsDeps): {
  showSavedToast: boolean;
  changeFontSize: (delta: number) => void;
  changeLetterSpacing: (delta: number) => void;
  setBrightness: (value: number) => void;
  setContrast: (value: number) => void;
  changeLineHeight: (delta: number) => void;
  cycleAlignment: () => void;
  cycleParagraphSpacing: () => void;
  resetToDefaults: () => void;
  toggleDirection: () => void;
  cleanup: () => void;
} {
  let showSavedToast = $state(false);
  let savedToastTimer: ReturnType<typeof setTimeout> | undefined;

  function notifyChange(): void {
    showSavedToast = true;
    clearTimeout(savedToastTimer);
    savedToastTimer = setTimeout(() => {
      showSavedToast = false;
    }, 1200);
  }

  function cleanup(): void {
    clearTimeout(savedToastTimer);
  }

  function changeFontSize(delta: number): void {
    const s = deps.getSettings();
    const current = s.epub.fontSize;
    const next = Math.max(80, Math.min(200, current + delta));
    if (next !== current) {
      deps.onSettingsChange({ ...s, epub: { ...s.epub, fontSize: next } });
      notifyChange();
    }
  }

  function changeLetterSpacing(delta: number): void {
    const s = deps.getSettings();
    const current = s.letterSpacing;
    const next = Math.max(-2, Math.min(10, current + delta));
    if (next !== current) {
      deps.onSettingsChange({ ...s, letterSpacing: next });
      notifyChange();
    }
  }

  function setBrightness(value: number): void {
    const s = deps.getSettings();
    const next = Math.max(
      READER_BRIGHTNESS_MIN,
      Math.min(READER_BRIGHTNESS_MAX, Math.round(value)),
    );
    if (next !== s.brightness) {
      deps.onSettingsChange({ ...s, brightness: next });
      notifyChange();
    }
  }

  function setContrast(value: number): void {
    const s = deps.getSettings();
    const next = Math.max(
      READER_BRIGHTNESS_MIN,
      Math.min(READER_BRIGHTNESS_MAX, Math.round(value)),
    );
    if (next !== s.contrast) {
      deps.onSettingsChange({ ...s, contrast: next });
      notifyChange();
    }
  }

  /**
   * Step through the line-height presets in one direction.
   *
   * This used to cycle (wrapping past the last preset back to the first), which
   * reads wrong behind a `−`/`+` stepper: pressing `+` at 2.4 jumped the text
   * back to its tightest setting. Clamping instead makes the control honest, and
   * the caller disables the button at each end.
   */
  function changeLineHeight(delta: number): void {
    const s = deps.getSettings();
    const idx = LINE_HEIGHT_PRESETS.indexOf(s.lineHeight);
    const currentIndex = idx < 0 ? 0 : idx;
    const nextIndex = Math.max(0, Math.min(LINE_HEIGHT_PRESETS.length - 1, currentIndex + delta));
    const next = LINE_HEIGHT_PRESETS[nextIndex];
    if (next === undefined || next === s.lineHeight) return;
    deps.onSettingsChange({ ...s, lineHeight: next });
    notifyChange();
  }

  function cycleAlignment(): void {
    const s = deps.getSettings();
    const next = cyclePreset(s.textAlign, ALIGN_CYCLE);
    deps.onSettingsChange({ ...s, textAlign: next });
    notifyChange();
  }

  function cycleParagraphSpacing(): void {
    const s = deps.getSettings();
    const next = cyclePreset(s.paragraphSpacing, PARAGRAPH_SPACING_PRESETS);
    deps.onSettingsChange({ ...s, paragraphSpacing: next });
    notifyChange();
  }

  function resetToDefaults(): void {
    const defaults: ReaderSettings = {
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
      margins: { top: 2.5, bottom: 2.5, left: 2, right: 2 },
      showHeader: true,
      showFooter: true,
      showPageNumbers: true,
      progressIndicator: 'percentage',
    };
    deps.onSettingsChange(defaults);
    notifyChange();
  }

  function toggleDirection(): void {
    const s = deps.getSettings();
    deps.onSettingsChange({
      ...s,
      direction: s.direction === 'ltr' ? 'rtl' : 'ltr',
    });
    notifyChange();
  }

  return {
    get showSavedToast(): boolean {
      return showSavedToast;
    },
    set showSavedToast(v: boolean) {
      showSavedToast = v;
    },
    changeFontSize,
    changeLetterSpacing,
    setBrightness,
    setContrast,
    changeLineHeight,
    cycleAlignment,
    cycleParagraphSpacing,
    resetToDefaults,
    toggleDirection,
    cleanup,
  };
}

export type ReaderTextSettingsState = ReturnType<typeof useReaderTextSettings>;
