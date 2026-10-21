/**
 * Reading and removing a book's highlights over the real IPC bridge.
 *
 * ## Why this is its own module
 *
 * The database is the authority on whether a colour was applied — a screenshot
 * is evidence of rendering, not of data, and one automated run already reported
 * a colour as "not applied" while the store held it (contract §2.4). So these
 * helpers read the store, never the DOM.
 *
 * They also carry the accounting rule that keeps a spec from damaging the
 * library it runs against: **identify writes by id, and delete every id the run
 * added.** A count cannot do that — measured in this library, one book reached
 * 115 highlights because a spec deleted only the newest row per run, and another
 * reached 18 from probe traffic that never cleaned up.
 */
import type { Page } from '@playwright/test';

async function invoke<T>(page: Page, cmd: string, args?: unknown): Promise<T> {
  return page.evaluate(
    async ([command, commandArgs]) => {
      const internals = (
        window as unknown as {
          __TAURI_INTERNALS__?: { invoke: (c: string, a?: unknown) => Promise<unknown> };
        }
      ).__TAURI_INTERNALS__;
      if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');
      return internals.invoke(command as string, commandArgs);
    },
    [cmd, args] as const,
  ) as Promise<T>;
}

/**
 * The ids of every highlight the backend reports for `bookId`.
 *
 * Set membership is what proves *a particular click* wrote something, which is
 * the property case 5 of the selection contract needs.
 */
export async function highlightIds(page: Page, bookId: string): Promise<Set<string>> {
  const rows = await invoke<Array<{ id: string }> | null>(page, 'listHighlights', { bookId });
  return new Set((rows ?? []).map((row) => row.id));
}

/** How many highlights the backend reports for `bookId`. */
export async function countHighlights(page: Page, bookId: string): Promise<number> {
  return (await highlightIds(page, bookId)).size;
}

/**
 * The highlight the backend holds for `highlightId`, or null when it is gone.
 *
 * The database is the authority on whether a colour was applied; a screenshot
 * is not.
 */
export async function readHighlight(
  page: Page,
  bookId: string,
  highlightId: string,
): Promise<{ id: string; color: string; text?: string } | null> {
  const rows = await invoke<Array<{ id: string; color: string; text?: string }> | null>(
    page,
    'listHighlights',
    { bookId },
  );
  return (rows ?? []).find((row) => row.id === highlightId) ?? null;
}

/** Deletes a highlight this spec created, so it does not leak into the library. */
export async function deleteHighlight(page: Page, highlightId: string): Promise<void> {
  await invoke(page, 'deleteHighlight', { id: highlightId });
}
