import { getVersion } from '@tauri-apps/api/app';
import { check as checkPluginUpdate } from '@tauri-apps/plugin-updater';
import { relaunch as relaunchPluginApp } from '@tauri-apps/plugin-process';
import {
  defaultSuppressionStorage,
  isSuppressedFor,
  loadSuppression,
  type SuppressionStorage,
} from './updateSuppression';

export type DesktopFeedPlatform = {
  url: string;
  signature: string;
};

export type DesktopUpdateFeed = {
  version: string;
  notes: string;
  pubDate: string;
  channel: string | null;
  platforms: Record<string, DesktopFeedPlatform>;
};

export type UpdateErrorKind = 'UNREACHABLE' | 'MALFORMED' | 'OFFLINE';

export type UpdateCheckState =
  | { status: 'disabled' }
  | { status: 'checking' }
  | { status: 'upToDate'; installedVersion: string }
  | {
      status: 'available';
      installedVersion: string;
      feedVersion: string;
      notes: string;
    }
  | { status: 'error'; kind: UpdateErrorKind };

export class FeedUnreachableError extends Error {
  constructor(message = 'Update feed unreachable') {
    super(message);
    this.name = 'FeedUnreachableError';
  }
}

export class FeedMalformedError extends Error {
  constructor(message = 'Update feed malformed') {
    super(message);
    this.name = 'FeedMalformedError';
  }
}

export type ParsedFeedOutcome =
  { kind: 'feed'; feed: DesktopUpdateFeed } | { kind: 'ignored' } | { kind: 'malformed' };

const isNonEmptyString = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0;

export function parseDesktopFeed(document: unknown): ParsedFeedOutcome {
  if (typeof document !== 'object' || document === null) return { kind: 'malformed' };
  const doc = document as Record<string, unknown>;
  if (!isNonEmptyString(doc['version'])) return { kind: 'malformed' };
  if (!isNonEmptyString(doc['notes'])) return { kind: 'malformed' };
  const pubDate = doc['pub_date'];
  if (!isNonEmptyString(pubDate)) return { kind: 'malformed' };
  const channel = doc['channel'];
  if (channel !== undefined && channel !== null && channel !== 'stable') {
    return { kind: 'ignored' };
  }
  const platforms = doc['platforms'];
  if (typeof platforms !== 'object' || platforms === null) return { kind: 'malformed' };
  const entries = Object.entries(platforms as Record<string, unknown>);
  if (entries.length === 0) return { kind: 'malformed' };
  const valid: Record<string, DesktopFeedPlatform> = {};
  for (const [target, entry] of entries) {
    if (typeof entry !== 'object' || entry === null) return { kind: 'malformed' };
    const candidate = entry as Record<string, unknown>;
    if (!isNonEmptyString(candidate['url']) || !isNonEmptyString(candidate['signature'])) {
      return { kind: 'malformed' };
    }
    valid[target] = { url: candidate['url'], signature: candidate['signature'] };
  }
  return {
    kind: 'feed',
    feed: {
      version: doc['version'] as string,
      notes: doc['notes'] as string,
      pubDate,
      channel: typeof channel === 'string' ? channel : null,
      platforms: valid,
    },
  };
}

const numericSegments = (version: string): number[] =>
  version
    .split('.')
    .map((part) => part.split('-')[0] ?? '')
    .map((part) => {
      const parsed = Number.parseInt(part, 10);
      return Number.isNaN(parsed) ? 0 : parsed;
    });

export function compareDesktopVersions(feedVersion: string, installedVersion: string): number {
  const feed = numericSegments(feedVersion);
  const installed = numericSegments(installedVersion);
  const length = Math.max(feed.length, installed.length);
  for (let index = 0; index < length; index += 1) {
    const delta = (feed[index] ?? 0) - (installed[index] ?? 0);
    if (delta !== 0) return delta > 0 ? 1 : -1;
  }
  return 0;
}

export type UpdateCheckDeps = {
  feedUrl: string;
  getInstalledVersion: () => Promise<string | null>;
  fetchFeed: (url: string) => Promise<unknown>;
  isOnline: () => boolean;
  storage: SuppressionStorage;
  nowEpochMs: () => number;
};

export const defaultFetchFeed = async (url: string): Promise<unknown> => {
  let response: Response;
  try {
    response = await fetch(url);
  } catch {
    throw new FeedUnreachableError();
  }
  if (!response.ok) throw new FeedUnreachableError(`Feed responded ${response.status}`);
  try {
    return (await response.json()) as unknown;
  } catch {
    throw new FeedMalformedError('Feed body is not valid JSON');
  }
};

const defaultIsOnline = (): boolean => {
  try {
    return globalThis.navigator?.onLine ?? true;
  } catch {
    return true;
  }
};

