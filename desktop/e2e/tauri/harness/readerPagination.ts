/**
 * Driving paginated mode in the real reader, and measuring what it claims.
 *
 * ## Why this is its own module
 *
 * `readerPaging.ts` drives the *scrolling* reader; the assertions in
 * `readerPagination.spec.ts` are about a different unit — the column. The two
 * modes report two different, correct page counts, and the whole point of the
 * spec is that the header says which one it is talking about. Keeping the
 * mode-switch and the column readers here means the spec states the claim and
 * this module states how it is observed.
 *
 * ## The three facts that shape everything below
 *
 * 1. **The columns are laid out on the chapter document's root, not on `body`.**
 *    The injected style rule targets `html`; `body` always reports
 *    `column-width: auto` and `scrollWidth === clientWidth`. Measuring `body`
 *    gives a confident, wrong "one column" for a chapter that has 25. Every
 *    reader here goes through `document.documentElement`.
 *
 * 2. **Paginated mode is a persisted reader setting**, so it can be seeded over
 *    IPC before the book is opened (`readerReset.ts` owns that reset). Toggling
 *    it through the settings UI additionally proves the control works, which is
 *    why the spec does both: seed for the measurement, toggle for the promise.
 *
 * 3. **Vite HMR does not reload `.svelte.ts` modules in this app.** A change to
 *    `useEpubPagination.svelte.ts` is invisible to an already-open page; only a
 *    reload picks it up. Anything that measures the page field after touching a
 *    `.svelte.ts` file must reload first or it measures the previous build.
 */
import { expect, type Frame, type Page } from '@playwright/test';
import { findChapterFrame, waitForChapterFrame } from './readerFlows';

/** What the header's page field claims right now. */
export type PageFieldClaim = {
  /** The number in the field, or null when it is not readable. */
  current: number | null;
  /** The "of N" total the field prints, or null when it is not readable. */
  total: number | null;
};

/**
 * Reads the header's page field and its "of N" total.
 *
 * The total is the `<span aria-hidden="true">` that follows the input inside
 * `PageInput.svelte`; it is read from the text ("/ 25") rather than by position
 * so a layout change inside the bar cannot silently hand back the zoom label.
 */
export async function readPageFieldClaim(page: Page): Promise<PageFieldClaim> {
  return page.evaluate(() => {
    const controls = document.querySelector('[data-testid="bottom-bar-controls"]');
    const input = controls?.querySelector('input') as HTMLInputElement | null;
    const totalSpan = controls?.querySelector('span[aria-hidden="true"]') as HTMLElement | null;
    const current = input ? Number.parseInt(input.value, 10) : Number.NaN;
    // The span renders "/ 25"; the digits are the total.
    const totalDigits = (totalSpan?.textContent ?? '').replace(/[^0-9]/g, '');
    const total = totalDigits.length > 0 ? Number.parseInt(totalDigits, 10) : Number.NaN;
    return {
      current: Number.isFinite(current) ? current : null,
      total: Number.isFinite(total) ? total : null,
    };
  });
}

/** The chapter's real column layout, measured where the rule is applied. */
export type ColumnLayout = {
  /** Number of page boxes the chapter occupies. */
  pageCount: number;
  /** The page box width, from the root's `clientWidth`. */
  columnWidth: number;
  /** The root's full inline extent. */
  scrollWidth: number;
  /** The root's inline offset — which page of the column set is on screen. */
  scrollLeft: number;
  /** 1-based page derived from the offset, or null when the offset is off-grid. */
  page: number | null;
};

/**
 * Measures the chapter's column layout from the frame's `documentElement`.
 *
 * `scrollLeft` is converted to a page index only when the offset is a whole
 * number of page boxes: a mid-reflow offset (the CFI restore, a `snapToGrid`
 * that has not landed) is not a page, and rounding it would report a page the
 * reader is not on.
 */
