/**
 * Does a library book actually render prose? Measured, never inferred.
 *
 * ## Why this module exists
 *
 * The CDP specs pick a book from the developer's real library. Picking by
 * *metadata* is not enough to know the spec can run: the library is not a
 * fixture, and a book that lists as an EPUB with a cover can still render an
 * empty chapter.
 *
 * Measured on this machine, over the real IPC bridge — the seven non-stub
 * EPUBs, each opened, left to settle, and read where the reader landed:
 *
 *   book                             prose?   where it was found
 *   Prisioneros de la geografía      646      chapter 19 (ch. 1-18 render none)
 *   Armas, gérmenes y acero          80070    first prose chapter
 *   Don Quijote                      32524    first prose chapter
 *   Historia mínima de Colombia      3371     first prose chapter
 *   Freakonomics                     62791    first prose chapter
 *   El economista camuflado          prose    not on the opening chapter
 *   Supercomunicadores               prose    not on the opening chapter
 *
 * The "ch. 1-18 render none" note for *Prisioneros* predates commit `46d30046`
 * (the self-closed `<title/>` fix), which made those chapters render. It is kept
 * only as the reason the sweep exists; re-measured after the fix, the opening
 * chapters carry prose.
 *
 * The line that matters: **every one of these books has prose, and page 1 is
 * frequently a cover that has none.** *Armas* at chapter 1 is
 * `<title>Cubierta</title>` with an inline image — 64 KB of markup and exactly 0
 * characters of `innerText`, held stable across ten samples. So "does this book
 * render text" is answered by reading on, never by testing the opening chapter.
 *
 * Two things follow, and both shape this module:
 *
 *   1. **A text-less chapter is not a text-less book.** A full spine sweep of
 *      *Prisioneros* found prose only at page 19, after ten consecutive empty
 *      chapters. Calling that book "unusable" would be false.
 *   2. **No library field predicts it.** `genre`, `language` and
 *      `publicationDate` were checked verbatim on every row and separate nothing
 *      usable (`language` is `en` on books that are in Spanish; the empty-cover
 *      rows carry the same metadata as the rest). Filtering by metadata would be
 *      a guess wearing a filter's clothes.
 *
 * So capacity is established the only honest way: by opening the book in the
 * running app, reading on, and measuring what lands in the chapter frame.
 *
 * ## Why opening a book here is safe
 *
 * Opening writes the reader's location (`saveProgress`), which is real state in
 * the developer's library. Every function in this module therefore records the
 * location before it opens a book and puts it back afterwards, and reports the
 * book it left open so the caller can decide what to do next. A measurement
 * helper that silently walks someone's book forward would be this harness
 * damaging the very state it exists to protect.
 */
import { test, type Page } from '@playwright/test';
import {
  findChapterFrame,
  goToLibrary,
  openBook,
  readLibraryRows,
  usableEpubCandidates,
  type LibraryRow,
} from './readerFlows';

/** How long a chapter is given to settle before its text length is trusted. */
const SETTLE_STEP_MS = 700;

/**
 * How long a "zero text" reading must persist before it is believed.
 *
 * A chapter swap empties the document before refilling it, so a zero observed
 * mid-swap is a transient. Measured on this machine: a 2-second window was too
 * short and let one selection pick an empty-cover book over a book whose
 * chapters carry 62 791 characters of prose.
 */
const ZERO_CONFIRM_MS = 4_500;

/**
 * Characters of settled prose that count as "this chapter really rendered".
 *
 * Deliberately low. It answers "is there text on this page at all", which is the
 * question a *chapter* has to pass; a two-line part title clears it. Ranking a
 * whole *book* needs more — see {@link PROSE_FOR_SPECS}.
 */
export const PROSE_FLOOR = 24;

/**
 * Characters a book must render to be worth handing to a spec.
 *
 * A book can pass {@link PROSE_FLOOR} on a part title ("Primera parte", measured
 * at 24-105 characters) while having no prose the spec can select, scroll or
 * highlight. Measured on this machine, first reachable chapter: *Armas* 105,
 * *Freakonomics* 82, *El economista camuflado* 67 — all titles, while *Don
 * Quijote* 32 524 and *Historia mínima de Colombia* 3 371 are real prose. A book
 * below this floor is reported, not silently chosen.
 */
