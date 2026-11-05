/**
 * The parent-side recorder: what arrived, in what order, and what dismissed a
 * menu.
 *
 * ## Why it is shared, and why it is idempotent
 *
 * Two specs need the same instrument — `readerToolbarRemount.spec.ts` (which
 * built it) and `readerHighlightMenu.spec.ts` (which needs it to see a menu
 * close itself). They run against **the same live app page in the same worker**,
 * and that page is never reloaded, so two private copies would fight: the second
 * install would either double every line or silently not install at all. One
 * module, installed once, read by both.
 *
 * ## No production code is instrumented
 *
 * `ReaderWorkspace.dismissToolbar` already pushes its reason onto
 * `window.__toolbarDismissals` synchronously. The recorder replaces that array
 * and forwards, so the attribution comes from the app's own call — and the
 * caller's stack is captured at the push, which is what turns "the menu
 * disappeared" into "`workspace-pointerdown` closed it, from line N".
 *
 * The input listeners fire in the CAPTURE phase on `window`, i.e. before any
 * handler in the app — including the workspace's own capture-phase
 * `pointerdown` — so the sequence below reads as the browser saw it.
 */
import type { Page } from '@playwright/test';

export type TraceEntry = { t: number; kind: string; detail: string };

/**
 * Installs the recorder and returns the window size it was installed at (a spec
 * that resizes wants to know). Idempotent: a second call resets the buffer.
 */
export async function installTrace(page: Page): Promise<string> {
  return page.evaluate(() => {
    type Entry = { t: number; kind: string; detail: string };
    type W = {
      __rt?: Entry[];
      __rtT0?: number;
      __rtInstalled?: boolean;
      __rtMenuTimer?: ReturnType<typeof setInterval> | null;
      __toolbarDismissals?: string[];
    };
    const w = window as unknown as W;

    if (w.__rtInstalled) {
      w.__rt = [];
      w.__rtT0 = performance.now();
      return `${window.innerWidth}x${window.innerHeight}`;
    }
    w.__rtInstalled = true;
    w.__rt = [];
    w.__rtT0 = performance.now();

    const rec = (kind: string, detail: string): void => {
      w.__rt!.push({
        t: Math.round((performance.now() - w.__rtT0!) * 10) / 10,
        kind,
        detail,
      });
      if (w.__rt!.length > 4000) w.__rt!.splice(0, 1000);
    };
    (window as unknown as { __rtRec?: (k: string, d: string) => void }).__rtRec = rec;

    /** Which surface a press landed in, named after the menu it belongs to. */
    const describe = (node: EventTarget | null): string => {
      const el = node as Element | null;
      if (!el || typeof el.tagName !== 'string') return String(node);
      const cls = String(el.getAttribute('class') ?? '')
        .replace(/\s+/g, ' ')
        .slice(0, 60);
      const where = el.closest?.('.selection-toolbar')
        ? ' <<IN-TOOLBAR>>'
        : el.closest?.('[data-testid="highlight-context-menu"]')
          ? ' <<IN-HIGHLIGHT-MENU>>'
          : el.closest?.('[data-floating-menu]')
            ? ' <<IN-FLOATING-MENU>>'
            : '';
      return `${el.tagName.toLowerCase()}.${cls}${where}`;
    };

    for (const type of ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'click'] as const) {
      window.addEventListener(
        type,
        (e) => {
          const ev = e as MouseEvent;
          rec(
            `input:${type}`,
            `${describe(e.target)} @${Math.round(ev.clientX)},${Math.round(ev.clientY)}`,
          );
        },
        true,
      );
    }

    window.addEventListener(
      'message',
      (e) => {
        const d = e.data as Record<string, unknown> | null;
        if (!d || typeof d !== 'object' || typeof d.type !== 'string') return;
        if (d.type === 'epub-selection') {
          rec(
            'msg:epub-selection',
            `textLen=${typeof d.text === 'string' ? d.text.length : 0} ` +
              `reason=${String(d.reason ?? '-')} fast=${d.fast === true} ` +
              `cfi=${typeof d.cfi === 'string' ? 'yes' : 'null'} page=${String(d.pageNumber ?? '-')}`,
          );
          return;
        }
        rec(`msg:${d.type}`, `id=${String(d.id ?? '-')} page=${String(d.pageNumber ?? '-')}`);
      },
      true,
    );

    const ring: string[] = [];
    const originalPush = Array.prototype.push.bind(ring) as (v: string) => number;
    ring.push = (...args: string[]): number => {
      const lines = (new Error().stack ?? '').split('\n').map((l) => l.trim());
      const site =
        lines
          .filter((l) => l.includes('ReaderWorkspace') || l.includes('/src/'))
          .slice(0, 2)
          .join(' <- ')
          .replace(/https?:\/\/[^\s)]+/g, '')
          .slice(0, 150) || '(unknown)';
      rec('dismissToolbar', `${String(args[0] ?? '?')}   caller= ${site}`);
      return originalPush(args[0] ?? '');
    };
    w.__toolbarDismissals = ring;

    return `${window.innerWidth}x${window.innerHeight}`;
  });
}

/** Empties the buffer without reinstalling the listeners. */
export async function resetTrace(page: Page): Promise<void> {
  await page.evaluate(() => {
    const w = window as unknown as { __rt?: unknown[]; __rtT0?: number };
    if (w.__rt) w.__rt.length = 0;
    w.__rtT0 = performance.now();
  });
}

export async function readTrace(page: Page): Promise<TraceEntry[]> {
  return (await page.evaluate(
    () => (window as unknown as { __rt?: TraceEntry[] }).__rt ?? [],
  )) as TraceEntry[];
}

export function formatTrace(trace: TraceEntry[]): string {
  return trace
    .map((e) => `  t+${String(e.t).padStart(8)}ms  ${e.kind.padEnd(24)} ${e.detail}`)
    .join('\n');
}

/**
 * Samples how many floating menus are on screen, into the same trace.
 *
 * ## Why a sampler and not two reads
 *
 * The defect this exists for is a menu that is up for a moment and then is not:
 * a before/after pair of reads reports "1" if the sample happens to land inside
 * the window and "0" if it lands after, and neither says WHEN it went. A 50 ms
 * sampler turns "appeared and disappeared" into timestamps, which is what makes
 * the sequence readable — and what separates "it never opened" from "something
 * closed it".
 *
 * The counts are read from the PARENT document, where both menus are rendered.
 */
export async function startMenuSampler(page: Page, everyMs = 50): Promise<void> {
  await page.evaluate((period) => {
    const w = window as unknown as {
      __rtMenuTimer?: ReturnType<typeof setInterval> | null;
      __rtRec?: (kind: string, detail: string) => void;
    };
    if (!w.__rtRec) return;
    if (w.__rtMenuTimer) clearInterval(w.__rtMenuTimer);
    w.__rtMenuTimer = setInterval(() => {
      const toolbar = document.querySelectorAll('.selection-toolbar').length;
      const highlight = document.querySelectorAll('[data-testid="highlight-context-menu"]').length;
      w.__rtRec!('menus', `toolbar=${toolbar} highlight=${highlight}`);
    }, period);
  }, everyMs);
}

export async function stopMenuSampler(page: Page): Promise<void> {
  await page.evaluate(() => {
    const w = window as unknown as {
      __rtMenuTimer?: ReturnType<typeof setInterval> | null;
    };
    if (w.__rtMenuTimer) clearInterval(w.__rtMenuTimer);
    w.__rtMenuTimer = null;
  });
}
