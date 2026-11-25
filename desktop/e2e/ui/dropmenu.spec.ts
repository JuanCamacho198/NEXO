import { expect, test, type Page } from '@playwright/test';
import { captureViewport, gotoRoute, openApp } from '../harness/appShell';
import { DEFAULT_SEEDED_TITLE } from '../harness/tauriStub';

// Pre-swap baselines for the `DropMenu` surface, captured before slice 5
// replaces its internals with bits-ui `DropdownMenu`.
//
// The trigger is addressed by the caller's `aria-label` (`shelf.bookOptions`),
// which is part of `ShelfBookActions`' contract and therefore survives the
// swap; the open popup is asserted through one of its caller-owned items
// (`children` render verbatim in both implementations, and slice 5 tags them
// `role="menuitem"` when it delivers the menu semantics).

const TITLE = DEFAULT_SEEDED_TITLE;

function bookMenu(page: Page) {
  return page.getByRole('button', { name: `Options for ${TITLE}`, exact: true }).first();
}

async function openBookMenu(page: Page): Promise<void> {
  await bookMenu(page).click();
  await expect(
    page.getByRole('menuitem', { name: 'Remove from library', exact: true }),
  ).toBeVisible();
}

test('library grid: the book action menu renders over the grid', async ({ page }) => {
  await openApp(page, { bookCount: 2 });
  await gotoRoute(page, 'library');

  await openBookMenu(page);
  await expect(page.getByRole('menuitem', { name: 'View details', exact: true })).toBeVisible();

  await captureViewport(page, 'library-grid-book-menu.png');
});

test('library list: the in-list book action menu renders over the row', async ({ page }) => {
  await openApp(page, { bookCount: 2 });
  await gotoRoute(page, 'library');

  await page.getByRole('button', { name: 'List view', exact: true }).click();
  // `ul.grid` is `ShelfGrid`'s root: waiting for it to disappear proves the
  // list variant is mounted before the trigger is resolved, so the capture
  // cannot race the view switch.
  await expect(page.locator('#main-content ul.grid')).toHaveCount(0);

  await openBookMenu(page);
  await captureViewport(page, 'library-list-book-menu.png');
});

test('library grid: the book action menu answers the keyboard and returns focus', async ({
  page,
}) => {
  await openApp(page, { bookCount: 2 });
  await gotoRoute(page, 'library');

  const trigger = bookMenu(page);
  await trigger.click();

  // The items are the caller's six plain buttons (open, favourite, complete,
  // edit metadata, details, remove), now tagged as menuitems; the separator
  // before "remove" is not one.
  const items = page.getByRole('menuitem');
  await expect(items).toHaveCount(6);
  await expect(items.first()).toHaveText('Open book');

  // Focus enters the menu on open, and the arrows walk the items in DOM order.
  await expect(items.first()).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(items.nth(1)).toBeFocused();
  await page.keyboard.press('End');
  await expect(items.nth(5)).toBeFocused();

  // Escape closes without activating, and focus returns to the caller's button.
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toBeHidden();
  await expect(trigger).toBeFocused();
});
