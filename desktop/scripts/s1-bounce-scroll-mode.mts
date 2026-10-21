/**
 * S1b — the BOUNCE test, in the default SCROLL mode.
 *
 * For each of N seeded opens: open the book, press Next the instant the restore
 * is in flight, and sample the page field every 200 ms for 8 s. A bounce is a
 * field that moves off the open value and then returns to it — the CFI restore
 * pulling back a committed turn.
 *
 * The open value varies per book, so the test records the FULL series and counts
 * a bounce as: a value V appears, a different value W appears later, and then V
 * appears again after W.
 *
 *   bun run scripts/s1-bounce-scroll-mode.mts
 */
import { chromium, type Page } from '@playwright/test';
import { goToLibrary, openBook, waitForChapterFrame } from '../e2e/tauri/harness/readerFlows';
import { noUsableEpubReason, selectUsableEpub } from '../e2e/tauri/harness/readerCapacity';

const RUNS = Number(process.env.NEXO_S1_RUNS ?? '10');
const SAMPLE_MS = 200;
const WINDOW_MS = 9000;

const browser = await chromium.connectOverCDP(
  `http://127.0.0.1:${process.env.NEXO_CDP_PORT ?? '9222'}`,
  { isLocal: true },
);

try {
  let page: Page | null = null;
  for (const context of browser.contexts())
    for (const candidate of context.pages())
      if (candidate.url().startsWith('http://127.0.0.1:1420')) page = candidate;
  if (!page) throw new Error('app page not found');
  const app = page;

  const { found, candidates } = await selectUsableEpub(app, (attempted, total, title) => {
    console.log(`[s1] candidate ${attempted}/${total}: ${title}`);
  });
  if (!found) throw new Error(noUsableEpubReason(candidates));
  const book = found.book;
  console.log(`book: ${book.title} (${found.textLen} chars of prose)`);

  const readPage = async (): Promise<number | null> =>
    app.evaluate(() => {
      const input = document.querySelector(
        '[data-testid="bottom-bar-controls"] input',
      ) as HTMLInputElement | null;
      if (!input) return null;
      const n = Number.parseInt(input.value, 10);
      return Number.isFinite(n) ? n : null;
    });

  const readPanel = async (): Promise<Record<string, string>> =>
    app.evaluate(() => {
      const panel = document.querySelector('[data-testid="reader-diagnostics"]');
      const rows: Record<string, string> = {};
      if (panel)
        panel.querySelectorAll('dt').forEach((dt, i) => {
          const dd = panel.querySelectorAll('dd')[i];
          if (dd) rows[(dt.textContent ?? '').trim()] = (dd.textContent ?? '').trim();
        });
      return rows;
    });

  const results: Array<{ run: number; series: number[]; bounced: boolean }> = [];

  for (let run = 1; run <= RUNS; run++) {
    await app.keyboard.press('Escape').catch(() => undefined);
    await goToLibrary(app).catch(async () => {
      await app.keyboard.press('Escape');
      await app.waitForTimeout(200);
      await goToLibrary(app);
    });
    await openBook(app, book);
    await waitForChapterFrame(app).catch(() => null);

    const series: number[] = [];
    // Press Next IMMEDIATELY — while the restore is in flight.
    await app.keyboard.press('ArrowRight').catch(() => undefined);

    const deadline = Date.now() + WINDOW_MS;
    while (Date.now() < deadline) {
      const p = await readPage();
      if (p !== null) series.push(p);
      await app.waitForTimeout(SAMPLE_MS);
    }

    // A bounce: some value V, then a different W, then V again.
    let bounced = false;
    const uniq: Array<{ v: number; first: number; last: number }> = [];
    series.forEach((v, i) => {
      const found = uniq.find((u) => u.v === v);
      if (found) found.last = i;
      else uniq.push({ v, first: i, last: i });
    });
    for (const a of uniq) {
      for (const b of uniq) {
        if (b.v > a.v && a.first < b.first && a.last > b.last) bounced = true;
      }
    }
    results.push({ run, series, bounced });
    console.log(
      `run ${String(run).padStart(2)}: ${series.join(',')}  ${bounced ? 'BOUNCE' : 'clean'}  panel=${JSON.stringify(await readPanel())}`,
    );
  }

  const bounced = results.filter((r) => r.bounced);
  console.log('');
  console.log(
    JSON.stringify(
      {
        runs: RUNS,
        bounces: bounced.length,
        bounceRate: `${bounced.length}/${RUNS}`,
        bouncedRuns: bounced.map((b) => b.run),
      },
      null,
      2,
    ),
  );
} finally {
  await browser.close();
}
