/**
 * Startup timing for the background update check (T6 / #11).
 *
 * The check must still run once per launch — the updater path depends on it —
 * but it must never compete with first paint or first interaction. Two probes
 * decide when it becomes eligible: the window has finished loading, and the
 * main thread has gone idle. Only then does the minimum delay start counting,
 * so the delay is a floor rather than a schedule.
 */

/** Never start the check sooner than this after the app becomes interactive. */
export const UPDATE_CHECK_MIN_DELAY_MS = 1500;

type TimerHandle = ReturnType<typeof setTimeout>;

/**
 * Runs `task` once the window has loaded and the main thread is idle, then
 * waits out whatever is left of `minDelayMs`. Returns a cancel function that is
 * safe to call at any point, including after the task has started.
 */
export function scheduleAfterFirstPaint(
  task: () => void,
  options: { minDelayMs?: number } = {},
): () => void {
  const minDelayMs = options.minDelayMs ?? UPDATE_CHECK_MIN_DELAY_MS;
  if (typeof window === 'undefined') return () => {};

  let cancelled = false;
  let timer: TimerHandle | null = null;
  let whenInteractive = 0;

  const cancel = (): void => {
    cancelled = true;
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  const startDelay = (): void => {
    if (cancelled) return;
    if (whenInteractive === 0) whenInteractive = Date.now();
    const elapsed = Date.now() - whenInteractive;
    timer = setTimeout(
      () => {
        timer = null;
        if (!cancelled) task();
      },
      Math.max(0, minDelayMs - elapsed),
    );
  };

  const waitForIdle = (): void => {
    if (cancelled) return;
    if (typeof window.requestIdleCallback === 'function') {
      window.requestIdleCallback(() => startDelay(), { timeout: 2000 });
      return;
    }
    startDelay();
  };

  if (document.readyState === 'complete') {
    whenInteractive = Date.now();
    waitForIdle();
    return cancel;
  }

  // The window is still loading: wait for it, but never wait forever. A load
  // event that never arrives must not cost the check its only run (#17).
  let loadTimer: TimerHandle | null = null;
  const onLoaded = (): void => {
    window.removeEventListener('load', onLoaded);
    if (loadTimer !== null) {
      clearTimeout(loadTimer);
      loadTimer = null;
    }
    whenInteractive = Date.now();
    waitForIdle();
  };
  window.addEventListener('load', onLoaded, { once: true });
  loadTimer = setTimeout(onLoaded, 5000);

  return () => {
    window.removeEventListener('load', onLoaded);
    if (loadTimer !== null) {
      clearTimeout(loadTimer);
      loadTimer = null;
    }
    cancel();
  };
}