export const PROSE_FOR_SPECS = 1_000;

/**
 * The chapter frame's settled inner-text length.
 *
 * The reader assigns `iframeEl.srcdoc` asynchronously and **replaces it again as
 * a chapter's assets settle** (measured: three swaps for one opening). A single
 * sample therefore reads an empty document for a chapter that is about to
 * render — measured on this machine: an early sample called *Freakonomics*
 * `textLen 0`, and opening it and waiting reported `62791`. Sampling until the
 * value stops changing is what separates "no text" from "not yet text".
 *
 * ## Why a "stable" zero is not accepted immediately
 *
 * A chapter swap passes through a document that is **emptied before it is
 * refilled**, so two consecutive zero reads can both be true of a chapter that
 * is still arriving — measured: a run of this selection picked *El economista
 * camuflado* (chapter 1 empty) over *Freakonomics* (whose chapter 1 renders
 * 62791) because the latter was sampled across such a trough. A zero is
 * therefore only trusted after `minZeroObservations` consecutive zero reads over
 * at least `ZERO_CONFIRM_MS`, while any non-zero is trusted as soon as it holds
 * still. Nothing is traded away by waiting longer on a zero: an empty chapter
 * stays empty, and the only cost is latency on a book that is about to be
 * rejected anyway.
 *
 * Returns -1 when there is no chapter frame at all, which callers treat as
 * "cannot measure" rather than "measured empty".
 */
export async function settledChapterTextLen(
  page: Page,
  timeoutMs = 10_000,
  minZeroObservations = 3,
): Promise<number> {
  const deadline = Date.now() + timeoutMs;
  const zeroDeadline = Date.now() + ZERO_CONFIRM_MS;
  let previous = -1;
  let stable = 0;
  let zeros = 0;

  while (Date.now() < deadline) {
    const frame = await findChapterFrame(page);
    const current = frame
      ? await frame.evaluate(() => document.body.innerText.trim().length).catch(() => -1)
      : -1;

    stable = current >= 0 && current === previous ? stable + 1 : 0;
    previous = current;

    if (current > 0 && stable >= 1) return current;

    if (current === 0) {
      zeros += 1;
      // A stable zero is believed only after it has been seen repeatedly and the
      // confirmation window has elapsed; a zero that persists past both is a
      // chapter with nothing in it.
      if (stable >= 1 && zeros >= minZeroObservations && Date.now() >= zeroDeadline) return 0;
    }

    await page.waitForTimeout(SETTLE_STEP_MS);
  }

  return previous;
}

/** The location the reader has stored for `bookId`, or null when it has none. */
async function readProgress(
  page: Page,
  bookId: string,
): Promise<{ cfiLocation: string; percentage: number } | null> {
  return page.evaluate(async (id) => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');
    return (await internals.invoke('getProgress', { bookId: id })) as {
      cfiLocation: string;
      percentage: number;
    } | null;
  }, bookId);
}

/**
 * A book measured to render prose, with the evidence that says so.
 *
 * `textLen` is the number the claim rests on, and it is what a caller can put in
 * a failure message or a log line when a later step goes wrong. `page` and
 * `totalPages` are where the measurement happened to land, which is *not*
 * guaranteed to be chapter 1 — a spec that reset to page 1 and then measured
 * nothing would be back where it started.
 *
 * `rendersAtChapterOne` is the cheapness signal: prose on the chapter the book
 * opens into means a spec needs no search at all, while a book that renders
 * nothing there costs a sweep. {@link pickUsableEpub} prefers those. Note that a
 * *cover* is not prose, and neither is a part title — see
 * {@link measureEpubCapacity} and {@link PROSE_FOR_SPECS}.
 */
export type UsableBook = {
  book: LibraryRow;
  /** Settled prose length at the chapter the measurement landed on. */
  textLen: number;
  /** The reader page field at that chapter, or null when it was unreadable. */
  page: number | null;
  /** The TOC total the footer reported, or null when it was unreadable. */
  totalPages: number | null;
  /** True when chapter 1 is where the prose was found. */
  rendersAtChapterOne: boolean;
};

