/**
 * Debug-level diagnostics for the Discover rails, mirroring Android
 * `CatalogRailLog`.
 *
 * A rail fails closed to `Hidden`, and a hidden rail renders nothing — so
 * without this a genuinely empty page and a swallowed exception look identical
 * from the UI. Every line goes through `console.debug`, which browsers and the
 * Tauri WebView keep out of the default console filter.
 *
 * Search never emits here: this is rail-only, on purpose.
 */

/** Stable rail name plus the upstream it was served by. */
export interface RailLogContext {
  /** Rail kind, e.g. `topic`, `popular`, `standard-ebooks`. */
  readonly rail: string;
  /** Upstream selector or source id the rail asked. */
  readonly source: string;
  /** Selector term sent upstream, when the rail has one. */
  readonly term?: string | null;
}

function prefix(context: RailLogContext): string {
  return `[NexoRail] rail=${context.rail} source=${context.source} term=${context.term ?? '-'}`;
}

/** A rail that will render nothing, with the reason it resolved that way. */
export function logRailHidden(context: RailLogContext, reason: string): void {
  console.debug(`${prefix(context)} hidden reason=${reason}`);
}

/** A rail that will render a section. */
export function logRailLoaded(context: RailLogContext, count: number): void {
  console.debug(`${prefix(context)} loaded books=${count}`);
}
