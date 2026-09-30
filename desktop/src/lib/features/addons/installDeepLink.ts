/**
 * Install deep-link routing (sdd/addon-deeplink-v1 Work Unit A).
 * Pure module: parse + route. Cold start (onOpenUrl) and warm start
 * (single-instance `deep-link-install` event) converge on handleDeepLinkUrls,
 * which delegates install URLs to the useInstallDeepLink store via a setter
 * — keeping this module free of store/Tauri imports for testability.
 */
// `nexo` is the canonical NEXO scheme; the `nextpage*` schemes are legacy and
// stay accepted during the transition so shared addon links keep resolving.
const INSTALL_SCHEMES = new Set(['nexo', 'nextpage', 'nextpage-desktop']);
const INSTALL_HOST = 'install';

export interface InstallDeepLinkDeps {
  /** Receives the https manifest URL to preview + confirm. */
  onInstallUrl: (installUrl: string) => void;
}

let onInstallUrl: InstallDeepLinkDeps['onInstallUrl'] | null = null;

/** Register the install-flow handler (production: useInstallDeepLink store). */
export function setInstallDeepLinkHandler(next: InstallDeepLinkDeps['onInstallUrl']): void {
  onInstallUrl = next;
}

/** Test seam: clear the handler between tests. */
export function resetInstallDeepLinkHandler(): void {
  onInstallUrl = null;
}

/**
 * Parse a raw deep-link URL. Returns the manifest URL for install links;
 * null for anything else (non-install hosts keep the OAuth reservation).
 * Never throws: garbage in → null out.
 */
export function parseInstallDeepLink(raw: string): { installUrl: string } | null {
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return null;
  }
  if (!INSTALL_SCHEMES.has(parsed.protocol.replace(':', ''))) return null;
  if (parsed.host !== INSTALL_HOST) return null;
  const installUrl = parsed.searchParams.get('url');
  if (!installUrl) return null;
  return { installUrl };
}

/**
 * Single entry point for all deep-link URL batches (cold + warm).
 * Non-install URLs are logged and ignored; install URLs are handed to the
 * registered handler exactly once per URL.
 */
export async function handleDeepLinkUrls(urls: string[]): Promise<void> {
  for (const raw of urls) {
    const parsed = parseInstallDeepLink(raw);
    if (!parsed) {
      console.log(`deep link ignored (non-install): ${raw}`);
      continue;
    }
    if (!onInstallUrl) {
      console.warn('install deep link received before handler registration');
      continue;
    }
    onInstallUrl(parsed.installUrl);
  }
}