/** The reader footer's page field and total, read defensively. */
async function readPagerFields(
  page: Page,
): Promise<{ page: number | null; totalPages: number | null }> {
  return page.evaluate(() => {
    const controls = document.querySelector('[data-testid="bottom-bar-controls"]');
    const input = controls?.querySelector('input') as HTMLInputElement | null;
    const totalSpan = controls?.querySelector('span[aria-hidden="true"]') as HTMLElement | null;
    const current = input ? Number.parseInt(input.value, 10) : Number.NaN;
    const total = Number.parseInt((totalSpan?.textContent ?? '').replace(/[^0-9]/g, ''), 10);
    return {
      page: Number.isFinite(current) ? current : null,
      totalPages: Number.isFinite(total) ? total : null,
    };
  });
}

/**
 * Counts the heading elements in the chapter frame that is on screen.
 *
 * A spec that measures the heading rules (`reader.spec.ts`) needs a chapter
 * that *contains a heading*, and prose does not imply one. Measured on this
 * machine, prose chapters differ exactly here: *Prisioneros* 1 443 chars with one
 * `h3`, *Armas* 1 482 with an `h1`, *Freakonomics* 5 480 with an `h1` — but *Don
 * Quijote* 32 524 and *Historia mínima de Colombia* 3 371 with **zero** headings.
 * So "this book renders prose" is not an answer to "this book has headings", and
 * a spec that assumes it will fail on a book that is perfectly fine.
 *
 * Returns -1 when there is no chapter frame, which callers treat as "cannot
 * measure" rather than "measured empty".
 */
export async function settledChapterHeadingCount(page: Page): Promise<number> {
  const frame = await findChapterFrame(page);
  if (!frame) return -1;
  return frame
    .evaluate(() => document.querySelectorAll('h1, h2, h3, h4, h5, h6').length)
    .catch(() => -1);
}

/**
 * Opens `book`, finds a chapter that actually renders prose, and reports where
 * it was found — restoring the location the book had before the open.
 *
 * ## Why this seeks instead of testing chapter 1
 *
 * Chapter 1 is very often a **cover**, and a cover renders no prose. Measured on
 * this machine: *Armas, gérmenes y acero* at page 1 is `<title>Cubierta</title>`
 * with a `<div class="cubierta">` holding an inline image — 64 KB of markup and
 * `innerText` of exactly **0**. This was verified as the *settled* state (ten
 * samples over 4.5 s, all zero, with a stable `srcdoc`), so it is the page's
 * real content, not a sampling artifact. Testing chapter 1 would therefore
 * reject a book whose chapters are full of prose.
 *
 * What a reader spec actually needs is "some reachable chapter has text". That
 * is established the way a person establishes it: read on until there is
 * something on the page ({@link seekRenderedChapter}).
 *
 * ## What the returned fields mean
 *
 *   - `textLen` — settled prose at the chapter the search stopped on.
 *   - `rendersAtChapterOne` — true when the search found prose **without turning**,
 *     i.e. the book's opening chapter is already prose. This is the cheapness
 *     signal {@link pickUsableEpub} uses to prefer a book no spec has to search.
 *   - `page` / `totalPages` — where the search stopped, as the footer reports it.
 *
 * Returns null only when the book will not open at all. A book that opens and
 * renders nothing anywhere is a real result, reported with `textLen` below
 * {@link PROSE_FLOOR}, because "this book has no prose" and "this book is broken"
 * are different facts and the caller's skip message says which.
 */
