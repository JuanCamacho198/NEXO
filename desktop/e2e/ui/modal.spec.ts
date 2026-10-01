import { expect, test } from '@playwright/test';
import {
  captureViewport,
  gotoRoute,
  openApp,
  openEditMetadataModal,
  openShelfDetail,
} from '../harness/appShell';
import { DEFAULT_SEEDED_TITLE } from '../harness/tauriStub';

// Pre-swap baselines for the live `Modal` consumers, captured before
// slice 7 replaces its internals with bits-ui `Dialog`.
//
// Consumer coverage list (the tenth `<Modal` site was `ConfirmDialog`, deleted
// in slice 2; `SettingsResetModal` was deleted in the settings-cleanup unit
// along with the Appearance / Reader settings tabs, so the set is eight):
//
//   1. ShelfDetailModal           covered here (home)          - detail + edit views
//   2. BulkImportModal            covered here (home)
//   3. EditMetadataModal          covered here (home)
//   4. RemoveBookModal            covered here (library)
//   5. ReadingStatisticsView      covered here (stats, chart modal)
//   6. DiscoverDetail             UNREACHABLE - see below
//   7. AddonInstallConfirmDialog  UNREACHABLE - see below
//   8. DriveConnectPrompt         UNREACHABLE - see below
//
// The three unreachable consumers cannot be opened from the stubbed app, and a
// synthetic open would require a production change (forbidden: zero call-site
// churn). Each names its equivalent jsdom coverage instead of being silently
// omitted:
//
//   - DiscoverDetail: the `discover` route is auth-gated; the remote catalog
//     resolves to `null` under the stub, so only the signed-out surface
//     renders and no detail can be opened. Equivalent: `src/test/unit/
//     discover-detail.test.ts` (its `role="dialog"` contract + Escape).
//   - AddonInstallConfirmDialog: opened only by the deep-link install handler
//     (`setInstallDeepLinkHandler`), and no deep link is delivered under the
//     stub. Equivalent: `src/test/unit/ui/AddonInstallConfirmDialog.test.ts`.
//   - DriveConnectPrompt: requires `downloadableCatalog.drivePromptPending`,
//     raised only by a cloud-download attempt that needs Drive authorization;
//     the stub returns no catalog rows, so the prompt never opens. Equivalent:
//     `src/test/unit/services/driveConnectPromptGate.test.ts`.
//
// Nested-modal stacking is likewise unreachable: no consumer opens a second
// dialog on top of itself, and the app mounts one dialog owner per surface, so
// there is no modal-over-modal pair to capture. The reachable stacking case -
// an overlay opened *inside* a dialog and painting above it - is covered by
// `dropdown.spec.ts` (the two Dropdowns in the open ShelfDetailModal) and by
// the ShelfDetailModal edit view below.

const DIALOG = '[role=dialog]';
const TITLE = DEFAULT_SEEDED_TITLE;

test('home: bulk import, edit metadata and shelf detail dialogs', async ({ page }) => {
  await openApp(page, { bookCount: 2 });
  await gotoRoute(page, 'home');

  // 1. BulkImportModal.
  await page.getByRole('button', { name: 'Import', exact: true }).click();
  await expect(page.locator(DIALOG)).toHaveCount(1);
  await expect(page.locator(DIALOG)).toContainText('Bulk import');
  await captureViewport(page, 'modal-bulk-import.png');

  // Escape closes exactly the open dialog.
  await page.keyboard.press('Escape');
  await expect(page.locator(DIALOG)).toBeHidden();

  // 2. EditMetadataModal.
  await openEditMetadataModal(page);
  await expect(page.locator(DIALOG)).toContainText('Edit Metadata');
  await captureViewport(page, 'modal-edit-metadata.png');

  await page.keyboard.press('Escape');
  await expect(page.locator(DIALOG)).toBeHidden();

  // 3. ShelfDetailModal, detail view.
  await openShelfDetail(page);
  await expect(page.locator(DIALOG)).toContainText(TITLE);
  // The seeded book is created at 2026-01-01 and the gate freezes the clock at
  // 2026-03-15, so the relative age must read "2mo ago". Without the frozen
  // clock this is "8mo ago" (measured) and would keep growing, expiring every
  // baseline that contains this text — so this assertion is what makes the
  // time-stability of the gate falsifiable rather than assumed.
  await expect(page.locator(DIALOG)).toContainText('2mo ago');
  await captureViewport(page, 'modal-shelf-detail.png');

  // 4. ShelfDetailModal, edit view (the second layer of the same dialog).
  await page.locator(DIALOG).getByRole('button', { name: 'Edit metadata' }).click();
  await expect(page.locator(DIALOG)).toContainText('Cancel');
  await captureViewport(page, 'modal-shelf-detail-edit.png');

  await page.keyboard.press('Escape');
  await expect(page.locator(DIALOG)).toBeHidden();

  // Backdrop close: the top-left corner is the dialog's own backdrop, so the
  // click must reach it and close exactly one dialog.
  await openShelfDetail(page);
  await page.mouse.click(4, 4);
  await expect(page.locator(DIALOG)).toBeHidden();
});

test('library: the remove-book dialog renders over the shelf', async ({ page }) => {
  await openApp(page, { bookCount: 2 });
  await gotoRoute(page, 'library');

  await page
    .getByRole('button', { name: `Options for ${TITLE}`, exact: true })
    .first()
    .click();
  await page.getByRole('menuitem', { name: 'Remove from library', exact: true }).click();

  await expect(page.locator(DIALOG)).toHaveCount(1);
  await expect(page.locator(DIALOG)).toContainText(TITLE);
  await captureViewport(page, 'modal-remove-book.png');

  await page.keyboard.press('Escape');
  await expect(page.locator(DIALOG)).toBeHidden();
});

test('stats: the chart dialog renders over the reading stats page', async ({ page }) => {
  await openApp(page, { bookCount: 2 });
  await gotoRoute(page, 'stats');

  await page.getByRole('button', { name: 'Pantalla completa', exact: true }).click();

  await expect(page.locator(DIALOG)).toHaveCount(1);
  await expect(page.locator(DIALOG)).toContainText('Minutes read');
  await captureViewport(page, 'modal-stats-chart.png');

  await page.keyboard.press('Escape');
  await expect(page.locator(DIALOG)).toBeHidden();
});