export async function readColumnLayout(frame: Frame): Promise<ColumnLayout | null> {
  return frame
    .evaluate(() => {
      const root = document.documentElement;
      const style = root ? getComputedStyle(root) : null;
      const columnWidth = root?.clientWidth ?? 0;
      const scrollWidth = root?.scrollWidth ?? 0;
      const scrollLeft = root?.scrollLeft ?? 0;
      const columns = style?.columnCount ? Number.parseInt(style.columnCount, 10) : Number.NaN;

      // `column-count` is what the injected rule writes; when it is absent the
      // extent itself is the count. Either way the answer comes from the live
      // layout, never from a second derivation of what the stylesheet meant.
      const pageCount = Number.isFinite(columns) && columns > 0
        ? columns
        : columnWidth > 0
          ? Math.round(scrollWidth / columnWidth)
          : 0;
      const onGrid = columnWidth > 0 && scrollLeft % columnWidth === 0;
      return {
        pageCount,
        columnWidth,
        scrollWidth,
        scrollLeft,
        page: onGrid && columnWidth > 0 ? Math.round(scrollLeft / columnWidth) + 1 : null,
      };
    })
    .catch(() => null);
}

/**
 * Waits until the chapter reads as a multi-column layout with a stable column
 * count, then returns it.
 *
 * A chapter opening is not one document: the reader replaces its `srcdoc` as
 * assets settle (measured: three swaps for one opening, with the column set
 * growing 9 -> 15 -> 25 pages between them). Sampling until the count stops
 * moving is what makes "the real count is 25" a statement about the settled
 * chapter instead of about the instant it was asked.
 */
export async function settledColumnLayout(
  page: Page,
  options: { timeoutMs?: number; minPageCount?: number } = {},
): Promise<{ frame: Frame; layout: ColumnLayout }> {
  const timeoutMs = options.timeoutMs ?? 20_000;
  const minPageCount = options.minPageCount ?? 2;
  const deadline = Date.now() + timeoutMs;
  let previous: ColumnLayout | null = null;
  let stable = 0;

  while (Date.now() < deadline) {
    const frame = await findChapterFrame(page);
    const layout = frame ? await readColumnLayout(frame) : null;
    const settled =
      layout !== null &&
      layout.pageCount >= minPageCount &&
      previous !== null &&
      layout.pageCount === previous.pageCount &&
      layout.scrollWidth === previous.scrollWidth;
    stable = settled ? stable + 1 : 0;
    previous = layout;
    if (settled && stable >= 2 && frame) return { frame, layout };
    await page.waitForTimeout(300);
  }

  throw new Error(
    `the chapter never settled as a multi-column layout (last seen: ` +
      `${previous ? `${previous.pageCount} pages, scrollWidth ${previous.scrollWidth}` : 'no frame'}). ` +
      `A single-column reading means either the chapter is not paginated or ` +
      `it is short enough to fit in one page box.`,
  );
}

/**
 * Enters paginated mode through the reader's own settings UI.
 *
 * ## Why the toggle is matched by its two states
 *
 * The top bar's settings button names the state it will *enter*: it reads
 * "Ajustes de texto" / "Text settings" while the panel is closed and
 * "Cerrar ajustes" / "Close settings" once it is open. Matching only the first
 * form hangs on a panel a previous spec left open, so the click here is
 * conditional on the closed label being present.
 *
 * The reading-mode control is a bits-ui `Select`: a `button` trigger inside
 * `[data-testid="reading-mode-select"]`, with the options portalled out. The
 * option is chosen by its label and both shipped locales are matched — a spec
 * that pinned one language would fail on the other machine.
 */
export async function openPaginatedMode(
  page: Page,
  options: { keepOpen?: boolean } = {},
): Promise<void> {
  const closed = page.getByRole('button', { name: /^(Ajustes de texto|Text settings)$/ });
  if ((await closed.count()) > 0) {
    await closed.first().click();
  }

  const select = page.locator('[data-testid="reading-mode-select"]');
  await expect(select).toBeVisible({ timeout: 15_000 });
  // Entering the mode is a state, not an action: if a previous run already left
  // the select on "Páginas", choosing it again would be a no-op and the caller
  // would go on to measure a chapter that was never re-laid-out.
  const label = ((await select.textContent()) ?? '').trim();
  if (!/^(Páginas|Pages)$/.test(label)) {
    await select.locator('button').first().click();
    const option = page.getByRole('option', { name: /^(Páginas|Pages)$/ }).first();
    await expect(option).toBeVisible({ timeout: 10_000 });
    await option.click();
  }

  // A caller that goes on to change another setting keeps the panel open: the
  // zoom and text controls live inside it, and closing then reopening it costs
  // two animations and makes the next control unavailable in between.
  if (options.keepOpen) return;

  // The panel's own close control is INSIDE the sidebar; the top bar's toggle is
  // behind the panel's backdrop and cannot be clicked until the panel is gone.
  await closeTextSettings(page);
}

