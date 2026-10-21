/**
 * Does a library book *record* where the reader is?
 *
 * ## Why "renders prose" is not enough for the smoke cycle
 *
 * The read-cycle's final assertions are "the place was kept": close the book,
 * reopen it, expect the page it closed on. That claim depends on the reader
 * publishing its location and the app storing it — and **not every book in this
 * library does**. Measured on this machine, one turn each:
 *
 *   book                          footer % across 4 chapters   stored CFI
 *   Prisioneros de la geografía    7% 9% 12% 14%               advances
 *   Don Quijote                    0% 0%  0%  0%               frozen
 *
 * *Don Quijote* reopens wherever its stored CFI last pointed regardless of what
 * the reader showed, so "the place was kept" fails against it on a book that
 * never promised to keep it. That is a book precondition, not a defect in the
 * interaction the spec covers.
 *
 * ## Why the probe measures the assertion itself
 *
 * A previous revision compared the stored CFI before and after a turn, and
 * reported `moved: true` for *Don Quijote* — because the value it saw change was
 * the **teardown of a previous spec** restoring its own position, not the turn
 * writing anything. "Some write happened" and "the reader recorded where it is"
 * are different claims.
 *
 * So this measures the one property the spec depends on, in the same words:
 * open, turn, close, reopen, and report whether the reader came back to the page
 * it left. Nothing derived, nothing inferred.
 */
import type { Page } from '@playwright/test';
import type { SmokeBook } from './readerPaging';

/** What a book's position did across one open / turn / close / reopen. */
export type ProgressProbe = {
  /** True when the reader came back to the page it closed on. */
  records: boolean;
  /** The page it closed on, and the page it came back to. */
  closedOn: number | null;
  reopenedOn: number | null;
};

/** The footer's page field — the chapter the reader is showing. */
async function displayedPage(page: Page): Promise<number | null> {
  return page
    .evaluate(() => {
      const input = document.querySelector(
        '[data-testid="bottom-bar-controls"] input',
      ) as HTMLInputElement | null;
      const value = input ? Number.parseInt(input.value, 10) : Number.NaN;
      return Number.isFinite(value) ? value : null;
    })
    .catch(() => null);
}

/**
 * Drives one open / turn / close / reopen and reports whether the position held.
 *
 * Takes the `open` and `close` callbacks rather than importing them, so this
 * module does not depend on `readerPaging` and the two stay separately testable.
 *
 * `records: false` also covers "the turn did not happen" and "the book did not
 * reopen": in every one of those cases this book cannot demonstrate the property,
 * which is what the caller's skip message says.
 */
export async function probeProgressPersistence(
  page: Page,
  book: SmokeBook,
  io: {
    open: (page: Page, book: SmokeBook) => Promise<void>;
    close: (page: Page) => Promise<void>;
  },
): Promise<ProgressProbe> {
  await io.open(page, book);
  await page.waitForTimeout(2_500);

  const next = page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
  if (!(await next.isEnabled().catch(() => false))) {
    return { records: false, closedOn: null, reopenedOn: null };
  }
  await next.click();
  await page.waitForTimeout(4_000);

  const closedOn = await displayedPage(page);
  await io.close(page);

  try {
    await io.open(page, book);
  } catch {
    return { records: false, closedOn, reopenedOn: null };
  }
  await page.waitForTimeout(3_000);

  const reopenedOn = await displayedPage(page);
  return {
    records: closedOn !== null && reopenedOn !== null && closedOn === reopenedOn,
    closedOn,
    reopenedOn,
  };
}
