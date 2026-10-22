export type AppSettingDto = {
  key: string;
  valueJson: string;
  updatedAt: string;
};

export const SUPPORTED_UI_LOCALES = ['es', 'en'] as const;

export type UiLocale = (typeof SUPPORTED_UI_LOCALES)[number];

export const UI_LOCALE_SETTING_KEY = 'ui.locale' as const;

export const READER_THEME_MODE_SETTING_KEY = 'reader.themeMode' as const;
export const READER_BRIGHTNESS_SETTING_KEY = 'reader.brightness' as const;
export const READER_CONTRAST_SETTING_KEY = 'reader.contrast' as const;
export const READER_SELECTION_COLOR_SETTING_KEY = 'reader.selectionColor' as const;
export const READER_EPUB_FONT_SIZE_SETTING_KEY = 'reader.epub.fontSize' as const;
export const READER_EPUB_FONT_FAMILY_SETTING_KEY = 'reader.epub.fontFamily' as const;

// New layout setting keys
export const READER_LINE_HEIGHT_SETTING_KEY = 'reader.lineHeight' as const;
export const READER_LETTER_SPACING_SETTING_KEY = 'reader.letterSpacing' as const;
export const READER_PARAGRAPH_SPACING_SETTING_KEY = 'reader.paragraphSpacing' as const;
export const READER_TEXT_ALIGN_SETTING_KEY = 'reader.textAlign' as const;
export const READER_DIRECTION_SETTING_KEY = 'reader.direction' as const;
export const READER_HYPHENATION_SETTING_KEY = 'reader.hyphenation' as const;
export const READER_VERTICAL_SCROLLING_SETTING_KEY = 'reader.verticalScrolling' as const;
export const READER_PAGINATED_SETTING_KEY = 'reader.paginated' as const;
export const READER_MARGINS_SETTING_KEY = 'reader.margins' as const; // deprecated — kept for reading legacy entries
export const READER_MARGIN_TOP_SETTING_KEY = 'reader.margins.top' as const;
export const READER_MARGIN_BOTTOM_SETTING_KEY = 'reader.margins.bottom' as const;
export const READER_MARGIN_LEFT_SETTING_KEY = 'reader.margins.left' as const;
export const READER_MARGIN_RIGHT_SETTING_KEY = 'reader.margins.right' as const;
export const READER_SHOW_HEADER_SETTING_KEY = 'reader.showHeader' as const;
export const READER_SHOW_FOOTER_SETTING_KEY = 'reader.showFooter' as const;
export const READER_SHOW_PAGE_NUMBERS_SETTING_KEY = 'reader.showPageNumbers' as const;
export const READER_PROGRESS_INDICATOR_SETTING_KEY = 'reader.progressIndicator' as const;

/**
 * Reader themes, each with a reason to exist:
 *  - `paper`  off-white, easier on the eye than pure white for long sessions
 *  - `sepia`  warm paper, the classic low-contrast reading surface
 *  - `night`  very dark grey; pure black causes halation against light text
 *  - `oled`   true black, for OLED panels where it saves power
 */
export type ReaderThemeMode = 'paper' | 'sepia' | 'night' | 'oled';

export type ReaderTextAlign = 'left' | 'center' | 'right' | 'justify';
export type ReaderDirection = 'ltr' | 'rtl';
/**
 * Reader progress readouts. Every member is lowercase because
 * `sanitizeReaderSettings` lowercases before matching (`sanitizeEnum`); a
 * camelCase member would be silently reverted to the default on every save.
 *
 * `bookpages` is a page number and total across the whole book (`N / M`);
 * `percentage` is spine-weighted text. They answer different questions, which is
 * why the footer's switch swaps one for the other instead of replacing both.
 */
export type ReaderProgressIndicator = 'percentage' | 'chapter' | 'time' | 'bookpages';

/**
 * True when the footer should answer with book page numbers (`N / M`) instead of
 * the percentage.
 *
 * The footer renders only `percentage` and `bookpages` today: `chapter` meant
 * "show the chapter's own numbers in the percentage slot", which is now the page
 * field beside it, and `time` has no estimator behind it. They keep their stored
 * meaning (both still resolve to the percentage) so an existing settings row is
 * never silently reinterpreted — and so this stays an additive extension of the
 * enum rather than a fourth setting that says almost the same thing.
 */
export const isBookPagesIndicator = (indicator: ReaderProgressIndicator): boolean =>
  indicator === 'bookpages';

