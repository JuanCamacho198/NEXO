/**
 * Counts the pages of a chapter document that is NOT on screen.
 *
 * Paginated mode only knows the column count of the document it shows. A
 * chapter's other documents (a title page, a map plate, the body) have to be
 * laid out to be counted, and laying them out in the reader's own frame would
 * move the reader. So each one is laid out here, in a frame of its own, at the
 * same page box and with the same stylesheet the reader would give it, and the
 * column count is read the way `derivePageCount` reads the live one.
 *
 * The frame:
 *  - is sandboxed WITHOUT scripts, so the bridge, selection and highlight
 *    scripts the srcdoc carries never run here;
 *  - sits in the viewport at opacity 0 behind the app, not off-screen, because
 *    Chromium throttles the lifecycle of frames outside the viewport;
 *  - waits for web fonts and images before reading, because both change how
 *    much text a column holds (a plate's height comes from its image);
 *  - is emptied after every measure, so nothing but the reader's own frame ever
 *    holds a chapter at rest.
 *
 * Measures run one at a time: they share one frame, and a chapter has few
 * documents.
 */
import { derivePageCount, type PageBox } from './readerPagination';

const LOAD_TIMEOUT_MS = 6000;
const ASSET_TIMEOUT_MS = 2500;

export type PageMeasurer = {
  /** Pages `srcdoc` lays out to at `box`, or 0 when it could not be measured. */
  measure(srcdoc: string, box: PageBox): Promise<number>;
  dispose(): void;
};

function withTimeout<T>(promise: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(fallback), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      () => {
        clearTimeout(timer);
        resolve(fallback);
      },
    );
  });
}

function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

function imagesSettled(doc: Document): Promise<void> {
  const pending = Array.from(doc.images).filter((img) => !img.complete);
  return Promise.all(
    pending.map(
      (img) =>
        new Promise<void>((resolve) => {
          img.addEventListener('load', () => resolve(), { once: true });
          img.addEventListener('error', () => resolve(), { once: true });
        }),
    ),
  ).then(() => undefined);
}

export function createPageMeasurer(): PageMeasurer {
  let frame: HTMLIFrameElement | null = null;
  let queue: Promise<unknown> = Promise.resolve();
  let pending = 0;

  function ensureFrame(): HTMLIFrameElement {
    if (frame && frame.isConnected) return frame;
    const el = document.createElement('iframe');
    el.setAttribute('sandbox', 'allow-same-origin');
    el.setAttribute('aria-hidden', 'true');
    el.setAttribute('tabindex', '-1');
    el.title = 'page-measure';
    el.dataset.nexoPageMeasure = 'true';
    el.style.cssText =
      'position:fixed;left:0;top:0;border:0;opacity:0;pointer-events:none;z-index:-1;contain:strict;';
    document.body.appendChild(el);
    frame = el;
    return el;
  }

  async function run(srcdoc: string, box: PageBox): Promise<number> {
    if (typeof document === 'undefined' || box.width <= 0 || box.height <= 0) return 0;
    const el = ensureFrame();
    el.style.width = `${Math.round(box.width)}px`;
    el.style.height = `${Math.round(box.height)}px`;
    // The reader's own frame is found by this id (the e2e harness looks for
    // it); a copy here must never be mistaken for it.
    const html = srcdoc.replace(/id=(["'])nexo-reader-overrides\1/, 'id="nexo-measure-overrides"');
    // Only OUR document counts as loaded: emptying the frame between measures
    // fires a load of its own that a measure queued behind must not take.
    const loaded = new Promise<boolean>((resolve) => {
      el.onload = () => {
        if (el.contentDocument?.getElementById('nexo-measure-overrides')) resolve(true);
      };
    });
    el.srcdoc = html;
    if (!(await withTimeout(loaded, LOAD_TIMEOUT_MS, false))) return 0;
    const doc = el.contentDocument;
    if (!doc?.documentElement) return 0;
    // Reading layout once starts the font and image loads the layout needs.
    void doc.documentElement.scrollWidth;
    await withTimeout(doc.fonts?.ready ?? Promise.resolve(), ASSET_TIMEOUT_MS, undefined);
    await withTimeout(imagesSettled(doc), ASSET_TIMEOUT_MS, undefined);
    await nextFrame();
    await nextFrame();
    return derivePageCount(doc.documentElement.scrollWidth, box.width);
  }

  function measure(srcdoc: string, box: PageBox): Promise<number> {
    pending += 1;
    const result = queue.then(() => run(srcdoc, box)).catch(() => 0);
    queue = result.then(() => {
      pending -= 1;
      if (pending === 0 && frame) {
        frame.onload = null;
        frame.srcdoc = '';
      }
    });
    return result;
  }

  function dispose(): void {
    frame?.remove();
    frame = null;
  }

  return { measure, dispose };
}
