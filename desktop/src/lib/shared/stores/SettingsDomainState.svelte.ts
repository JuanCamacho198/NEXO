import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import { TauriSettingsAdapter } from '$lib/shared/ports/adapters/tauri/TauriSettingsAdapter';
import type { ReaderSettings, UiLocale } from '$lib/shared/types';
import { DEFAULT_DAILY_GOAL, DAILY_GOAL_OPTIONS } from '$lib/shared/types/settings';

const FALLBACK_READER_SETTINGS: ReaderSettings = {
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
};

const sanitizeDailyGoalFallback = (value: number): number => {
  const n = Math.min(60, Math.max(10, Math.round(value)));
  if (n === 60) return 45;
  const allowed = DAILY_GOAL_OPTIONS as readonly number[];
  if ((allowed as number[]).includes(n)) return n;
  let best = allowed[0];
  let bestDist = Math.abs(n - best);
  for (const opt of allowed) {
    const dist = Math.abs(n - opt);
    if (dist < bestDist) {
      best = opt;
      bestDist = dist;
    }
  }
  return best;
};

class SettingsDomainState {
  private readonly settingsPort: SettingsPort;

  constructor(deps: { settingsPort?: SettingsPort } = {}) {
    this.settingsPort = deps.settingsPort ?? new TauriSettingsAdapter();
  }

  // ─── State ───
  locale = $state<UiLocale>('es');
  readerSettings = $state<ReaderSettings>(FALLBACK_READER_SETTINGS);
  dailyGoalMinutes = $state<number>(DEFAULT_DAILY_GOAL as number);

  // ─── Methods ───

  async loadReaderSettings(): Promise<void> {
    try {
      this.readerSettings = await this.settingsPort.getReaderSettings();
    } catch {
      this.readerSettings = FALLBACK_READER_SETTINGS;
    }
  }

  // Deliberately bound arrow fields. Both handlers are forwarded verbatim onto
  // component props (`onLocaleChange={settingsState.handleLocaleChange}`), and
  // Svelte invokes such props as `$$props.onLocaleChange?.(...)`, which rebinds
  // `this` to the props object. A prototype method would then write
  // `this.locale = ...` onto the props accessors instead of onto this store —
  // and when the parent passes `locale={settingsState.locale}` without `bind:`,
  // that accessor is getter-only, so the write throws
  // "Cannot set property locale of #<Object> which has only a getter".
  handleReaderSettingsChange = (nextSettings: ReaderSettings): void => {
    this.readerSettings = nextSettings;
  };

  handleLocaleChange = (nextLocale: UiLocale): void => {
    this.locale = nextLocale;
  };

  async loadDailyGoalMinutes(userId?: string): Promise<void> {
    try {
      const minutes = await this.settingsPort.getDailyGoal(userId);
      this.dailyGoalMinutes = minutes;
    } catch {
      this.dailyGoalMinutes = DEFAULT_DAILY_GOAL as number;
    }
  }

  async saveDailyGoalMinutes(minutes: number, userId?: string): Promise<void> {
    if (!userId || userId.trim().length === 0) return;
    await this.settingsPort.saveDailyGoal(minutes, userId);
    // reflect sanitized value (normalize 60→45 etc.)
    try {
      const refreshed = await this.settingsPort.getDailyGoal(userId);
      this.dailyGoalMinutes = refreshed;
    } catch {
      this.dailyGoalMinutes = sanitizeDailyGoalFallback(minutes) as number;
    }
  }

  clearDailyGoal(): void {
    this.dailyGoalMinutes = DEFAULT_DAILY_GOAL as number;
  }
}

export const settingsState = new SettingsDomainState();
export { SettingsDomainState };