export type ReaderSettings = {
  // Existing fields
  themeMode: ReaderThemeMode;
  brightness: number;
  contrast: number;
  selectionColor: string;
  epub: {
    fontSize: number;
    fontFamily: string;
  };

  // New layout fields (merged from ReaderLayoutSettings)
  lineHeight: number; // 1.0–3.0, step 0.1, default 1.8
  letterSpacing: number; // -2 to 10 px, default 0
  paragraphSpacing: number; // 0 to 4 em, default 1
  textAlign: ReaderTextAlign;
  direction: ReaderDirection;
  hyphenation: boolean;
  verticalScrolling: boolean;
  /**
   * Paginated mode: the chapter renders as pages that turn one screen at a
   * time, instead of one tall scrolling column. `false` is scrolling mode.
   *
   * A new field rather than a reuse of `verticalScrolling`: that one predates
   * any viewer reading it (the reader settings panel removed its switch because
   * nothing consumed it) and its polarity is inverted, so overloading it would
   * make `false` mean two different things across stored settings.
   */
  paginated: boolean;
  margins: {
    top: number; // 0.5–4 rem
    bottom: number;
    left: number;
    right: number;
  };
  showHeader: boolean;
  showFooter: boolean;
  showPageNumbers: boolean;
  progressIndicator: ReaderProgressIndicator;
};

export type TranslationKey = string;

// Reader Layout Settings (margins, spacing, header/footer)
// Deprecated: These fields are now merged directly into ReaderSettings.
// Kept for backward compat with AppConfigExport.
export interface ReaderLayoutSettings {
  margins: {
    top: number;
    bottom: number;
    left: number;
    right: number;
  };
  lineHeight: number;
  paragraphSpacing: number;
  showHeader: boolean;
  showFooter: boolean;
  showPageNumbers: boolean;
  progressIndicator: 'percentage' | 'chapter' | 'time';
}

// Notification Settings
export interface NotificationSettings {
  readingReminders: {
    enabled: boolean;
    frequency: 'daily' | 'weekly';
    time?: string;
  };
  progressAlerts: {
    enabled: boolean;
    milestones: number[];
  };
}

// Sync Settings
export interface SyncSettings {
  autoSync: boolean;
  frequency: 'manual' | 'hourly' | 'daily';
  conflictResolution: 'local' | 'remote' | 'ask';
  lastSyncTime?: string;
}

// Storage Information
export interface StorageInfo {
  cacheSize: number;
  downloadedBooks: number;
  tempFiles: number;
}

// Diagnose result from backend health check
export interface DiagnoseResult {
  database: string;
  queue: string;
  filesystem: string;
  logFile: string;
  details: Record<string, unknown>;
}

// Daily reading goal (reading-daily-goal)
export type DailyGoalOption = 10 | 20 | 30 | 45;
export const DAILY_GOAL_OPTIONS: readonly DailyGoalOption[] = [10, 20, 30, 45] as const;
export const DEFAULT_DAILY_GOAL: DailyGoalOption = 20;
export const READING_DAILY_GOAL_KEY = 'reading.dailyGoalMinutes' as const;
export const ALLOWED_GOALS: readonly number[] = DAILY_GOAL_OPTIONS;
export const getPerUserDailyGoalKey = (userId: string): string =>
  `${READING_DAILY_GOAL_KEY}_${userId}`;
export const isDailyGoalOption = (value: unknown): value is DailyGoalOption =>
  typeof value === 'number' && (DAILY_GOAL_OPTIONS as readonly number[]).includes(value);
export const normalizeDailyGoalOption = (value: number): DailyGoalOption => {
  if (value === 60) return 45;
  if (isDailyGoalOption(value)) return value;
  // nearest allowed (clamp semantics match sanitizeDailyGoal)
  let best = DAILY_GOAL_OPTIONS[0];
  let bestDist = Math.abs(value - best);
  for (const opt of DAILY_GOAL_OPTIONS) {
    const dist = Math.abs(value - opt);
    if (dist < bestDist) {
      best = opt;
      bestDist = dist;
    }
  }
  return best;
};

// Export/Import Config
export interface AppConfigExport {
  version: string;
  exportedAt: string;
  locale: UiLocale;
  theme: string;
  fontScale: number;
  readerSettings: ReaderSettings;
  readerLayoutSettings: ReaderLayoutSettings;
  notificationSettings: NotificationSettings;
  syncSettings: SyncSettings;
}