export async function measureEpubCapacity(
  page: Page,
  book: LibraryRow,
): Promise<UsableBook | null> {
  const previous = await readProgress(page, book.id).catch(() => null);

  try {
    await goToLibrary(page);
    await openBook(page, book);

    // Chapter 1 is the deterministic start: the file's own front matter, not the
    // location the book happened to be left on. `resetToFirstChapter` forces a
    // real navigation even when the field already reads 1, so the frame is
    // rebuilt for this book rather than measured stale (see its docstring).
    await resetToFirstChapter(page);

    // Read on until *prose* appears — past covers and part titles, which clear
    // {@link PROSE_FLOOR} on their own ("Primera parte", measured 24-105 chars)
    // but give a spec nothing to select, scroll or highlight.
    const sought = await seekRenderedChapter(page, { resetToStart: false, floor: PROSE_FOR_SPECS });
    const { page: currentPage, totalPages } = await readPagerFields(page);

    return {
      book,
      textLen: sought.textLen,
      page: currentPage,
      totalPages,
      rendersAtChapterOne: sought.turns === 0 && sought.textLen >= PROSE_FOR_SPECS,
    };
  } catch {
    // A book that will not open is a candidate that cannot serve; the caller
    // reports the outcome, not this function.
    return null;
  } finally {
    // Put the location back. Opening stores one, and a measurement helper is not
    // entitled to move the developer's place in their own book. A restore the app
    // overwrites is reported rather than swallowed (see `restoreProgress`).
    if (previous) {
      const held = await restoreProgress(page, book.id, previous.cfiLocation, previous.percentage);
      if (!held) {
        console.warn(
          `[readerCapacity] could not restore the reading position of "${book.title}" ` +
            `(wanted ${previous.cfiLocation}). The app kept writing progress after the restore.`,
        );
      }
    }
  }
}

/**
 * Measures candidates and returns the first that renders **enough** prose,
 * **leaving that book open on its prose chapter**.
 *
 * ## Why it stops early, and why that is still honest
 *
 * Measuring every candidate is expensive — one open plus one search each, 3-8 s
 * per open — and paid inside a spec it exceeded every spec's timeout (measured:
 * 8 specs at 3.0 m of a 3.0 m budget). But the *ranking* only exists to avoid
 * handing a spec a book with nothing in it, and the first candidate that clears
 * {@link PROSE_FOR_SPECS} already answers that: it has real prose.
 *
 * The exception is the *cheapness* tie-break. A book that renders prose on the
 * chapter it opens into avoids a search for every later spec, so one such
 * candidate is worth a little more scanning:
 *
 *   1. scan until a candidate renders prose **without turning** — take it, stop;
 *   2. otherwise keep scanning to the end and take the richest book found, so a
 *      book that only renders deep (measured: *Prisioneros*, prose only from
 *      chapter 19) is still used rather than skipped;
 *   3. null when nothing clears {@link PROSE_FOR_SPECS} and nothing renders at
 *      all.
 *
 * ## Why the winner is re-opened after the scan
 *
 * Measuring a candidate leaves the reader on it, so when the loop stops on the
 * last one measured the winner may not be on screen — measured: a scan selected
 * *Armas* while the display showed *Supercomunicadores*. The winner is re-opened
 * before returning, so "which book does this return" and "which book is shown"
 * are the same answer.
 *
 * ## Selection rule for the fallback pool
 *
 * When no candidate renders prose on its opening chapter, the richest is taken —
 * most prose first, so the search a spec then has to do is as short as possible.
 * A book below {@link PROSE_FOR_SPECS} everywhere comes back with its real
 * `textLen`, and the caller skips with {@link noUsableEpubReason}.
 */
export async function pickUsableEpub(
  page: Page,
  candidates: LibraryRow[],
  onAttempt?: (attempted: number, total: number) => void,
): Promise<UsableBook | null> {
  const measured: UsableBook[] = [];
  let attempted = 0;

  for (const book of candidates) {
    attempted += 1;
    onAttempt?.(attempted, candidates.length);

    const result = await measureEpubCapacity(page, book);
    if (result) measured.push(result);

    // A book that renders prose where it opens needs no search from any spec:
    // take it and stop paying for opens. `rendersAtChapterOne` is measured, not
    // assumed, so this is the same signal the ranking below would use.
    if (result?.rendersAtChapterOne) return { ...result };
  }

  if (measured.length === 0) return null;

  const winner = measured.reduce((best, entry) => (entry.textLen > best.textLen ? entry : best));

  // The scan left the reader on the last candidate measured, not on the winner.
  // Put the winner back on screen so the returned row describes what is shown.
  const restored = await measureEpubCapacity(page, winner.book);
  return restored ?? winner;
}

