/**
 * Build-time release resolver for the landing's download section.
 *
 * The asset names this repository publishes carry the version and are not
 * uniformly shaped (`Nexo.Desktop_0.3.2_x64-setup.exe` uses underscores,
 * `Nexo.Desktop-0.3.2-1.x86_64.rpm` uses hyphens,
 * `nexo-android-v0.3.2.apk` embeds a `v`). They cannot be assembled by
 * string concatenation, so every URL here comes from enumerating the release's
 * own asset list.
 *
 * Two shortcuts that look obvious and are wrong, recorded so nobody retries
 * them:
 *
 *   - `/releases/latest/download/<asset>` 404s. `latest` resolves to whichever
 *     release is newest overall, and this repository keeps three tag families
 *     (`android-v*`, `desktop-v*`, `nexo-web-v*`); the newest is `nexo-web-v*`,
 *     which publishes no assets.
 *   - The `latest.json` asset is the Tauri updater manifest. Its `url` values
 *     point at `api.github.com/repos/.../releases/assets/<id>`, which do not
 *     download from a browser click because they need an
 *     `Accept: application/octet-stream` header. It is for the app's updater,
 *     never for a visitor.
 *
 * Resolution never throws. A landing page must not stop building because a CI
 * runner was rate limited, so every failure path degrades to `null` and the
 * section falls back to the plain GitHub releases link.
 */

const RELEASES_API = 'https://api.github.com/repos/JuanCamacho198/NEXO/releases?per_page=100';

/** Anonymous callers get 60 requests per hour; a token raises it to 5000. */
const REQUEST_TIMEOUT_MS = 8000;

const DESKTOP_TAG_PREFIX = 'desktop-v';
const ANDROID_TAG_PREFIX = 'android-v';

/** Plateformes a visitor can actually install on. */
export type Platform = 'android' | 'windows' | 'macos' | 'linux';

export type AssetKind = 'installer' | 'msi' | 'dmg' | 'appimage' | 'deb' | 'rpm' | 'apk';

export interface ReleaseAsset {
  platform: Platform;
  kind: AssetKind;
  /** The single build we put behind the card's primary button. */
  recommended: boolean;
  fileName: string;
  url: string;
  sizeBytes: number;
  /** Derived from the release tag, without the family prefix. */
  version: string;
}

export interface PlatformOffer {
  platform: Platform;
  version: string;
  primary: ReleaseAsset;
  /** `.msi` next to the Windows `.exe`, `.deb`/`.rpm` next to the AppImage. */
  alternates: ReleaseAsset[];
}

export interface DownloadCatalog {
  offers: PlatformOffer[];
  checksumsUrl: string | null;
  desktopVersion: string | null;
  androidVersion: string | null;
}

interface GithubAsset {
  name?: unknown;
  size?: unknown;
  browser_download_url?: unknown;
}

interface GithubRelease {
  tag_name?: unknown;
  draft?: unknown;
  prerelease?: unknown;
  assets?: unknown;
}

/**
 * Reads the token without depending on Node types being installed in this
 * standalone project. Absent, the request is anonymous and may be rate limited;
 * that path is already handled.
 */
function readGithubToken(): string | undefined {
  const runtimeProcess = (globalThis as { process?: { env?: Record<string, string | undefined> } })
    .process;
  const token = runtimeProcess?.env?.GITHUB_TOKEN ?? runtimeProcess?.env?.GH_TOKEN;
  const trimmed = token?.trim();
  return trimmed ? trimmed : undefined;
}

/** `desktop-v0.3.1` -> `0.3.1`; anything without a numeric suffix -> null. */
function parseVersion(tag: string, prefix: string): string | null {
  if (!tag.startsWith(prefix)) return null;
  const version = tag.slice(prefix.length).trim();
  return /^\d+(\.\d+)*$/.test(version) ? version : null;
}

