import type { ActivityPoint, ReadingStatsSummaryDto } from '$lib/shared/types';
import { DEFAULT_DAILY_GOAL } from '$lib/shared/types/settings';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
import { TauriSettingsAdapter } from '$lib/shared/ports/adapters/tauri/TauriSettingsAdapter';

type MaybeCommandError = Error & {
  commandError?: { code: string; message: string; recoverable: boolean };
};

class StatsDomainState {
  private readonly viewerPort: ViewerPort;
  private readonly settingsPort: SettingsPort;

  constructor(deps: { viewerPort?: ViewerPort; settingsPort?: SettingsPort } = {}) {
    this.viewerPort = deps.viewerPort ?? new TauriViewerAdapter();
    this.settingsPort = deps.settingsPort ?? new TauriSettingsAdapter();
  }

  // ─── State ───
  stats = $state<ReadingStatsSummaryDto | null>(null);
  isLoadingStats = $state(false);
  statsUnavailableReason = $state<string | null>(null);

  // ─── Reading activity ───
  activitySeries = $state<ActivityPoint[]>([]);
  isLoadingActivity = $state(false);
  activityUnavailableReason = $state<string | null>(null);

  // ─── Range stats (current + previous for delta) ───
  currentStats = $state<ReadingStatsSummaryDto | null>(null);
  previousStats = $state<ReadingStatsSummaryDto | null>(null);
  isLoadingRange = $state(false);
  rangeUnavailableReason = $state<string | null>(null);

  // ─── Streak ───
  streakDays = $state<number>(0);
  isLoadingStreak = $state(false);
  streakUnavailableReason = $state<string | null>(null);

  // ─── Daily goal progress ───
  todayMinutes = $state<number>(0);
  isLoadingTodayMinutes = $state(false);
  // injected daily goal for derived — updated via AppState wiring
  dailyGoalMinutes = $state<number>(DEFAULT_DAILY_GOAL as number);
  goalProgress = $derived(
    this.dailyGoalMinutes <= 0
      ? 0
      : Math.min(1, Math.max(0, this.todayMinutes / this.dailyGoalMinutes)),
  );

  // ─── Methods ───

  async loadStats(bookId?: string, userId = ''): Promise<void> {
    this.isLoadingStats = true;

    try {
      this.stats = await this.viewerPort.getReadingStats(bookId, userId);
      this.statsUnavailableReason = null;
    } catch (error) {
      const typed = error as MaybeCommandError;
      if (typed.commandError?.recoverable) {
        this.statsUnavailableReason = typed.commandError.message;
      }
      this.stats = null;
    } finally {
      this.isLoadingStats = false;
    }
  }

  async loadActivity(period: string, granularity: string, bookId?: string): Promise<void> {
    this.isLoadingActivity = true;
    this.activityUnavailableReason = null;

    try {
      this.activitySeries = await this.viewerPort.getReadingActivity(period, granularity, bookId);
    } catch (error) {
      const typed = error as MaybeCommandError;
      if (typed.commandError?.recoverable) {
        this.activityUnavailableReason = typed.commandError.message;
      }
      this.activitySeries = [];
    } finally {
      this.isLoadingActivity = false;
    }
  }

  async loadRangeStats(
    from: string,
    to: string,
    bookId?: string,
    target: 'current' | 'previous' = 'current',
    userId = '',
  ): Promise<void> {
    if (target === 'current') {
      this.isLoadingRange = true;
      this.rangeUnavailableReason = null;
    }

    try {
      const stats = await this.viewerPort.getReadingStatsForRange(from, to, bookId, userId);
      if (target === 'current') {
        this.currentStats = stats;
      } else {
        this.previousStats = stats;
      }
    } catch (error) {
      const typed = error as MaybeCommandError;
      if (typed.commandError?.recoverable) {
        if (target === 'current') {
          this.rangeUnavailableReason = typed.commandError.message;
        }
      }
      if (target === 'current') {
        this.currentStats = null;
      } else {
        this.previousStats = null;
      }
    } finally {
      if (target === 'current') {
        this.isLoadingRange = false;
      }
    }
  }

  async loadStreak(bookId?: string, userId = ''): Promise<void> {
    this.isLoadingStreak = true;
    this.streakUnavailableReason = null;

    try {
      this.streakDays = await this.viewerPort.getReadingStreak(bookId, userId);
    } catch (error) {
      const typed = error as MaybeCommandError;
      if (typed.commandError?.recoverable) {
        this.streakUnavailableReason = typed.commandError.message;
      }
      this.streakDays = 0;
    } finally {
      this.isLoadingStreak = false;
    }
  }

  async loadTodayMinutes(userId: string, bookId?: string): Promise<void> {
    if (!userId || userId.trim().length === 0) {
      this.todayMinutes = 0;
      return;
    }
    this.isLoadingTodayMinutes = true;
    try {
      this.todayMinutes = await this.settingsPort.getTodayMinutes(userId, bookId);
    } catch {
      this.todayMinutes = 0;
    } finally {
      this.isLoadingTodayMinutes = false;
    }
  }

  syncDailyGoal(minutes: number): void {
    this.dailyGoalMinutes = minutes;
  }

  clearTodayMinutes(): void {
    this.todayMinutes = 0;
  }
}

export const statsState = new StatsDomainState();
export { StatsDomainState };
