import { get } from 'svelte/store';
import { i18n, type MessageKey } from '$lib/shared/i18n';
import logoLockup from '$lib/shared/brand/nexo-logo-horizontal.svg?raw';

/**
 * The page the system browser shows after an OAuth redirect lands on the
 * loopback server (Google sign-in, Drive grant). It is a static string served
 * once, so it carries its own styles and the NEXO lockup inline: the browser
 * tab cannot reach the app's assets.
 */
export type LoopbackPageKind = 'signedIn' | 'driveConnected';

const COPY: Record<LoopbackPageKind, { title: MessageKey; heading: MessageKey; body: MessageKey }> =
  {
    signedIn: {
      title: 'oauthPage.signedIn.title',
      heading: 'oauthPage.signedIn.heading',
      body: 'oauthPage.signedIn.body',
    },
    driveConnected: {
      title: 'oauthPage.drive.title',
      heading: 'oauthPage.drive.heading',
      body: 'oauthPage.drive.body',
    },
  };

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// Only the wordmark follows the page ink (the mark keeps its own), so one
// inline lockup serves both schemes.
const LOGO = logoLockup
  .replace('id="wordmark" fill="#FFF6EC"', 'id="wordmark" fill="currentColor"')
  .replace('stroke="#FFF6EC" stroke-width="30"', 'stroke="currentColor" stroke-width="30"')
  .replace('stroke="#FFF6EC" stroke-width="29"', 'stroke="currentColor" stroke-width="29"')
  .replace('<svg ', '<svg class="logo" role="img" aria-label="NEXO" ');

export function buildLoopbackPage(kind: LoopbackPageKind): string {
  const locale = get(i18n.locale);
  const t = (key: MessageKey): string => escapeHtml(i18n.t(locale, key));
  const copy = COPY[kind];

  return `<!DOCTYPE html>
<html lang="${locale}">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${t(copy.title)} — NEXO</title>
  <style>
    :root {
      color-scheme: dark light;
      --ground: #08111f; --card: #101c2c; --edge: rgba(148, 173, 206, 0.22);
      --ink: #f5f7fb; --muted: #94a3b8; --wordmark: #fff6ec;
      --brand-start: #ffa620; --brand: #f7781a; --brand-end: #ff2d0f; --brand-on: #1f0d02;
      --glow: rgba(247, 120, 26, 0.22);
    }
    @media (prefers-color-scheme: light) {
      :root {
        --ground: #eaeef4; --card: #f7f9fc; --edge: #b9cbe2;
        --ink: #0d1d3b; --muted: #52627a; --wordmark: #0d1d3b;
        --glow: rgba(247, 120, 26, 0.16);
      }
    }
    * { box-sizing: border-box; }
    html, body { height: 100%; }
    body {
      margin: 0; padding: 32px 24px;
      display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 28px;
      font-family: 'Segoe UI Variable Text', 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, Helvetica, Arial, sans-serif;
      color: var(--ink);
      background:
        radial-gradient(60rem 30rem at 50% -10%, var(--glow), transparent 70%),
        var(--ground);
    }
    .logo { height: 44px; width: auto; color: var(--wordmark); }
    .card {
      width: 100%; max-width: 440px;
      padding: 36px 32px 32px; text-align: center;
      background: var(--card); border: 1px solid var(--edge); border-radius: 24px;
    }
    .badge {
      width: 60px; height: 60px; margin: 0 auto 20px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      background: linear-gradient(135deg, var(--brand-start), var(--brand-end));
      box-shadow: 0 12px 28px -12px var(--brand);
      color: var(--brand-on);
    }
    h1 { margin: 0 0 10px; font-size: 24px; line-height: 1.2; letter-spacing: -0.01em; font-weight: 700; }
    p { margin: 0; font-size: 15px; line-height: 1.55; color: var(--muted); }
    .hint {
      margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--edge);
      font-size: 13px; color: var(--muted);
    }
  </style>
</head>
<body>
  ${LOGO}
  <main class="card">
    <div class="badge" aria-hidden="true">
      <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l4 4L19 7"/></svg>
    </div>
    <h1>${t(copy.heading)}</h1>
    <p>${t(copy.body)}</p>
    <p class="hint">${t('oauthPage.closeHint')}</p>
  </main>
</body>
</html>`;
}
