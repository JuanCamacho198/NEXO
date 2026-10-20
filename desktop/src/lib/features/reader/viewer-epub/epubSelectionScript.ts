/**
 * Plain-JS string of the EPUB chapter selection bridge, inlined into the
 * chapter iframe's `srcdoc` by `buildChapterSrcdoc`.
 *
 * It is the frame half of the reader's primary interaction (select text →
 * `SelectionToolbar` → colour). The parent never sees a click or a selection
 * that happens inside this document — the frame boundary blocks both — so every
 * dismissal the reader expects has to be emitted from here.
 *
 * ## What it posts
 *
 * - `epub-selection` with text — the reader made a selection; the parent raises
 *   the toolbar. Sent twice per gesture: immediately on `mouseup`, and again
 *   after a 100 ms debounce, plus an **immediate** form tagged `fast: true`.
 *   The immediate form is what makes the toolbar appear without the debounce
 *   delay; the debounced form is the one that carries the measured rects on a
 *   real drag. The parent keeps only one menu for the pair, so the duplicate
 *   cannot flicker between two payloads.
 * - `epub-selection` with empty text and a `reason` — the selection ended and
 *   the toolbar must go. Reasons seen in the log: `inside-click`, `scroll`,
 *   `mouseup-collapsed`, `selection-collapsed`, `iframe-blur`, `escape`,
 *   `pagehide`.
 * - `epub-highlight-click` — the caret landed on an existing highlight; the
 *   parent raises the highlight menu instead.
 *
 * ## The drag state, and why it exists
 *
 * A drag *starts* by collapsing the selection, and Chromium fires a native
 * `selectionchange` for that collapse. Emitting a clear for it would close the
 * toolbar the drag is about to open — measured as "selecting sometimes does not
 * show the toolbar". So `mousedown` opens a drag and the collapse-driven clears
 * (`selectionchange`, `scroll`, `blur`) are suppressed until `mouseup` settles
 * it, with a bounded idle timeout in case no `mouseup` ever arrives.
 *
 * The state is deliberately internals-free: a spec that sets a Range and only
 * dispatches `mouseup` (no `mousedown`) is never treated as dragging, so the
 * synthetic gesture and the real gesture both work.
 */