/**
 * Reads the library, reduces it to real EPUB candidates and measures them until
 * one renders prose — then **caches the choice for the rest of the process**.
 *
 * The chosen book is **left open in a prose chapter**, which is the state every
 * caller wants.
 *
 * ## Why the result is cached
 *
 * Measuring every candidate is the only honest way to choose, and it costs one
 * open plus one search per candidate — measured at 3-8 s per open over 7
 * candidates, i.e. tens of seconds. Paid *per spec* it exceeds every spec's
 * timeout (measured: 3.0 m of a 3.0 m budget, 8 specs timing out). Paid once it
 * is a fixed preamble for the run.
 *
 * The `tauri-cdp` project runs with `workers: 1` and `fullyParallel: false`
 * (playwright.config.ts), so the specs share one worker process and a
 * module-level cache is shared between them. The cache holds **which book** was
 * chosen, never "the reader is already there": each spec still drives the app
 * into that book, because a previous spec legitimately moved it.
 *
 * A failed selection is cached too, so a library with no usable EPUB does not
 * pay the full scan once per spec to reach the same skip.
 *
 * `found === null` is a **skip**, not a failure — report it with
 * {@link noUsableEpubReason} so the run output says why.
 */
export async function selectUsableEpub(
  page: Page,
  onProgress?: (attempted: number, total: number, title: string) => void,
): Promise<{ found: UsableBook | null; candidates: LibraryRow[] }> {
  const rows = await readLibraryRows(page);
  const candidates = usableEpubCandidates(rows);

  const cached = selectionCache;
  if (cached && cached.candidatesFingerprint === fingerprint(candidates)) {
    onProgress?.(candidates.length, candidates.length, cached.found?.book.title ?? '(cached)');
    return { found: cached.found, candidates };
  }

  const found = await pickUsableEpub(page, candidates, (attempted, total) => {
    onProgress?.(attempted, total, candidates[attempted - 1]?.title ?? '(unknown)');
  });
  selectionCache = { candidatesFingerprint: fingerprint(candidates), found };

  return { found, candidates };
}

/** Module-level, so the scan is paid once per worker process (see above). */
let selectionCache: { candidatesFingerprint: string; found: UsableBook | null } | null = null;

/**
 * Reset the cache. Exported for a run that changes the library mid-session — the
 * fingerprint covers the ordinary case, but a caller that has just imported or
 * deleted a book can say so explicitly instead of relying on it.
 */
export function resetUsableEpubCache(): void {
  selectionCache = null;
}

/** Identifies a candidate list, so a changed library invalidates the cache. */
function fingerprint(candidates: LibraryRow[]): string {
  return candidates.map((row) => `${row.id}:${row.title}`).join('|');
}

/**
 * Opens the book {@link selectUsableEpub} chose and puts it on a prose chapter.
 *
 * ## Why this opens the book instead of assuming it
 *
 * `selectUsableEpub` is cached for the process (`workers: 1`), so from the second
 * spec onward it answers from the cache **without opening anything** — the reader
 * is then showing whatever the previous spec left there. An earlier revision of
 * this function only sought a prose chapter, so it reported the cached book's id
 * while the chapter on screen belonged to a *different* book. Measured in this
 * suite: the spec resolved *Don Quijote* while the reader held *Prisioneros*, and
 * every highlight it created was written to a book its assertions never read.
 *
 * So the book is opened here, and the returned id is the book whose chapter is on
 * screen. The reader's position is the one `openBook` restores; the seek then
 * reads on past covers and part titles to settled prose.
 *
 * Skips the caller with a reason when the library holds no usable book. Returns
 * `turns: 0` when the book was already on prose.
 */
export async function openUsableEpub(
  page: Page,
): Promise<{ book: LibraryRow; textLen: number; turns: number }> {
  const { found, candidates } = await selectUsableEpub(page);
  skipWhenNoUsableEpub(found, candidates);

  const book = found!.book;
  await openBook(page, book);
  const sought = await seekRenderedChapter(page);
  return { book, textLen: sought.textLen, turns: sought.turns };
}

