import * as pdfjsLib from './build/pdf.min.mjs';
import { EventBus, PDFLinkService, PDFViewer } from './web/pdf_viewer.mjs';

const bridge = window.nexoPdf;
const post = (obj) => {
  try {
    bridge.postMessage(JSON.stringify(obj));
  } catch (e) {}
};

window.addEventListener('error', (e) => post({ type: 'error', message: String(e.message) }));
window.addEventListener('unhandledrejection', (e) =>
  post({ type: 'error', message: String(e.reason && e.reason.message ? e.reason.message : e.reason) }),
);

const base = new URL('.', location.href).href;
pdfjsLib.GlobalWorkerOptions.workerSrc = base + 'build/pdf.worker.min.mjs';

const container = document.getElementById('container');
const eventBus = new EventBus();
const linkService = new PDFLinkService({ eventBus });
const viewer = new PDFViewer({
  container,
  viewer: document.getElementById('viewer'),
  eventBus,
  linkService,
  textLayerMode: 1,
  removePageBorders: true,
});
linkService.setViewer(viewer);

const ROUND_FN = /round\(down,\s*(.+?),\s*var\(--scale-round-[xy]\)\)/g;
const unwrapRound = (el) => {
  const s = el.getAttribute('style');
  if (s && s.includes('round(')) el.setAttribute('style', s.replace(ROUND_FN, 'calc($1)'));
};
new MutationObserver((records) => {
  for (const r of records) {
    if (r.type === 'attributes') unwrapRound(r.target);
    else r.addedNodes.forEach((n) => n.nodeType === 1 && (unwrapRound(n), n.querySelectorAll('[style*="round("]').forEach(unwrapRound)));
  }
}).observe(document.getElementById('viewer'), {
  subtree: true,
  childList: true,
  attributes: true,
  attributeFilter: ['style'],
});

eventBus.on('pagesinit', () => {
  viewer.currentScaleValue = 'page-width';
  post({ type: 'loaded', numPages: viewer.pagesCount });
  postPage();
});

let pageTimer = null;
const postPage = () =>
  post({ type: 'page', page: viewer.currentPageNumber, total: viewer.pagesCount });
eventBus.on('pagechanging', () => {
  clearTimeout(pageTimer);
  pageTimer = setTimeout(postPage, 200);
});

const bookUrl = new URL('/book/current.pdf', location.origin).href;
pdfjsLib
  .getDocument({
    url: bookUrl,
    isEvalSupported: false,
    enableScripting: false,
    cMapUrl: base + 'cmaps/',
    cMapPacked: true,
    standardFontDataUrl: base + 'standard_fonts/',
    wasmUrl: base + 'wasm/',
  })
  .promise.then((doc) => {
    viewer.setDocument(doc);
    linkService.setDocument(doc, null);
  })
  .catch((e) => post({ type: 'error', message: String(e && e.message ? e.message : e) }));

let timer = null;
document.addEventListener('selectionchange', () => {
  clearTimeout(timer);
  timer = setTimeout(reportSelection, 150);
});

function reportSelection() {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
    post({ type: 'selection', empty: true });
    return;
  }
  const range = sel.getRangeAt(0);
  const text = sel.toString();
  if (!text.trim()) {
    post({ type: 'selection', empty: true });
    return;
  }
  const startNode = range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement;
  const pageEl = startNode && startNode.closest('.page');
  if (!pageEl) {
    post({ type: 'selection', empty: true });
    return;
  }
  const pageNumber = parseInt(pageEl.dataset.pageNumber, 10);
  const scale = viewer.currentScale || 1;
  const pageRect = pageEl.getBoundingClientRect();
  const rects = Array.from(range.getClientRects())
    .filter((r) => r.width > 0 && r.height > 0)
    .map((r) => ({
      x: (r.left - pageRect.left) / scale,
      y: (r.top - pageRect.top) / scale,
      width: r.width / scale,
      height: r.height / scale,
    }));
  const b = range.getBoundingClientRect();
  post({
    type: 'selection',
    empty: false,
    pageNumber,
    text,
    rects,
    bounds: { left: b.left, top: b.top, right: b.right, bottom: b.bottom },
    scale,
  });
}