/** Opens the reader's text-settings sidebar. */
export async function openTextSettings(page: Page): Promise<void> {
  const toggle = page.getByRole('button', { name: /^(Ajustes de texto|Text settings)$/ });
  if ((await toggle.count()) > 0) await toggle.first().click();
  await expect(page.locator('[data-testid="reading-mode-select"]')).toBeVisible({
    timeout: 15_000,
  });
}

/** Closes the reader's text-settings sidebar if it is open. */
export async function closeTextSettings(page: Page): Promise<void> {
  const sidebar = page.getByRole('dialog', { name: /^(Ajustes de texto|Text settings)$/ });
  const panelClose = sidebar.getByRole('button', {
    name: /^(Cerrar ajustes|Close settings)$/,
  });
  if ((await panelClose.count()) > 0) {
    await panelClose.first().click();
  }
  await expect(page.locator('[data-testid="reading-mode-select"]')).toBeHidden({
    timeout: 10_000,
  });
}

/** The reader's zoom label, e.g. "150%" -> 150; null when there is no control. */
export async function readZoomPercent(page: Page): Promise<number | null> {
  const trigger = page.locator('[data-testid="zoom-dropdown-trigger"]');
  if ((await trigger.count()) === 0) return null;
  const label = (await trigger.textContent()) ?? '';
  const parsed = Number.parseInt(label.replace(/[^0-9]/g, ''), 10);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Picks a zoom level other than `current` and returns the new percentage.
 *
 * The zoom changes the page box, which is the whole reason the count must be
 * re-derived per zoom: a chapter that is 25 columns at 100% is a different set of
 * columns at 150%. The option is chosen by exclusion rather than by a hardcoded
 * percentage, so the spec does not depend on the shipped option list.
 */
export async function selectZoomOtherThan(page: Page, current: number | null): Promise<number | null> {
  await page.locator('[data-testid="zoom-dropdown-trigger"]').click();
  const dropdown = page.locator('[data-testid="zoom-dropdown-list"]');
  await expect(dropdown).toBeVisible({ timeout: 10_000 });

  const options = dropdown.locator('[role="option"]');
  const count = await options.count();
  for (let index = 0; index < count; index++) {
    const label = (await options.nth(index).textContent()) ?? '';
    const percent = Number.parseInt(label.replace(/[^0-9]/g, ''), 10);
    if (!Number.isFinite(percent) || percent === current) continue;
    await options.nth(index).click();
    await expect.poll(async () => readZoomPercent(page), { timeout: 15_000 }).toBe(percent);
    return percent;
  }
  throw new Error(
    `the zoom menu offered no level other than ${current}% ` +
      `(${count} option(s) seen), so the count cannot be measured twice.`,
  );
}

/**
 * Seeks to a chapter with enough prose to paginate into several columns.
 *
 * A book's opening chapter is frequently a cover or a part title, which fits in
 * **one** page box: measured on *Prisioneros de la geografía*, chapter 5 lays out
 * 173 characters into `scrollHeight` 737 against a 583px box — it overflows
 * vertically and produces no second column at all, so there is nothing to turn
 * to. Chapter 16 lays the same book out at 52 008 characters and 18-20 columns.
 *
 * ## Why the field is set rather than turned through
 *
 * In the broken state a turn advanced the columns but not the field, so driving
 * by turn count would navigate differently before and after the fix. Setting the
 * field is the one navigation that behaves identically in both trees — and it is
 * also the reader's own "go to page".
 *
 * ## Why a failed candidate does not mean the chapter is short
 *
 * Measured: setting the field to 1 while the reader is already on 1 is a no-op,
 * which leaves the *previous* chapter's document in the frame and reads its
 * layout as if it belonged to chapter 1. So the sweep starts from a chapter it
 * has not visited and skips any candidate whose field did not actually land on
 * the value asked for.
 */
/**
 * A named book's library row, and whether the app's reader is currently open —
 * the two facts a spec needs to put itself in a known place.
 *
 * Opening a book is done through `readLibraryRows` + `openBook` rather than
 * `openUsableEpub` for the layout specs: the latter opens whatever position the
 * reader was left on, and *Supercomunicadores* / *Prisioneros* can land on a
 * synopsis that fits in one page box — measured: one column, a forward turn
 * disabled, and nothing to paginate.
 */
export async function readBookRow(
  page: Page,
  titlePattern: RegExp,
): Promise<{ id: string; title: string } | null> {
  const rows = await page.evaluate(async () => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    return (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as { id: string; title: string; format?: string; filePath?: string }[];
  });
  const row = rows.find(
    (candidate) =>
      titlePattern.test(candidate.title) && (candidate.format ?? '').toLowerCase() === 'epub',
  );
  return row ? { id: row.id, title: row.title } : null;
}

/** True when the EPUB reader's chapter frame is on screen. */
export async function isReaderOpen(page: Page): Promise<boolean> {
  return (await page.locator('iframe[title="chapter"]').count()) > 0;
}

/**
 * Opens a named library book and puts it in paginated mode on a settled column
 * layout, without any of the probes' assumptions about which book opened last.
 *
 * The caller names a *pattern* that must match one row's title (`/Supercom/`).
 * A book chosen by measurement (`openUsableEpub`) is the right default for a
 * general spec, but it opens "wherever the reader was", and some books open on a
 * chapter that fits in one page box — measured on *Prisioneros de la geografía*,
 * whose stored position lands on its synopsis: one column, a forward turn
 * disabled, and nothing to paginate. A spec that has to page *inside* a chapter
 * therefore pins both the book and the mode.
 */
export async function openPaginatedBook(
  page: Page,
  titlePattern: RegExp,
): Promise<{ frame: Frame; layout: ColumnLayout; title: string }> {
  const book = await readBookRow(page, titlePattern);
  if (!book) throw new Error(`no library book matches ${titlePattern}`);

  if (await isReaderOpen(page)) {
    await page.locator('header button').first().click();
    await page.locator('aside nav > button').first().waitFor({ timeout: 30_000 });
  }
  const libraryNav = page.locator('aside nav').getByRole('button', {
    name: /^(Library|Estanter[ií]a)$/,
  });
  if ((await libraryNav.count()) > 0) await libraryNav.first().click();
  const card = page.locator('article').filter({ hasText: book.title }).first();
  await expect(card).toBeVisible({ timeout: 30_000 });
  await card
    .getByRole('button', {
      name: /^(Read|Continue reading|Open book|Start|Start reading|Leer|Seguir leyendo|Abrir|Abrir libro|Empezar|Continuar lectura|Empezar lectura)$/,
    })
    .first()
    .click();
  await waitForChapterFrame(page);
  await openPaginatedMode(page, { keepOpen: true });
  await page.waitForTimeout(2_500);

  const settled = await settledColumnLayout(page, { timeoutMs: 30_000 });
  return { ...settled, title: book.title };
}

/**
 * Opens a named book, enters paginated mode and parks on `column`, confirming
 * the reader really landed there.
 *
 * A parked column is the precondition for the two claims a recount must uphold:
 * it must not move (`currentPage` stays) and it must clamp when the new count is
 * smaller. Column 20 on a 52-column chapter is deep enough that a zoom-out has
 * to clamp and a font-size change has to leave it alone.
 */
export async function parkOnColumn(
  page: Page,
  titlePattern: RegExp,
  column: number,
): Promise<{ layout: ColumnLayout; frame: Frame }> {
  const opened = await openPaginatedBook(page, titlePattern);
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await input.fill(String(column));
  await input.blur();
  await expect
    .poll(async () => (await readPageFieldClaim(page)).current, { timeout: 15_000 })
    .toBe(column);
  const frame = (await findChapterFrame(page)) ?? opened.frame;
  const layout = (await readColumnLayout(frame)) ?? opened.layout;
  return { layout, frame };
}

export async function seekMultiColumnChapter(
  page: Page,
  options: { candidates?: number[] } = {},
): Promise<{ frame: Frame; layout: ColumnLayout }> {
  const candidates = options.candidates ?? [16, 20, 24, 28, 12, 8, 32, 4];
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  let last: { frame: Frame; layout: ColumnLayout } | null = null;

  for (const target of candidates) {
    await input.fill(String(target));
    // `onchange` on a text input fires on blur, so the commit is leaving the field.
    await input.blur();
    // A chapter render is a real backend read plus an `srcdoc` swap, and the
    // layout re-measures as the new document settles.
    await page.waitForTimeout(4_500);

    const landed = (await readPageFieldClaim(page)).current === target;
    if (!landed) continue;

    const frame = await findChapterFrame(page);
    const layout = frame ? await readColumnLayout(frame) : null;
    if (!frame || !layout || layout.pageCount <= 1) {
      if (frame && layout) last = { frame, layout };
      continue;
    }
    // "Go to page" in paginated mode addresses a COLUMN, so the number just
    // typed was read as one: the reader is now parked on column `target` of a
    // chapter with `pageCount` columns. A chapter whose count is below the
    // requested number has already been clamped to its last column, where a
    // forward turn cannot advance — so only a count strictly above `target`
    // leaves room to turn.
    if (layout.pageCount > target) return { frame, layout };
    last = { frame, layout };
  }

  throw new Error(
    `no chapter of this book laid out in more than one page box with room to turn (tried ` +
      `${candidates.join(', ')}; last seen: ${
        last
          ? `${last.layout.pageCount} page(s), scrollWidth ${last.layout.scrollWidth} ` +
            `against a ${last.layout.columnWidth}px box`
          : 'no chapter frame'
      }).`,
  );
}

/**
 * The header page field's claim before and after one turn.
 *
 * ## Why this is not a click and a sleep
 *
 * In **scrolling** mode a turn is a real backend chapter extraction plus an
 * `srcdoc` swap, and the field is committed-render state: the commit lands 0.5 s
 * at best and several seconds from a cold chapter (`readerPaging.ts` documents
 * the full measurement). A press that has not committed after a fixed sleep
 * reads the pre-turn value and looks identical to a frozen field, which is the
 * exact confusion this spec exists to remove. So a scroll-mode turn is bound to
 * "the field moved" with a real budget.
 *
 * In **paginated** mode the field must move within a frame or two — a turn
 * writes `scrollLeft` synchronously — so a single settle is both sufficient and
 * the honest test. A long wait there would paper over the defect by giving a
 * broken field time to catch up through some other path.
 *
 * A press that moved nothing is retried (a click can be swallowed — the reader's
 * CFI restore pulls a committed turn back), so the assertion that follows is
 * about the field being frozen, not about one unlucky click.
 */
export async function readClaimAroundTurn(
  page: Page,
  direction: 1 | -1,
  options: { paginated?: boolean } = {},
): Promise<{ before: PageFieldClaim; after: PageFieldClaim; previous: PageFieldClaim | null }> {
  const paginated = options.paginated ?? false;
  // prev is index 0, next is index 1 (`ReaderBottomBar`).
  const control = page
    .locator('[data-testid="bottom-bar-controls"] button')
    .nth(direction === 1 ? 1 : 0);
  // The control can legitimately be disabled (the book's first or last
  // document), which is a state this helper reports by making no move rather
  // than by throwing: the caller's assertion is about the field, and a
  // positional precondition belongs to the caller.
  if (!(await control.isEnabled().catch(() => false))) {
    const claim = await readPageFieldClaim(page);
    return { before: claim, after: claim, previous: null };
  }

  const before = await readPageFieldClaim(page);
  let previous: PageFieldClaim | null = null;

  for (let attempt = 0; attempt < 6; attempt++) {
    // A retry can land after the control became unavailable (a turn at the end
    // of the book), so the enabled check is repeated rather than assumed.
    if (!(await control.isEnabled().catch(() => false))) break;
    await control.click();
    if (paginated) {
      await page.waitForTimeout(700);
      return { before, after: await readPageFieldClaim(page), previous };
    }
    const deadline = Date.now() + 12_000;
    while (Date.now() < deadline) {
      await page.waitForTimeout(250);
      const current = await readPageFieldClaim(page);
      if (current.current !== null && current.current !== before.current) {
        return { before, after: current, previous };
      }
    }
    const now = await readPageFieldClaim(page);
    previous = previous ?? now;
  }

  return { before, after: previous ?? (await readPageFieldClaim(page)), previous };
}
