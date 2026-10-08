import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';
import { installJsdomHarness } from './harness/jsdomHarness';

// Global mock for Tauri IPC
vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
}));

// Override structuredClone for jsdom/vitest (native Node.js version throws
// DataCloneError on mock objects with private slots, e.g. vitest MockProxy)
globalThis.structuredClone = (obj: unknown) => JSON.parse(JSON.stringify(obj));

// jsdom harness (see ./harness/jsdomHarness.ts): registry-backed
// ResizeObserver plus the manual flush hook, PointerEvent when absent, and
// scrollIntoView/scrollTo/scrollBy no-ops. Tests opt into resize delivery with
// flushResizeObservers(); nothing is delivered spontaneously.
installJsdomHarness();

// Polyfill window.matchMedia for jsdom (jsdom does not implement it).
// ContinueReadingSection's reduced-motion effect reads it on mount; the
// carousel unit tests override the global per test to simulate `reduce`.
if (typeof globalThis.matchMedia === 'undefined') {
  globalThis.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

// Polyfill Element.animate for jsdom (used by Svelte transitions in the app).
// jsdom does not run Web Animations, and Svelte sequences its JS transitions
// through the animation's `finish` event: without a real finish, an outro's
// node is never removed and any exit transition is untestable. Honour the
// requested duration and fire `onfinish` ourselves.
if (typeof globalThis.Element !== 'undefined' && !globalThis.Element.prototype.animate) {
  globalThis.Element.prototype.animate = function (
    _keyframes?: unknown,
    options?: number | { duration?: number },
  ) {
    const duration = typeof options === 'number' ? options : Number(options?.duration ?? 0);
    let finishHandler: (() => void) | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let playState = 'idle';

    const complete = (): void => {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      playState = 'finished';
      finishHandler?.();
    };

    const animation = {
      play() {
        /* noop */
      },
      pause() {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
      },
      finish: complete,
      cancel() {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
        playState = 'idle';
      },
      reverse() {
        /* noop */
      },
      addEventListener() {
        /* noop */
      },
      removeEventListener() {
        /* noop */
      },
      currentTime: null,
      playbackRate: 1,
      get playState() {
        return playState;
      },
      set playState(value: string) {
        playState = value;
      },
      finished: Promise.resolve(),
      ready: Promise.resolve(),
      get onfinish(): (() => void) | null {
        return finishHandler;
      },
      set onfinish(handler: (() => void) | null) {
        finishHandler = handler;
      },
    } as unknown as globalThis.Animation;

    timer = setTimeout(complete, Number.isFinite(duration) ? duration : 0);

    return animation;
  };
}

// Polyfill DOMMatrix for pdfjs-dist (not available in Node.js/jsdom/happy-dom)
if (typeof globalThis.DOMMatrix === 'undefined') {
  class DOMMatrixPolyfill {
    a = 1;
    b = 0;
    c = 0;
    d = 1;
    e = 0;
    f = 0;
    constructor(transform?: string) {
      if (transform) {
        const m = transform.match(/matrix\(([^)]+)\)/);
        if (m) {
          const v = m[1].split(',').map(Number);
          this.a = v[0] ?? 1;
          this.b = v[1] ?? 0;
          this.c = v[2] ?? 0;
          this.d = v[3] ?? 1;
          this.e = v[4] ?? 0;
          this.f = v[5] ?? 0;
        }
      }
    }
    translate(tx = 0, ty = 0) {
      this.e += tx;
      this.f += ty;
      return this;
    }
    scale(sx = 1, sy = 1) {
      this.a *= sx;
      this.d *= sy;
      return this;
    }
    multiply(other: DOMMatrixPolyfill) {
      const { a, b, c, d, e, f } = this;
      this.a = a * other.a + c * other.b;
      this.b = b * other.a + d * other.b;
      this.c = a * other.c + c * other.d;
      this.d = b * other.c + d * other.d;
      this.e = a * other.e + c * other.f + e;
      this.f = b * other.e + d * other.f + f;
      return this;
    }
    get isIdentity() {
      return (
        this.a === 1 && this.b === 0 && this.c === 0 && this.d === 1 && this.e === 0 && this.f === 0
      );
    }
    toFloat64() {
      return new Float64Array([this.a, this.b, this.c, this.d, this.e, this.f]);
    }
    toFloat32() {
      return new Float32Array([this.a, this.b, this.c, this.d, this.e, this.f]);
    }
    toJSON() {
      return { a: this.a, b: this.b, c: this.c, d: this.d, e: this.e, f: this.f };
    }
    toString() {
      return `matrix(${this.a}, ${this.b}, ${this.c}, ${this.d}, ${this.e}, ${this.f})`;
    }
  }
  globalThis.DOMMatrix = DOMMatrixPolyfill as unknown as typeof DOMMatrix;
}