/**
 * `test.skip` with {@link noUsableEpubReason} when there is no usable book.
 *
 * Takes the pair `selectUsableEpub` returns; kept here so every spec reports the
 * same honest reason instead of its own wording.
 */
export function skipWhenNoUsableEpub(found: UsableBook | null, candidates: LibraryRow[]): void {
  test.skip(found === null, noUsableEpubReason(candidates));
}

/**
 * The skip message for {@link pickUsableEpub} returning null.
 *
 * Names the candidates that were tried and the floor that was required, so a
 * human reading the run output can tell "this library has no EPUB that renders
 * prose" from "the harness is looking in the wrong place". A bare "skipped"
 * would hide both.
 */
export function noUsableEpubReason(candidates: LibraryRow[]): string {
  const names = candidates.map((row) => `"${row.title}"`).join(', ');
  return (
    `No library EPUB rendered at least ${PROSE_FOR_SPECS} characters of prose in any ` +
    `reachable chapter (tried ${candidates.length}: ${names || 'none'}). ` +
    `A book whose chapters are covers and part-titles cannot serve a reader spec.`
  );
}

/**
 * Drives the reader's page field to `target` and lets the navigation settle.
 *
 * Returns nothing; the caller waits for the field to confirm if it needs to.
 */
async function goToPageField(page: Page, target: number, settleMs = 1_800): Promise<void> {
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  if ((await input.count()) === 0) return;
  await input.fill(String(target));
  // `onchange` on a text input fires on blur, so the commit is leaving the field.
  await input.blur();
  await page.waitForTimeout(settleMs);
}

/**
 * Puts the reader on chapter 1 and returns only once the chapter **rendered**.
 *
 * ## Why setting the field is not enough
 *
 * Setting the page field to the value it already holds is a no-op: no
 * navigation, no re-render, and the chapter frame keeps whatever document was
 * already in it. Measured on this machine — measuring one candidate immediately
 * after another — *Armas* reported `rendersAtChapterOne: false` with `1482`
 * characters on one run and `true` with `41471` on the next, because the second
 * measurement read the *previous* candidate's document still in the frame. The
 * reader's opening position is itself ambient (the stored location), so "already
 * at chapter 1" is a state this has to force, not assume.
 *
 * The move is therefore made through a different page first, so a navigation is
 * guaranteed, and then back to 1. The step-away page is 2 when the book has one
 * and the field is already at 1; otherwise the field is set straight to 1, which
 * is itself a change.
 */
async function resetToFirstChapter(page: Page, settleMs = 2_200): Promise<void> {
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  if ((await input.count()) === 0) return;

  const current = await readPageField(page);
  if (current === 1) {
    // Force a real navigation so the frame is rebuilt for chapter 1's document
    // rather than left holding the previous book's.
    await input.fill('2');
    await input.blur();
    await page.waitForTimeout(settleMs);
  }

  await input.fill('1');
  await input.blur();
  await waitForPageFieldToReach(page, 1, 8_000);
  await page.waitForTimeout(settleMs);
}

/** The reader footer's page field, re-read defensively. */
async function readPageField(page: Page): Promise<number | null> {
  return page.evaluate(() => {
    const input = document.querySelector(
      '[data-testid="bottom-bar-controls"] input',
    ) as HTMLInputElement | null;
    const value = input ? Number.parseInt(input.value, 10) : Number.NaN;
    return Number.isFinite(value) ? value : null;
  });
}

/** The reader's Next control; the bottom bar orders prev (0) before next (1). */
function nextControl(page: Page) {
  return page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
}

