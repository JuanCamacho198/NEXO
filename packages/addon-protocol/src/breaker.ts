/**
 * Per-addon circuit breaker. Pure bookkeeping, no I/O.
 *
 * A dead addon must not light up the UI on every search, so after `threshold`
 * consecutive failures the addon is skipped outright for `openMs`, then
 * half-opens for exactly one probe. A failed probe pays another full cooldown;
 * a successful one resets the addon.
 */

interface BreakerState {
  failures: number;
  /** Set while the circuit is open; the half-open window starts here. */
  openedAt?: number;
  /** Set while the single half-open probe is in flight. */
  probed?: boolean;
}

const DEFAULT_THRESHOLD = 3;
const DEFAULT_OPEN_MS = 300_000;

export class CircuitBreaker {
  private readonly threshold: number;
  private readonly openMs: number;
  private readonly now: () => number;
  private readonly states = new Map<string, BreakerState>();

  constructor(opts: { threshold?: number; openMs?: number; now?: () => number } = {}) {
    this.threshold = Math.max(1, Math.trunc(opts.threshold ?? DEFAULT_THRESHOLD));
    this.openMs = Math.max(0, opts.openMs ?? DEFAULT_OPEN_MS);
    this.now = opts.now ?? Date.now;
  }

  canRequest(addonId: string): boolean {
    const state = this.states.get(addonId);
    if (state === undefined || state.openedAt === undefined) return true;
    if (this.now() - state.openedAt < this.openMs) return false;
    if (state.probed === true) return false;
    // Half-open: this caller owns the only probe.
    state.probed = true;
    return true;
  }

  recordSuccess(addonId: string): void {
    this.states.delete(addonId);
  }

  recordFailure(addonId: string): void {
    const state = this.states.get(addonId) ?? { failures: 0 };
    state.probed = false;

    if (state.openedAt !== undefined) {
      // The probe failed: open again for a full cooldown.
      state.openedAt = this.now();
      state.failures = 0;
    } else {
      state.failures += 1;
      if (state.failures >= this.threshold) {
        state.openedAt = this.now();
        state.failures = 0;
      }
    }
    this.states.set(addonId, state);
  }
}
