import type { AppRoute } from '$lib/shared/stores/HomeState';

/**
 * How a route wants its content box laid out inside `#main-content`.
 *
 * `full` lets the route fill the whole content box (chrome-style screens that
 * own their own columns). `contained` centres the route in a readable column.
 */
export type RouteLayout = 'full' | 'contained';

/**
 * Routes that opt out of the contained column. Everything else — including a
 * route a future contributor adds and forgets to list — resolves to
 * `contained`, so the safe default never depends on remembering this table.
 */
export const FULL_LAYOUT_ROUTES = ['discover', 'addons', 'settings', 'storage', 'sync'] as const;

/**
 * Explicit policy per route. The `contained` entries document the intended
 * layout for the current screens; the default still covers anything missing.
 */
const ROUTE_LAYOUTS: Readonly<Partial<Record<AppRoute, RouteLayout>>> = {
  home: 'contained',
  library: 'contained',
  stats: 'contained',
  highlights: 'contained',
  dictionary: 'contained',
  discover: 'full',
  addons: 'full',
  settings: 'full',
  storage: 'full',
  sync: 'full',
};

/** A route with no explicit policy is contained, never full-bleed by accident. */
export const DEFAULT_ROUTE_LAYOUT: RouteLayout = 'contained';

export function resolveRouteLayout(route: AppRoute): RouteLayout {
  return ROUTE_LAYOUTS[route] ?? DEFAULT_ROUTE_LAYOUT;
}

/**
 * Routes that render edge-to-edge inside the sidebar shell and therefore skip
 * the main-content padding. This is independent of `RouteLayout`: `discover`
 * and `addons` are full-bleed for width but still padded.
 */
const UNPADDED_ROUTES: ReadonlySet<AppRoute> = new Set<AppRoute>([
  'reader',
  'settings',
  'storage',
  'sync',
]);

/** True when `#main-content` should carry the padded content inset. */
export function routeUsesPadding(route: AppRoute): boolean {
  return !UNPADDED_ROUTES.has(route);
}

const LAYOUT_CLASSES: Record<RouteLayout, string> = {
  full: 'w-full h-full flex-1 flex flex-col min-h-0 max-w-none',
  // `w-full` is load-bearing: this item sits in `#main-content`'s column flex,
  // where an auto cross-axis margin (`mx-auto`) suppresses `align-self: stretch`
  // and makes the item shrink-to-fit its child. Without an explicit width the
  // container would resize whenever a screen swaps its content (e.g. the
  // library grid vs list), so `w-full` pins it to the available width and only
  // `max-w-7xl` limits it.
  contained: 'mx-auto w-full max-w-7xl',
};

/** Tailwind classes for a resolved policy, so the router maps policy, not routes. */
export function routeLayoutClass(layout: RouteLayout): string {
  return LAYOUT_CLASSES[layout];
}
