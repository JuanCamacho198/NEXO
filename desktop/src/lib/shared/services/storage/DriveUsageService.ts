/**
 * Measures how many bytes the app owns under `Nexo/Books` in Google Drive.
 *
 * Three honest states — never a bare `null` that means either "unknown" or a
 * stale number presented as current:
 *   - `not_connected`: no Drive grant, checked before any network call.
 *   - `failed`: the grant exists but the measurement call failed.
 *   - `measured`: a real byte count, carrying `measuredAt` so callers can show
 *     how old the number is.
 *
 * The result is cached for `DRIVE_USAGE_CACHE_TTL_MS` so opening the storage
 * panel repeatedly does not issue a Drive request per open. There is no
 * background poller: the measurement happens lazily on demand and only when
 * the cache is missing or stale.
 */
import { GDriveProvider } from './GDriveProvider';
import { isDriveAuthorized } from '$lib/shared/services/DriveConnectService';

export type DriveUsage =
  | { state: 'not_connected' }
  | { state: 'failed'; message: string }
  | { state: 'measured'; bytes: number; fileCount: number; measuredAt: number };

export type DriveUsageReader = {
  getUsage: () => Promise<{ bytes: number; fileCount: number }>;
};

export const DRIVE_USAGE_CACHE_TTL_MS = 15 * 60 * 1000;

type MeasuredUsage = Extract<DriveUsage, { state: 'measured' }>;

let cached: MeasuredUsage | null = null;

export type DriveUsageDeps = {
  /** Bypass a fresh cache entry and measure again. */
  force?: boolean;
  provider?: DriveUsageReader;
  isAuthorized?: () => Promise<boolean>;
  now?: () => number;
};

export async function getDriveUsage(deps: DriveUsageDeps = {}): Promise<DriveUsage> {
  const now = deps.now ?? ((): number => Date.now());

  let authorized: boolean;
  try {
    authorized = await (deps.isAuthorized ?? isDriveAuthorized)();
  } catch (e) {
    return { state: 'failed', message: e instanceof Error ? e.message : String(e) };
  }
  if (!authorized) {
    // A cached count belongs to whichever grant produced it; without a grant
    // the honest answer is "not connected", never a number the user cannot use.
    cached = null;
    return { state: 'not_connected' };
  }

  if (!deps.force && cached && now() - cached.measuredAt < DRIVE_USAGE_CACHE_TTL_MS) {
    return cached;
  }

  try {
    const provider = deps.provider ?? new GDriveProvider();
    const { bytes, fileCount } = await provider.getUsage();
    cached = { state: 'measured', bytes, fileCount, measuredAt: now() };
    return cached;
  } catch (e) {
    return { state: 'failed', message: e instanceof Error ? e.message : String(e) };
  }
}

/** Reset the module-level cache (used by tests between cases). */
export function __resetDriveUsageCache(): void {
  cached = null;
}
