// Detects the visitor's OS and publishes it as `html[data-os]`.
//
// The download section reveals its "detected" badge and reorders the matching
// card through `html[data-os='…']` CSS selectors. Two constraints follow:
//
//   1. It has to run before the first paint of that section, or the visitor
//      sees the wrong card first and watches it jump.
//   2. It must NOT block the head. It used to sit inline in <head>, which put
//      ~2.5 KB of fixed cost in front of every page's first paint.
//
// So it ships as a module and the download cards start hidden by CSS until
// `data-os` lands (see DownloadSection). The document is re-measured by the
// header script's ResizeObserver, so the reorder does not strand stale geometry.

function detectOs(): string {
  const data = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData;

  // The user agent is always appended, even when `userAgentData` exists: on
  // Chrome OS `userAgentData.platform` is the bare string "Chrome OS", which
  // carries neither the Linux nor the CrOS token the checks below need, so a
  // platform-only probe silently loses that case.
  const probe = `${data?.platform ?? ''} ${navigator.platform ?? ''} ${navigator.userAgent ?? ''}`;

  // Order is load-bearing. An Android user agent also carries Linux, and a
  // Chrome OS one carries Linux, CrOS and X11 at once, so the more specific
  // platforms are resolved first. There is no iOS build, so every Apple mobile
  // form factor maps to `other` instead of guessing macOS and pointing it at a
  // .dmg it cannot open.
  if (/android/i.test(probe)) return 'android';
  if (/iphone|ipad|ipod/i.test(probe)) return 'other';
  if (/cros|chrome os/i.test(probe)) return 'other';
  // Matched as a word, not as `/win/i`: macOS browsers report "Darwin" in the
  // user agent, which a bare `/win/` would steal.
  if (/windows|win32|win64|wow64|win16/i.test(probe)) return 'windows';
  // iPadOS 13+ asks sites for the desktop layout and then reports itself as
  // "MacIntel", which no user-agent token distinguishes from a real Mac.
  // Multitouch is the remaining signal: a Mac reports one contact, an iPad
  // several.
  if (/mac/i.test(probe)) return navigator.maxTouchPoints > 1 ? 'other' : 'macos';
  if (/linux|x11/i.test(probe)) return 'linux';
  return 'other';
}

try {
  document.documentElement.dataset.os = detectOs();
} catch {
  // Detection unavailable: `data-os` stays unset, so the default card order
  // renders with no highlight. Release the CSS hold so nothing stays hidden.
  document.documentElement.dataset.os = 'other';
}

export {};