/**
 * Puts the reader on a chapter that renders prose, the way a person reads on
 * until there is something on the page.
 *
 * ## Why this is needed at all
 *
 * A book's opening chapter is frequently a **cover with no text**. Measured on
 * this machine: *Armas, gérmenes y acero* renders exactly 0 characters of
 * `innerText` at page 1 (`<title>Cubierta</title>`, inline image, 64 KB of
 * markup) while its prose chapters carry 80 070. Measured too: *Prisioneros de
 * la geografía* has ten consecutive empty chapters before chapter 19 renders 646
 * characters, and the reader persists its opening location across runs — so
 * *which* chapter a spec lands on is ambient state it does not control. And at a
 * book's last chapter the Next control is `disabled`, which is how one spec in
 * this suite failed with `unexpected value "disabled"` while looking for prose.
 *
 * ## A click that does not move the page is retried, not read as the end
 *
 * Measured, sweeping this book with a 1.8 s settle: **the page field failed to
 * move on 12 of 21 clicks**, and every one of those chapters does have a next
 * chapter (`nextEnabled=true`). The reader's CFI restore pulls a committed turn
 * back — the same bounce `harness/readerPaging.ts` documents (`4,4,4,4,4,6,4,
 * 4,4,4`) — so "the field did not change" is normal app behaviour and says
 * nothing about whether the book continues. An earlier revision of this helper
 * treated it as end-of-book and gave up at chapter 4 with prose waiting at 19. A
 * click that moved nothing is therefore **retried**, and only the control's own
 * `disabled` state or the turn budget ends the sweep.
 *
 * The page field is **committed-render state** (`EpubNativeViewer` derives it
 * from `lastRenderedChapter`), so it is also used to wait for a turn to land
 * before the next chapter's text is measured; measuring mid-turn reads the
 * chapter being left.
 *
 * ## What it returns
 *
 * `turns` is 0 when the chapter the reader was already on renders prose, which
 * is the signal that no search was needed. `textLen` below {@link PROSE_FLOOR}
 * means nothing rendered within the budget. It asserts nothing — the caller
 * decides whether that is a skip or a failure, at the point where the missing
 * precondition is meaningful.
 */
export async function seekRenderedChapter(
  page: Page,
  options: {
    maxTurns?: number;
    perChapterMs?: number;
    resetToStart?: boolean;
    floor?: number;
  } = {},
): Promise<{ textLen: number; turns: number }> {
  const maxTurns = options.maxTurns ?? 24;
  const perChapterMs = options.perChapterMs ?? 6_000;
  const floor = options.floor ?? PROSE_FLOOR;
  // Callers that have just driven the reader to chapter 1 pass false, so the
  // reset is not repeated and its navigation does not empty the frame again.
  const resetToStart = options.resetToStart ?? true;

  if (resetToStart) {
    // Deterministic start, forcing a real navigation so the frame is rebuilt for
    // this book's chapter 1 instead of read stale (see `resetToFirstChapter`).
    await resetToFirstChapter(page);
  }

  const next = nextControl(page);

  for (let turn = 0; turn <= maxTurns; turn++) {
    const textLen = await settledChapterTextLen(page, perChapterMs);
    if (textLen >= floor) return { textLen, turns: turn };

    if (!(await next.isEnabled().catch(() => false))) return { textLen, turns: turn };

    // Up to two attempts, because one click can be swallowed by the CFI restore.
    for (let attempt = 0; attempt < 2; attempt++) {
      const before = await readPageField(page);
      await next.click();
      await waitForPageFieldToMove(page, before ?? -1, 5_000);
      if ((await readPageField(page)) !== before) break;
    }
  }

  return { textLen: await settledChapterTextLen(page, perChapterMs), turns: maxTurns };
}

/**
 * Reads on until the chapter on screen renders prose **and** carries a heading.
 *
 * A separate entry point from {@link seekRenderedChapter} because the two answer
 * different questions. Prose is what a selection, a scroll or a highlight needs;
 * a heading is what the injected heading rules can only be measured against, and
 * the books in this library split exactly along that line: *Don Quijote* renders
 * 32 524 characters of prose with **zero** headings, *Historia mínima de
 * Colombia* 3 371 with zero, while *Prisioneros* renders 1 443 with an `h3` and
 * *Freakonomics* 5 480 with an `h1` (all measured on this machine).
 *
 * So requiring a heading is not tightening the prose check — it is asking for the
 * property the spec is about. A spec that measured heading rules on a book
 * without headings would fail for a reason that says nothing about the rules.
 *
 * Returns the settled prose length and the heading count at the chapter it
 * stopped on; `headingCount === 0` means the budget ran out, and the caller
 * decides whether that is a skip or a failure.
 */