/** Numeric, segment-wise comparison. Distances above zero are what we use. */
function compareVersions(a: string, b: string): number {
  const left = a.split('.').map(Number);
  const right = b.split('.').map(Number);
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const diff = (left[index] ?? 0) - (right[index] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

function asAssets(release: GithubRelease): GithubAsset[] {
  return Array.isArray(release.assets) ? (release.assets as GithubAsset[]) : [];
}

function toAsset(raw: GithubAsset): { name: string; size: number; url: string } | null {
  const { name, size, browser_download_url: url } = raw;
  if (typeof name !== 'string' || typeof url !== 'string') return null;
  return { name, size: typeof size === 'number' ? size : 0, url };
}

/**
 * Picks the newest release of one tag family that actually carries assets.
 * Ordering by the API's position is rejected on purpose: the list is ordered by
 * creation date, which is not version order, and a re-cut of an older version
 * would otherwise win.
 */
function pickLatest(releases: GithubRelease[], prefix: string): GithubRelease | null {
  const candidates = releases
    .filter((release) => release.draft !== true)
    .map((release) => {
      const tag = typeof release.tag_name === 'string' ? release.tag_name : '';
      const version = parseVersion(tag, prefix);
      if (!version) return null;
      if (asAssets(release).length === 0) return null;
      return { release, version };
    })
    .filter((candidate): candidate is { release: GithubRelease; version: string } =>
      Boolean(candidate),
    );

  candidates.sort((a, b) => compareVersions(b.version, a.version));
  return candidates[0]?.release ?? null;
}

/**
 * Maps one desktop asset name to its platform and kind.
 *
 * `.sig` files are Tauri update signatures and `.tar.gz` is the macOS updater
 * archive: neither is something a visitor installs from a website, so both are
 * skipped along with `latest.json`.
 */
function classifyDesktopAsset(name: string): { kind: AssetKind; recommended: boolean } | null {
  if (name.endsWith('.exe')) return { kind: 'installer', recommended: true };
  if (name.endsWith('.msi')) return { kind: 'msi', recommended: false };
  if (name.endsWith('.dmg')) return { kind: 'dmg', recommended: true };
  if (name.endsWith('.AppImage')) return { kind: 'appimage', recommended: true };
  if (name.endsWith('.deb')) return { kind: 'deb', recommended: false };
  if (name.endsWith('.rpm')) return { kind: 'rpm', recommended: false };
  return null;
}

function platformOfDesktopKind(kind: AssetKind): Platform | null {
  if (kind === 'installer' || kind === 'msi') return 'windows';
  if (kind === 'dmg') return 'macos';
  if (kind === 'appimage' || kind === 'deb' || kind === 'rpm') return 'linux';
  return null;
}

const CHECKSUMS_FILE_NAME = 'SHA256SUMS.txt';
const ALTERNATE_ORDER: AssetKind[] = ['msi', 'deb', 'rpm'];

function buildDesktopOffers(
  release: GithubRelease,
  version: string,
): { offers: PlatformOffer[]; checksumsUrl: string | null } {
  const collected: ReleaseAsset[] = [];
  let checksumsUrl: string | null = null;

  for (const raw of asAssets(release)) {
    const asset = toAsset(raw);
    if (!asset) continue;
    if (asset.name === CHECKSUMS_FILE_NAME) {
      checksumsUrl = asset.url;
      continue;
    }
    const classified = classifyDesktopAsset(asset.name);
    if (!classified) continue;
    const platform = platformOfDesktopKind(classified.kind);
    if (!platform) continue;
    collected.push({
      platform,
      kind: classified.kind,
      recommended: classified.recommended,
      fileName: asset.name,
      url: asset.url,
      sizeBytes: asset.size,
      version,
    });
  }

  const offers: PlatformOffer[] = [];
  for (const platform of ['windows', 'macos', 'linux'] as const) {
    const forPlatform = collected.filter((asset) => asset.platform === platform);
    const primary = forPlatform.find((asset) => asset.recommended);
    if (!primary) continue;
    const alternates = forPlatform
      .filter((asset) => !asset.recommended)
      .sort((a, b) => ALTERNATE_ORDER.indexOf(a.kind) - ALTERNATE_ORDER.indexOf(b.kind));
    offers.push({ platform, version, primary, alternates });
  }

  return { offers, checksumsUrl };
}

function buildAndroidOffer(release: GithubRelease, version: string): PlatformOffer | null {
  for (const raw of asAssets(release)) {
    const asset = toAsset(raw);
    // `.aab` is a Play Store upload bundle, not something a visitor can install.
    if (!asset || !asset.name.endsWith('.apk')) continue;
    return {
      platform: 'android',
      version,
      primary: {
        platform: 'android',
        kind: 'apk',
        recommended: true,
        fileName: asset.name,
        url: asset.url,
        sizeBytes: asset.size,
        version,
      },
      alternates: [],
    };
  }
  return null;
}

/** Pure: the whole catalog decision, given a releases payload. */
export function buildCatalog(payload: unknown): DownloadCatalog | null {
  if (!Array.isArray(payload)) return null;
  const releases = payload as GithubRelease[];

  const desktopRelease = pickLatest(releases, DESKTOP_TAG_PREFIX);
  const androidRelease = pickLatest(releases, ANDROID_TAG_PREFIX);

  if (!desktopRelease && !androidRelease) return null;

  const desktopVersion = desktopRelease
    ? parseVersion(String(desktopRelease.tag_name ?? ''), DESKTOP_TAG_PREFIX)
    : null;
  const androidVersion = androidRelease
    ? parseVersion(String(androidRelease.tag_name ?? ''), ANDROID_TAG_PREFIX)
    : null;

  const desktop =
    desktopRelease && desktopVersion
      ? buildDesktopOffers(desktopRelease, desktopVersion)
      : { offers: [] as PlatformOffer[], checksumsUrl: null };

  const offers = [...desktop.offers];
  let checksumsUrl = desktop.checksumsUrl;

  if (androidRelease && androidVersion) {
    const androidOffer = buildAndroidOffer(androidRelease, androidVersion);
    if (androidOffer) offers.push(androidOffer);
    if (!checksumsUrl) {
      for (const raw of asAssets(androidRelease)) {
        const asset = toAsset(raw);
        if (asset?.name === CHECKSUMS_FILE_NAME) {
          checksumsUrl = asset.url;
          break;
        }
      }
    }
  }

  if (offers.length === 0) return null;
  return { offers, checksumsUrl, desktopVersion, androidVersion };
}

/** Shared so both the `es` and the `en` page build reuse one request. */
let cached: Promise<DownloadCatalog | null> | null = null;

async function requestCatalog(): Promise<DownloadCatalog | null> {
  const token = readGithubToken();
  try {
    const response = await fetch(RELEASES_API, {
      headers: {
        accept: 'application/vnd.github+json',
        'x-github-api-version': '2022-11-28',
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });

    if (!response.ok) {
      console.warn(
        `[releases] GitHub returned ${response.status} ${response.statusText}; the download section falls back to the releases link.`,
      );
      return null;
    }

    const catalog = buildCatalog(await response.json());
    if (!catalog) {
      console.warn(
        '[releases] No release with downloadable assets matched; the download section falls back to the releases link.',
      );
    }
    return catalog;
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    console.warn(
      `[releases] Could not reach GitHub (${reason}); the download section falls back to the releases link.`,
    );
    return null;
  }
}

/** Never rejects. `null` means "render the fallback". */
export function getDownloadCatalog(): Promise<DownloadCatalog | null> {
  cached ??= requestCatalog();
  return cached;
}

/**
 * Decimal megabytes (10^6), matching what GitHub prints next to the same
 * asset. Dividing by 1024^2 would show 5.9 where the release page says 6.2, and
 * a download size that disagrees with the release page reads as a bug.
 */
export function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '';
  const megabytes = bytes / 1_000_000;
  return megabytes >= 10 ? `${Math.round(megabytes)}` : megabytes.toFixed(1);
}
