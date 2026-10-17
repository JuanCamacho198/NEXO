import { expect, type Locator, type Page } from '@playwright/test';
import { DEFAULT_SEEDED_TITLE, installTauriInvokeStub, type TauriStubSeed } from './tauriStub';

/**
 * Frozen system time for the visual gate.
 *
 * The library cards and `ShelfDetailModal` render relative timestamps
 * ("6d ago", "2mo ago") computed from `Date.now()`, so a live clock makes every
 * baseline expire on its own within a day — a gate that fails tomorrow is not a
 * gate. `setFixedTime` fakes `Date` only and keeps every timer running, so app
 * boot and its timeouts are unaffected.
 */
export const GATE_NOW = new Date('2026-03-15T10:30:00.000Z');

/**
 * `SyncAuthBanner` is raised asynchronously by the sync/Drive AUTH_REQUIRED
 * path, is `position: fixed` across the top of the window, and re-raises itself
 * after a dismissal — so it both intercepts clicks over the sidebar header and
 * would make captures race its mount.
 *
 * It is not a surface this gate covers: it is hand-rolled feedback chrome, out
 * of scope by D12 and absent from the swapped set. Hiding it by CSS (rather
 * than dismissing it through the app) is what makes that exclusion
 * deterministic instead of timing-dependent.
 */
const HIDE_SYNC_BANNER_CSS = '[data-testid="sync-auth-banner"]{display:none !important}';

export type GateRoute = 'home' | 'library' | 'stats' | 'settings';

/** Index inside `aside nav`, i.e. the order of `getNavItems`. */
const ROUTE_INDEX: Record<GateRoute, number> = {
  home: 0,
  library: 1,
  stats: 4,
  settings: 6,
};

const ROUTE_ANCHORS: Record<GateRoute, (page: Page) => Locator> = {
  // Anchored to `home.recentBooksTitle`, which `HomeMainContent` renders as an
  // `h2`. It previously looked for "My Shelf", a catalog key (`home.myShelf`)
  // that no component renders — so the anchor waited 15s for text that was
  // never on screen. Anchoring by role+name keeps this tied to rendered
  // structure rather than to a string that can fall out of use unnoticed.
  home: (page) =>
    page.locator('#main-content').getByRole('heading', { name: 'Recent books', exact: true }),
  library: (page) => page.locator('#main-content').getByRole('heading', { name: 'Library' }),
  stats: (page) => page.locator('#main-content').getByRole('heading', { name: 'Reading Stats' }),
  settings: (page) => page.getByRole('tab', { name: 'Account' }),
};

/** The sidebar nav button for a route, resolved by source order, not by label. */
export function navButton(page: Page, route: GateRoute): Locator {
  return page.locator('aside nav > button').nth(ROUTE_INDEX[route]);
}

/**
 * Boots the app into the authenticated shell: frozen clock, stubbed Tauri IPC,
 * sync-auth banner hidden, sidebar rendered.
 */
export async function openApp(page: Page, seed: TauriStubSeed = {}): Promise<void> {
  await page.clock.setFixedTime(GATE_NOW);
  await installTauriInvokeStub(page, seed);
  await page.goto('/');
  await expect(page.locator('aside nav > button').first()).toBeVisible({ timeout: 30_000 });
  await page.addStyleTag({ content: HIDE_SYNC_BANNER_CSS });
}

export async function gotoRoute(page: Page, route: GateRoute): Promise<void> {
  await navButton(page, route).click();
  await expect(ROUTE_ANCHORS[route](page)).toBeVisible({ timeout: 15_000 });
}

/**
 * Captures the whole viewport rather than a component subtree.
 *
 * This is a deliberate gate decision: every surface this gate covers is an
 * overlay, and the target implementations portal their content into
 * `document.body` (bits-ui Dialog/Select/DropdownMenu). An element-scoped
 * screenshot would silently stop covering the swapped surface the moment the
 * portal lands; the viewport is the only scope that survives the swap.
 */
export async function captureViewport(page: Page, name: string): Promise<void> {
  await expect(page).toHaveScreenshot(name);
}

const DIALOG = '[role=dialog]';

/**
 * The **home** shelf card's action trigger.
 *
 * On the home route this is `ShelfActionMenu` (hand-rolled, out of baseline
 * scope by D12) and on the library route it is `DropMenu` (in scope), so the
 * two routes must never share this helper. It exists here only because two of
 * the nine live `Modal` consumers are reachable exclusively through it.
 */
export function homeShelfCardMenuTrigger(page: Page): Locator {
  return page
    .getByRole('button', { name: `Options for ${DEFAULT_SEEDED_TITLE}`, exact: true })
    .first();
}

export async function openShelfDetail(page: Page): Promise<void> {
  await homeShelfCardMenuTrigger(page).click();
  await page.getByRole('menuitem', { name: 'View details', exact: true }).first().click();
  await expect(page.locator(DIALOG)).toHaveCount(1);
}

export async function openEditMetadataModal(page: Page): Promise<void> {
  await homeShelfCardMenuTrigger(page).click();
  await page.getByRole('menuitem', { name: 'Edit Metadata', exact: true }).first().click();
  await expect(page.locator(DIALOG)).toHaveCount(1);
}