export async function getInstalledAppVersion(): Promise<string | null> {
  try {
    const version = await getVersion();
    return typeof version === 'string' && version.length > 0 ? version : null;
  } catch {
    return null;
  }
}

export function resolveUpdateFeedUrl(
  env: Record<string, string | undefined> = (import.meta.env ?? {}) as Record<
    string,
    string | undefined
  >,
): string {
  return (env['VITE_UPDATE_FEED_URL'] ?? '').trim();
}

export const defaultUpdateCheckDeps = (feedUrl: string): UpdateCheckDeps => ({
  feedUrl,
  getInstalledVersion: getInstalledAppVersion,
  fetchFeed: defaultFetchFeed,
  isOnline: defaultIsOnline,
  storage: defaultSuppressionStorage(),
  nowEpochMs: () => Date.now(),
});

export async function checkForUpdates(
  deps: UpdateCheckDeps,
  options: { manual: boolean },
): Promise<UpdateCheckState | null> {
  if (deps.feedUrl.trim().length === 0) {
    return options.manual ? { status: 'disabled' } : null;
  }
  const installedVersion = await deps.getInstalledVersion();
  if (!installedVersion) {
    return options.manual ? { status: 'error', kind: 'MALFORMED' } : null;
  }
  if (!deps.isOnline()) {
    return options.manual ? { status: 'error', kind: 'OFFLINE' } : null;
  }
  let document: unknown;
  try {
    document = await deps.fetchFeed(deps.feedUrl);
  } catch (error) {
    if (error instanceof FeedMalformedError) {
      return options.manual ? { status: 'error', kind: 'MALFORMED' } : null;
    }
    return options.manual ? { status: 'error', kind: 'UNREACHABLE' } : null;
  }
  const parsed = parseDesktopFeed(document);
  if (parsed.kind === 'malformed') {
    return options.manual ? { status: 'error', kind: 'MALFORMED' } : null;
  }
  if (parsed.kind === 'ignored') {
    return options.manual ? { status: 'upToDate', installedVersion } : null;
  }
  const { feed } = parsed;
  if (!options.manual) {
    const suppression = loadSuppression(deps.storage);
    if (isSuppressedFor(suppression, feed.version, deps.nowEpochMs())) return null;
  }
  if (compareDesktopVersions(feed.version, installedVersion) <= 0) {
    return { status: 'upToDate', installedVersion };
  }
  return {
    status: 'available',
    installedVersion,
    feedVersion: feed.version,
    notes: feed.notes,
  };
}

let startupCheckAttempted = false;

export function resetStartupUpdateCheckForTests(): void {
  startupCheckAttempted = false;
}

export async function runStartupUpdateCheck(
  deps: UpdateCheckDeps,
): Promise<UpdateCheckState | null> {
  if (startupCheckAttempted) return null;
  startupCheckAttempted = true;
  const state = await checkForUpdates(deps, { manual: false });
  if (state?.status === 'available') return state;
  return null;
}

export type PluginUpdatePorts = {
  checkForPluginUpdate: () => Promise<{ version: string; notes: string | null } | null>;
  downloadAndInstall: (
    onProgress: (downloaded: number, total: number | null) => void,
  ) => Promise<void>;
  relaunchApp: () => Promise<void>;
};

export const defaultPluginUpdatePorts = (): PluginUpdatePorts => ({
  checkForPluginUpdate: async () => {
    const update = await checkPluginUpdate();
    if (!update) return null;
    const notes = update.body ?? null;
    const version = update.version;
    await update.close();
    return { version, notes };
  },
  downloadAndInstall: async (onProgress) => {
    const update = await checkPluginUpdate();
    if (!update) throw new FeedUnreachableError('Feed no longer advertises an update');
    try {
      let downloaded = 0;
      let total: number | null = null;
      await update.downloadAndInstall((event) => {
        if (event.event === 'Started') {
          total = event.data.contentLength ?? null;
        } else if (event.event === 'Progress') {
          downloaded += event.data.chunkLength;
        }
        onProgress(downloaded, total);
      });
    } finally {
      await update.close();
    }
  },
  relaunchApp: async () => {
    await relaunchPluginApp();
  },
});

export type PluginUpdateNowOutcome = 'installed' | 'noUpdateAnymore';

export async function performPluginUpdateNow(
  ports: PluginUpdatePorts,
  onProgress: (downloaded: number, total: number | null) => void,
): Promise<PluginUpdateNowOutcome> {
  const pending = await ports.checkForPluginUpdate();
  if (!pending) return 'noUpdateAnymore';
  await ports.downloadAndInstall(onProgress);
  return 'installed';
}