export function buildChapterSelectionScript(opts: {
  /** Normalized href of the chapter this document holds (the CFI authority). */
  chapterHref: string;
  /** Spine index of the chapter; every message is gated on it by the parent. */
  chapterIndex: number;
  chapterTitle: string | null;
}): string {
  return `
      (function() {
        var timer = null;
        var mouseupDebounceTimer = null;
        // U2: the reader's DragState. While a drag is in flight the frame must
        // not emit a clear — a drag STARTS by collapsing the selection, and the
        // native \`selectionchange\` that collapse fires would otherwise post a
        // delayed clear that closes the menu the drag is about to open (measured
        // as "selecting sometimes does not show the toolbar").
        var dragState = 'idle';
        var DRAG_IDLE_MS = 1500;
        var dragIdleTimer = null;
        var CHAPTER_HREF = ${JSON.stringify(opts.chapterHref)};
        var CHAPTER_INDEX = ${opts.chapterIndex};
        var CHAPTER_TITLE = ${JSON.stringify(opts.chapterTitle ?? null)};

        function postClear(reason) {
          try {
            window.parent.postMessage({
              type: 'epub-selection',
              text: '',
              pageNumber: CHAPTER_INDEX,
              reason: reason
            }, '*');
          } catch (e) {}
        }

        function beginDrag() {
          dragState = 'dragging';
          if (dragIdleTimer) clearTimeout(dragIdleTimer);
          dragIdleTimer = null;
          if (timer) {
            clearTimeout(timer);
            timer = null;
          }
        }

        function endDrag() {
          dragState = 'settled';
          if (dragIdleTimer) clearTimeout(dragIdleTimer);
          dragIdleTimer = setTimeout(function() {
            dragIdleTimer = null;
            dragState = 'idle';
          }, DRAG_IDLE_MS);
        }

        document.addEventListener('mousedown', function() {
          beginDrag();
        }, true);
        document.addEventListener('mouseleave', function() {
          if (dragState === 'dragging') endDrag();
        }, true);

        document.addEventListener('click', function(ev) {
          var hit = null;
          try {
            if (window.__epubHighlightOverlay &&
                typeof window.__epubHighlightOverlay.hitTest === 'function') {
              hit = window.__epubHighlightOverlay.hitTest(ev.clientX, ev.clientY);
            }
          } catch (e) {
            hit = null;
          }
          if (hit) {
            ev.stopPropagation();
            if (ev.preventDefault) ev.preventDefault();
            if (mouseupDebounceTimer) {
              clearTimeout(mouseupDebounceTimer);
              mouseupDebounceTimer = null;
            }
            if (timer) {
              clearTimeout(timer);
              timer = null;
            }
            var selHit = window.getSelection();
            if (selHit) selHit.removeAllRanges();
            window.parent.postMessage({
              type: 'epub-highlight-click',
              id: hit.id,
              x: ev.clientX,
              y: ev.clientY,
              color: hit.color,
              text: hit.text,
              pageNumber: CHAPTER_INDEX
            }, '*');
            return;
          }
          // U2: a click on the chapter that is NOT part of a drag collapses the
          // selection, and the toolbar must follow it away. The parent never
          // sees this click — it lands in this document — so the clear has to be
          // emitted from here. Deferred one task so the click's own effect on
          // the selection (and the hit test above) has landed first.
          setTimeout(function() {
            var sel = window.getSelection();
            if (!sel || sel.isCollapsed || !sel.toString().trim()) postClear('inside-click');
          }, 0);
        }, true);

        window.addEventListener('blur', function() {
          if (dragState === 'dragging') return;
          postClear('iframe-blur');
        });

        document.addEventListener('keydown', function(ev) {
          if (ev.key !== 'Escape') return;
          postClear('escape');
        }, true);

        // ─── Selection release ───────────────────────────────────────────
        // One gesture, two emissions, and the parent keeps one menu for both:
        //
        //   - the immediate form (tagged 'fast') raises the toolbar without the
        //     100 ms debounce delay, which is what makes a selection feel
        //     instant;
        //   - the debounced form carries the measured rects. On a real drag the
        //     immediate one has no rects yet, and on the synthetic
        //     mouseup-only gesture a spec drives the debounced one is the
        //     same payload.
        //
        // They are mutually exclusive per release, not both required: whichever
        // arrives first opens the menu and the other is deduplicated by the
        // parent, so a reader never sees the toolbar flicker between two
        // payloads.
        function buildSelectionPayload() {
          var sel = window.getSelection();
          if (!sel || sel.isCollapsed || !sel.toString().trim()) return null;
          // A run that touches an existing highlight belongs to the highlight
          // menu: the click handler above raises it, and this selection must
          // not raise the toolbar under it.
          var range = sel.getRangeAt(0);
          var overlapsHl = false;
          try {
            if (window.__epubHighlightOverlay &&
                typeof window.__epubHighlightOverlay.rangeOverlapsHighlight === 'function') {
              overlapsHl = window.__epubHighlightOverlay.rangeOverlapsHighlight(range);
            }
          } catch (eOverlap) {
            overlapsHl = false;
          }
          if (overlapsHl) return null;
          var frameRect = (window.frameElement && window.frameElement.getBoundingClientRect)
            ? window.frameElement.getBoundingClientRect()
            : { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
          var rect = range.getBoundingClientRect();
          var bounds = {
            left: rect.left,
            top: rect.top,
            right: rect.right,
            bottom: rect.bottom
          };
          var containerRect = {
            left: frameRect.left,
            top: frameRect.top,
            width: frameRect.width,
            height: frameRect.height
          };
          var clientRects = range.getClientRects();
          var rects = [];
          for (var i = 0; i < clientRects.length; i++) {
            var r = clientRects[i];
            rects.push({
              left: r.left,
              top: r.top,
              width: r.width,
              height: r.height
            });
          }
          var cfi = null;
          try {
            if (window.__cfiBridge &&
                typeof window.__cfiBridge.rangeToCFI === 'function') {
              cfi = window.__cfiBridge.rangeToCFI(range, CHAPTER_HREF, document);
            }
          } catch (eCfi) {
            console.warn('epub-cfi: rangeToCFI threw', eCfi);
          }
          var quote = null;
          try {
            if (window.__epubQuote &&
                typeof window.__epubQuote.extract === 'function') {
              quote = window.__epubQuote.extract(range, document).quote;
            }
          } catch (eQuote) {
            quote = null;
          }
          return {
            type: 'epub-selection',
            text: sel.toString().trim(),
            bounds: bounds,
            container: containerRect,
            rects: rects,
            pageNumber: CHAPTER_INDEX,
            cfi: cfi,
            quote: quote,
            chapterTitle: CHAPTER_TITLE
          };
        }

        document.addEventListener('mouseup', function() {
          if (timer) clearTimeout(timer);
          if (mouseupDebounceTimer) clearTimeout(mouseupDebounceTimer);
          var payload = buildSelectionPayload();
          endDrag();
          if (!payload) {
            postClear('mouseup-collapsed');
            return;
          }
          // Immediate, so the toolbar does not wait on the debounce.
          try {
            window.parent.postMessage(
              Object.assign({}, payload, { fast: true, rects: [] }),
              '*'
            );
          } catch (eFast) {}
          // Debounced, with the measured rects: the authoritative form. The
          // debounce exists because a real drag's final selection can still
          // change inside the window.
          mouseupDebounceTimer = setTimeout(function() {
            mouseupDebounceTimer = null;
            window.parent.postMessage(payload, '*');
          }, 100);
        });


        document.addEventListener('selectionchange', function() {
          if (dragState === 'dragging') return;
          var sel = window.getSelection();
          if (!sel || sel.isCollapsed) {
            if (timer) clearTimeout(timer);
            timer = setTimeout(function() {
              postClear('selection-collapsed');
            }, 200);
          }
        });

        var scrollDismissTimer = null;
        function dismissOnScroll() {
          if (dragState === 'dragging') return;
          if (scrollDismissTimer) clearTimeout(scrollDismissTimer);
          scrollDismissTimer = setTimeout(function() {
            postClear('scroll');
          }, 50);
        }
        window.addEventListener('scroll', dismissOnScroll, true);
        document.addEventListener('scroll', dismissOnScroll, true);

        // Document teardown (a chapter swap replaces this whole document) closes
        // the toolbar: the selection it was anchored to stops existing with it.
        // Bounded so a message the parent never receives cannot leak.
        window.addEventListener('pagehide', function() {
          postClear('pagehide');
        });
        window.addEventListener('beforeunload', function() {
          postClear('pagehide');
        });

        // ─── Pagination key routing ──────────────────────────────────────
        // When the chapter document is the only scrollable surface (paginated
        // mode pins the parent container to one page box and moves the overflow
        // in here), focusing the frame means the browser's own arrow handling
        // consumes the key and scrolls the columns by its default step — a few
        // dozen pixels, not a page — and the parent window never sees a keydown
        // at all, because key events do not cross a frame boundary.
        //
        // So the frame reports the arrow upward and the parent decides. It only
        // does this when the document is genuinely the inline column layout, so
        // scrolling mode keeps its existing behaviour untouched; and it only
        // claims Left/Right, leaving Up/Down to the browser for intra-page
        // scrolling of a tall block.
        document.addEventListener('keydown', function(ev) {
          if (ev.key !== 'ArrowRight' && ev.key !== 'ArrowLeft') return;
          if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
          var html = document.documentElement;
          var rootStyle = window.getComputedStyle(html);
          var inlineColumns =
            (rootStyle.overflowX === 'auto' || rootStyle.overflowX === 'scroll') &&
            rootStyle.overflowY !== 'auto' &&
            rootStyle.overflowY !== 'scroll';
          if (!inlineColumns) return;
          var target = ev.target;
          if (target && target.closest &&
              target.closest('input, textarea, select, button, [contenteditable], [role="textbox"], [role="searchbox"], [role="combobox"]')) {
            return;
          }
          ev.preventDefault();
          window.parent.postMessage({
            type: 'epub-pagination-key',
            direction: ev.key === 'ArrowRight' ? 'next' : 'prev',
            pageNumber: CHAPTER_INDEX
          }, '*');
        }, true);
      })();
    `;
}
