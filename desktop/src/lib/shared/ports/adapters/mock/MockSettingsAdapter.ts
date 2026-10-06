import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import type { AppSettingDto, ReaderSettings, UiLocale } from '$lib/shared/types';

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
  margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
  showHeader: true,
  showFooter: true,
  showPageNumbers: true,
  progressIndicator: 'percentage',
};

export class MockSettingsAdapter implements SettingsPort {
  #s: ReaderSettings = {
    ...defaults,
    epub: { ...defaults.epub },
    margins: { ...defaults.margins },
  };
  #locale: string | null = null;
  #goals = new Map<string, number>();
  #anonymousGoal: number | null = null;
  #minutes = new Map<string, number>();
  #app = new Map<string, AppSettingDto>();
  async getReaderSettings(): Promise<ReaderSettings> {
    return { ...this.#s, epub: { ...this.#s.epub }, margins: { ...this.#s.margins } };
  }
  async upsertReaderSettings(p: Partial<ReaderSettings>): Promise<ReaderSettings> {
    this.#s = {
      ...this.#s,
      ...p,
      epub: { ...this.#s.epub, ...p.epub },
      margins: { ...this.#s.margins, ...p.margins },
    } as ReaderSettings;
    return this.getReaderSettings();
  }
  async resetReaderSettings(): Promise<ReaderSettings> {
    this.#s = { ...defaults, epub: { ...defaults.epub }, margins: { ...defaults.margins } };
    return this.getReaderSettings();
  }
  async getLocale(): Promise<string | null> {
    return this.#locale;
  }
  async upsertLocale(l: UiLocale): Promise<void> {
    this.#locale = l;
  }
  async getDailyGoal(u?: string): Promise<number> {
    const uid = u?.trim();
    // Mirrors the backend: a signed-in read falls back to the anonymous global
    // value when no per-user value exists.
    if (uid) return this.#goals.get(uid) ?? this.#anonymousGoal ?? 20;
    return this.#anonymousGoal ?? 20;
  }
  async saveDailyGoal(m: number, u?: string): Promise<void> {
    const uid = u?.trim();
    if (uid) this.#goals.set(uid, m);
    else this.#anonymousGoal = m;
  }
  async getTodayMinutes(u: string, b?: string): Promise<number> {
    return this.#minutes.get(b ? `${u}:${b}` : u) ?? 0;
  }
  async getAppSettings(): Promise<AppSettingDto[]> {
    return [...this.#app.values()];
  }
  async upsertAppSettings(s: AppSettingDto[]): Promise<void> {
    for (const e of s) this.#app.set(e.key, e);
  }
  seedTodayMinutes(u: string, m: number, b?: string): void {
    this.#minutes.set(b ? `${u}:${b}` : u, m);
  }
}
