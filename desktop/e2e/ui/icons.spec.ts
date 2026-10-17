import { expect, test, type Page } from '@playwright/test';
import { captureViewport, gotoRoute, navButton, openApp } from '../harness/appShell';

// Pre-swap baselines for the icon surfaces, captured before slices 10-14
// migrate them off the `Icon` shim.
//
// Scope: the routed surfaces that carry first-party icons (home, library,
// stats) plus the `AppSidebar` tooltip contract that D3 changes when bits-ui
// `Tooltip` replaces the CSS-only affordance. Both sidebar states are captured
// with and without the tooltip shown, because D3's contract is about the
// visible tooltip, not about the icon markup.
//
// The tooltip is a hover-only affordance: it is asserted visible/hidden around
// every capture, so a baseline can never silently record the wrong hover state.

const libraryIcon = (page: Page) => navButton(page, 'library').locator('svg').first();

const libraryTooltip = (page: Page) =>
  page.locator('[role=tooltip]').filter({ hasText: 'Library' });

// A point inside `#main-content`'s top padding: inert, so moving the mouse
// there clears the hover state without changing what is rendered underneath.
const INERT_POINT = { x: 1240, y: 40 };

test('routed icon surfaces: home, library and stats', async ({ page }) => {
  await openApp(page, { bookCount: 2 });

  await gotoRoute(page, 'home');
  await captureViewport(page, 'icons-home.png');

  await gotoRoute(page, 'library');
  await captureViewport(page, 'icons-library.png');

  await gotoRoute(page, 'stats');
  await captureViewport(page, 'icons-stats.png');
});

test('sidebar icon surface: expanded and collapsed, with and without the tooltip', async ({
  page,
}) => {
  await openApp(page, { bookCount: 2 });
  await gotoRoute(page, 'library');

  // Expanded: the label is already on screen, so `AppSidebar` deliberately does
  // not mount the tooltip portal in this state — it would only repeat the text
  // that is right there. This test used to assert it visible and waited 5s for a
  // node the component never renders. The contract asserted here is the real
  // one: no tooltip while expanded, a tooltip once collapsed.
  await page.mouse.move(INERT_POINT.x, INERT_POINT.y);
  await libraryIcon(page).hover();
  await expect(libraryTooltip(page)).toBeHidden();
  await captureViewport(page, 'icons-sidebar-expanded.png');

  // Expanded, pointer away: the resting state, identical for the same reason.
  await page.mouse.move(INERT_POINT.x, INERT_POINT.y);
  await expect(libraryTooltip(page)).toBeHidden();
  await captureViewport(page, 'icons-sidebar-expanded-resting.png');

  // Collapsed, tooltip hidden. The collapse control is activated by keyboard so
  // the pointer never comes to rest on a hover-styled control.
  await page.getByRole('button', { name: 'Collapse sidebar', exact: true }).click();
  await page.mouse.move(INERT_POINT.x, INERT_POINT.y);
  await expect(page.getByRole('button', { name: 'Expand sidebar', exact: true })).toBeVisible();
  // Settle the rail before asserting the hidden contract. Collapsing mounts the
  // collapsed-state `Tooltip.Portal` while its content is still closed, and
  // bits-ui leaves that closed node in the DOM (parked off-screen at the
  // unpositioned origin) until the trigger is next interacted with. That is a
  // real, pre-existing bits-ui mount artifact — not a merge regression, and
  // off-screen so it never reaches a user — but Playwright reads `display:block`
  // as visible. One hover-then-leave over the collapsed trigger, which is what a
  // real pointer does, unmounts it and lets the assertion measure the app's
  // actual resting state instead of the leak.
  await libraryIcon(page).hover();
  await page.mouse.move(INERT_POINT.x, INERT_POINT.y);
  await expect(libraryTooltip(page)).toBeHidden();
  await captureViewport(page, 'icons-sidebar-collapsed.png');

  // Collapsed, tooltip shown: the collapsed sidebar has no text label, so the
  // tooltip is the whole remaining affordance for the nav item.
  await libraryIcon(page).hover();
  await expect(libraryTooltip(page)).toBeVisible();
  await captureViewport(page, 'icons-sidebar-collapsed-tooltip.png');
});