export async function seekChapterWithHeading(
  page: Page,
  options: { maxTurns?: number; perChapterMs?: number; floor?: number } = {},
): Promise<{ textLen: number; headingCount: number; turns: number }> {
  const maxTurns = options.maxTurns ?? 24;
  const perChapterMs = options.perChapterMs ?? 6_000;
  const floor = options.floor ?? PROSE_FLOOR;

  await resetToFirstChapter(page);
  const next = nextControl(page);

  let textLen = -1;
  for (let turn = 0; turn <= maxTurns; turn++) {
    textLen = await settledChapterTextLen(page, perChapterMs);
    if (textLen >= floor) {
      const headingCount = await settledChapterHeadingCount(page);
      if (headingCount > 0) return { textLen, headingCount, turns: turn };
    }

    if (!(await next.isEnabled().catch(() => false))) break;

    for (let attempt = 0; attempt < 2; attempt++) {
      const before = await readPageField(page);
      await next.click();
      await waitForPageFieldToMove(page, before ?? -1, 5_000);
      if ((await readPageField(page)) !== before) break;
    }
  }

  return { textLen, headingCount: await settledChapterHeadingCount(page), turns: maxTurns };
}

/**
 * Waits until the reader's page field reports exactly `target`.
 *
 * Used after the reset that puts a book on chapter 1: the field is
 * committed-render state, so it reaching `target` is the signal that the chapter
 * being measured is the chapter that was asked for.
 */
async function waitForPageFieldToReach(
  page: Page,
  target: number,
  timeoutMs: number,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await readPageField(page)) === target) return;
    await page.waitForTimeout(250);
  }
}

/**
 * Waits until the reader's page field leaves `from`, or the budget expires.
 *
 * `from` of -1 (the field could not be read) returns immediately rather than
 * waiting on a comparison that can never be made.
 */
async function waitForPageFieldToMove(page: Page, from: number, timeoutMs: number): Promise<void> {
  if (from < 0) return;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await readPageField(page)) !== from) return;
    await page.waitForTimeout(250);
  }
}

/**
 * Restores a book's stored location and confirms the write held.
 *
 * ## Why a single write is not enough
 *
 * Opening a book and reading through it makes the **app** write progress, not
 * just this helper — the reader streams its location as chapters land. A restore
 * issued while that stream is still in flight is overwritten by the next app
 * write. Measured on this machine, across a full 7-candidate selection: 6 books
 * came back unchanged and *Prisioneros de la geografía* — the one book the scan
 * had to turn 18 chapters through — came back advanced by one chapter
 * (`/6/41!/4/2/4` → `/6/43!/4/2/2/4/2/2/2`). Leaving that alone would mean this
 * harness silently moving the developer's place in their own book, which is
 * exactly the ambient-state damage it exists to prevent.
 *
 * So the write is verified rather than assumed, and repeated until it holds or
 * the budget runs out. Each attempt re-issues the same `saveProgress` the app's
 * own reader uses, which is the same call the smoke spec's teardown makes.
 *
 * Returns true when the stored value matched `location` on the final check, so a
 * caller can report a restore it could not hold instead of staying silent.
 */
async function restoreProgress(
  page: Page,
  bookId: string,
  location: string,
  percentage: number,
  attempts = 4,
): Promise<boolean> {
  for (let attempt = 0; attempt < attempts; attempt++) {
    await page
      .evaluate(
        async ([id, cfi, pct]) => {
          const internals = (
            window as unknown as {
              __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
            }
          ).__TAURI_INTERNALS__;
          await internals?.invoke('saveProgress', {
            payload: { bookId: id, cfiLocation: cfi, percentage: pct },
          });
        },
        [bookId, location, percentage] as const,
      )
      .catch(() => undefined);

    // Give a late app write the chance to land before checking, so the check is
    // not satisfied by the value merely still being in flight.
    await page.waitForTimeout(1_200);
    const current = await readProgress(page, bookId).catch(() => null);
    if (current?.cfiLocation === location) return true;
  }
  return false;
}
