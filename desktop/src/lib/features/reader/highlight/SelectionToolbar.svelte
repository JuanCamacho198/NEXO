<script lang="ts">
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import type { MessageKey } from '$lib/shared/i18n';
  import { HIGHLIGHT_COLORS } from '$lib/features/reader/highlight/highlightColors';
  import {
    MENU_DIVIDER,
    MENU_ICON_BUTTON,
    MENU_SWATCH,
    MENU_SWATCH_DOT,
    MENU_SWATCH_DOT_ACTIVE,
  } from './floatingMenuStyles';
  import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
  import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';
  import type { DefinitionResult } from '$lib/shared/ports/DictionaryLookupPort';
  import { isDefinableSelection } from '$lib/shared/services/definition/definitionSelectionGuard';
  import BookOpenText from 'lucide-svelte/icons/book-open-text';
  import BookPlus from 'lucide-svelte/icons/book-plus';
  import Copy from 'lucide-svelte/icons/copy';
  import Languages from 'lucide-svelte/icons/languages';
  import { pushToast, type ToastType } from '$lib/shared/stores/ToastQueue.svelte';
  import LookupPanel from './LookupPanel.svelte';
  import { createLookup } from './useLookup.svelte';
  import {
    PANEL_HEIGHT_FACTOR,
    floatingMenuAvailableWidth,
    placeFloatingMenu,
    resolveFloatingMenuLayout,
  } from '$lib/shared/services/placement/floatingMenuPlacement';
  import { reservesResultPanelSpace, showsTooltips } from './selectionPanelReservation';

  // The full data we need to persist a highlight. The parent captures this at
  // selection time and passes it back to us as a prop so that the click on a
  // color can forward it directly to the parent's handler. This avoids a race
  // with the browser's `selectionchange` event: even if the parent's global
  // selection state is cleared before our click handler runs, the data the
  // handler needs is already in the argument we pass.
  export type SelectionData = {
    text: string;
    bounds: { left: number; top: number; right: number; bottom: number };
    rects: Array<{ left: number; top: number; width: number; height: number }>;
    pageNumber: number;
    cfi: string | null;
    /** EPUB evidence: the containing paragraph, or null when none was found. */
    quote?: string | null;
    /** EPUB evidence: the chapter the selection came from. */
    chapterTitle?: string | null;
  };

  type Props = {
    // Kept in the contract for callers that only have the raw string; the
    // toolbar itself reads `selectionData`, which carries the same text plus
    // the EPUB evidence the capture path needs.
    selectedText: string;
    selectionBounds: { left: number; top: number; right: number; bottom: number };
    containerRect: { left: number; top: number; width: number; height: number };
    selectionData: SelectionData | null;
    /**
     * The window the toolbar is placed in. Optional because every production
     * caller is already inside one; it is a prop so tests can place the toolbar
     * at a width the JSOM window cannot be moved to.
     *
     * It is NOT read as `window.innerWidth` inside the placement `$derived`,
     * and that is not a style preference. A `$derived` that touches the ambient
     * `window` is evaluated once and cached for the lifetime of the MODULE, so
     * the second mount in a test process silently re-uses the first mount's
     * width — measured: unmounting and re-rendering at 116px still produced the
     * `full` layout decided at 1024px. Taking the viewport as a value keeps the
     * decision inside the instance, where a re-render re-evaluates it.
     */
    viewport?: { width: number; height: number };
    onCopy: () => void;
    onAddToDictionary: (data: SelectionData) => Promise<DictionaryCaptureFeedback>;
    onTranslate: (data: SelectionData) => Promise<TranslationOutcome>;
    /**
     * Persists a language pair chosen in the result panel. Separate from
     * `onTranslate` because it is a WRITE, not a read: the request itself stays
     * `onTranslate`, and the toolbar re-runs it after this resolves.
     *
     * The payload carries the WHOLE pair plus `changed`, which names the side the
     * reader actually moved. Both are needed and neither implies the other: the
     * pair is what the bar was showing, and `changed` is what the caller may take
     * from it. Persisting the untouched side straight from here is the defect
     * this signature exists to prevent — see `handleLanguagesChange`.
     */
    onChangeLanguages: (next: {
      source: string | null;
      target: string | null;
      changed: 'source' | 'target';
    }) => Promise<void>;
    onDefine: (data: SelectionData) => Promise<DefinitionResult>;
    onColorSelect: (color: string, data: SelectionData) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let {
    selectionBounds,
    containerRect,
    selectionData,
    viewport = undefined,
    onCopy,
    onAddToDictionary,
    onTranslate,
    onChangeLanguages,
    onDefine,
    onColorSelect,
    t,
  }: Props = $props();

  /**
   * The window the toolbar is placed in.
   *
   * TWO sources on purpose, and the second is not a convenience:
   *
   *   - `viewport` (the prop) is what a test supplies so it can place the
   *     toolbar at a width it cannot move a real window to.
   *   - `liveViewport` is what a PERSON gets. It is a `$state` fed by a `resize`
   *     listener, because a default value on a prop is evaluated ONCE, when the
   *     component is constructed — so a toolbar opened before a resize (or, on
   *     the desktop app, before the window reaches its final size) would keep
   *     deciding its layout against the width that existed at mount. Measured
   *     over CDP: the row stayed in the wrapped layout at a 1400px viewport
   *     because it had been constructed while the window was 298px wide.
   *
   * Reading `window.innerWidth` directly inside the placement `$derived` is the
   * third option and the wrong one: such a `$derived` is evaluated once and
   * cached for the module's lifetime, so the second mount in a test process
   * silently re-uses the first mount's width.
   */
  let liveViewport = $state({
    width: typeof window !== 'undefined' ? window.innerWidth : 0,
    height: typeof window !== 'undefined' ? window.innerHeight : 0,
  });

  $effect(() => {
    if (typeof window === 'undefined') return;
    const onResize = (): void => {
      liveViewport = { width: window.innerWidth, height: window.innerHeight };
    };
    // The effect can run before the first `resize` arrives (the window may
    // already have been resized between mount and here), so read once up front.
    onResize();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  });

  const resolvedViewport = $derived(viewport ?? liveViewport);

  /**
   * The reader's reduced-motion preference, read here for the same reason
   * `ReaderTocPanel` and four siblings read it: a Svelte transition (and, for
   * the placement move below, the CSS transition) must be zeroed by hand when
   * the user asked for no motion.
   *
   * The global `@media (prefers-reduced-motion: reduce)` block in `tokens.css`
   * already forces `transition-duration: 1ms !important` on every element, so
   * the CSS half would be covered without this. It is read explicitly anyway so
   * the behaviour is stated in the component that owns it, matches the pattern
   * the rest of the reader follows, and is testable without a real browser.
   */
  let reducedMotion = $state(false);
  $effect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const apply = (): void => {
      reducedMotion = mq.matches;
    };
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  });

  let selectedColor = $state(HIGHLIGHT_COLORS[0].hex);
  /**
   * Translate and Define as one lookup with two tabs (see `useLookup`): the
   * reader moves between them in the panel instead of stacking two panels.
   */
  const lookup = createLookup({
    translate: () => onTranslate(selectionData!),
    define: () => onDefine(selectionData!),
    changeLanguages: (next) => onChangeLanguages(next),
    canTranslate: () => Boolean(selectionData && selectionData.text.trim()),
    canDefine: () => Boolean(selectionData) && canDefine,
  });
  const translationHighlight = $derived(
    lookup.tab === 'translate' ? 'bg-white/10 text-(--color-brand-text)' : '',
  );

  /**
   * The custom-color popover, shown only in the `collapsed` layout.
   *
   * The compaction the reveal is part of: one the palette is folded into a
   * single swatch, this is where a custom color is still reachable. It is
   * rendered inline rather than through `ColorPickerPopover`, because that
   * component is anchored by reading its anchor element's rect on every render.
   * Chaining two measured popovers is the tolerated case, not the rule.
   */
  let pickerOpen = $state(false);
  let pickerAnchor = $state<HTMLButtonElement | null>(null);

  const definitionActive = $derived(
    lookup.tab === 'define' ? 'bg-white/10 text-(--color-brand-text)' : '',
  );

  /**
   * The C+A limit, as a `$derived` over the SAME text the button would send.
   * It reads `selectionData.text` (not the `selectedText` prop) because
   * `selectionData` is what `handleDefine` forwards — deriving from a different
   * string than the one sent is how a button ends up enabled for something the
   * handler then refuses.
   */
  const canDefine = $derived(isDefinableSelection(selectionData?.text));

  /**
   * The pill row's height, MEASURED against the live app over CDP.
   *
   * A 28px button plus the row's own `py-2` (8px top and bottom) and its 1px
   * border both sides is 46px, which is what `getBoundingClientRect().height`
   * reports for the rendered `[role="toolbar"]`.
   *
   * The previous value was 56 — ten pixels of phantom height, which the
   * placement turns directly into ten pixels the menu floats ABOVE the word. It
   * is also the above/below flip point, where a few pixels of error only moves
   * where the flip happens; here it is the distance from the selection, which is
   * the thing the reader notices.
   */
  const TOOLBAR_HEIGHT_ESTIMATE = 46;
  /**
   * The toolbar's placement width, fixed rather than measured.
   *
   * The pill is a row of colors and icon buttons at a known size, so its width
   * is predictable — and a PREDICTED width is the point: measuring the real
   * element would force a place-then-correct cycle, and the correction lands on
   * the second frame as a visible jump.
   *
   * MEASURED against the live app over CDP, not estimated. The previous value
   * (260) was 118px short of reality, which is why the clamp let the pill run
   * past the right edge: it believed a 378px row fit in a 260px box. The
   * breakdown, measured the same way:
   *
   *   color group  (5 x 24px + 4 x 12px gap) = 168px
   *   separator                              =   4px
   *   actions group (4 x 34px + 3 x 4px gap) = 148px
   *   pill padding (px-4, both sides)        =  32px
   *   border (1px, both sides)               =   2px
   *                                             378px
   *
   * The height estimate is only used for the above/below flip, where a few
   * pixels of error just moves the flip point; the width is what the clamp
   * depends on, so it is the one that has to be right.
   */
  const TOOLBAR_MAX_WIDTH = 380;
  /**
   * The same pill with the five color circles folded into one trigger.
   *
   * Measured the same way: the 168px color group becomes a single 28px swatch,
   * so 380 - 168 + 28 = 240. The extra pixel over the measured 238 is the same
   * safety margin `TOOLBAR_MAX_WIDTH` carries, so a slightly bolder font or a
   * wider scrollbar cannot tip the row into overflow between the decision and
   * the paint.
   */
  const TOOLBAR_COLLAPSED_WIDTH = 240;

  const TOOLBAR_EDGE_PADDING = 16;
  /**
   * The gap between the menu and the selected line.
   *
   * Small on purpose: the menu belongs to the word it was raised on, and a
   * larger gap reads as the toolbar floating away from the text. This is 8px —
   * a nudge, not a fix. The fix for "the menu appears far above the word" is the
   * honest height the placement now reserves (see `placementMenuSize`); this
   * constant only sets how tightly the menu is tucked in once it is in the right
   * place.
   */
  const TOOLBAR_OFFSET = 8;

  /**
   * The collapsed palette popover's width, and a predicted one for the same
   * reason the pill's widths are predicted: measuring would mean placing it,
   * reading it back and moving it, which is a visible jump on open.
   *
   * MEASURED against the live app over CDP: five 24px swatches with 8px gaps and
   * 8px padding both sides is 168px of content; the constant carries the 8px of
   * slack the pill's own widths carry, so a bolder swatch or a scrollbar cannot
   * tip it over the edge between the decision and the paint.
   */
  const PICKER_ESTIMATED_WIDTH = 176;

  /**
   * Keeps the palette popover inside the viewport, the same way
   * `placeFloatingMenu` keeps the toolbar inside it — the LEFT edge is pinned to
   * the padding when there is no room rather than allowed to go negative.
   *
   * Extracted and exported for the unit test: this is the arithmetic the bug was
   * made of, and jsdom can exercise it with numbers even though it cannot lay
   * anything out.
   */
  function clampPickerLeft(left: number, width: number, viewportWidth: number): number {
    return Math.max(
      TOOLBAR_EDGE_PADDING,
      Math.min(left, viewportWidth - width - TOOLBAR_EDGE_PADDING),
    );
  }
  // One localized message per capture outcome (REQ-DRE-014). `error` reuses the
  // app's generic failure copy: the reader has no capture-specific error the
  // user can act on, and inventing one would add a key no requirement names.
  const DICTIONARY_FEEDBACK_KEYS: Record<DictionaryCaptureFeedback, MessageKey> = {
    created: 'reader.addedToDictionary',
    'evidence-updated': 'reader.dictionaryEvidenceUpdated',
    'already-in-dictionary': 'reader.dictionaryAlreadyInDictionary',
    'no-match': 'reader.dictionaryNoMatch',
    ambiguous: 'reader.dictionarySeveralMatches',
    error: 'error.somethingWrong',
  };
  // The toast's tone follows the outcome: saved is a success, a no-op is
  // information, and a capture that could not happen is an error.
  const DICTIONARY_FEEDBACK_TONE: Record<DictionaryCaptureFeedback, ToastType> = {
    created: 'success',
    'evidence-updated': 'success',
    'already-in-dictionary': 'info',
    'no-match': 'error',
    ambiguous: 'error',
    error: 'error',
  };

  // The selection's box, translated from container space into the window space
  // the fixed-position toolbar actually lives in. The container (the EPUB
  // iframe) carries its own offset, so the selection is shifted by it BEFORE
  // placement: clamping a container-relative X against the window is how the
  // toolbar used to end up past the window's right border.
  const selectionWindowBounds = $derived({
    left: containerRect.left + selectionBounds.left,
    right: containerRect.left + selectionBounds.right,
    top: containerRect.top + selectionBounds.top,
    bottom: containerRect.top + selectionBounds.bottom,
  });

  /**
   * The room the toolbar has, in the SAME band the placement clamp works in.
   *
   * Derived from one source (`floatingMenuAvailableWidth`) so a layout can never
   * be chosen for a band the clamp does not agree exists — a menu told it "fits"
   * against the full viewport width and then clamped to a narrower one is the
   * layout and the position disagreeing.
   */
  const toolbarAvailableWidth = $derived(
    floatingMenuAvailableWidth(resolvedViewport, { edgePadding: TOOLBAR_EDGE_PADDING }),
  );

  /**
   * Which of the three layouts the toolbar renders in.
   *
   * In a normal window this is always `full` and the toolbar is byte-for-byte
   * what it was. The narrower rungs only engage once the selection sits close
   * enough to an edge that the natural row would not fit.
   */
  const toolbarLayout = $derived(
    resolveFloatingMenuLayout(
      { naturalWidth: TOOLBAR_MAX_WIDTH, availableWidth: toolbarAvailableWidth },
      { collapsedWidth: TOOLBAR_COLLAPSED_WIDTH },
    ),
  );

  /** The palette folds away on BOTH narrow rungs: once the row has to wrap, the
   * five circles are the first thing that has to give, and leaving them in place
   * would wrap a row that the collapse rung had already made fit. */
  const toolbarCollapsed = $derived(toolbarLayout !== 'full');
  const toolbarWrapped = $derived(toolbarLayout === 'wrapped');

  /**
   * The width floor for the WRAPPED row, as a class.
   *
   * `min-w-37` is 148px in Tailwind v4's 4px spacing scale: the four actions the
   * toolbar always keeps, measured at 148px. Wrapping already bought one line
   * break, so letting the four 34px buttons break there too laid them out as a
   * ragged 2D block and collapsed the row's WIDTH to whatever the longest label
   * happened to be — measured: a 140px window rendered a 41px-wide pill, a
   * sliver none of the buttons fit in. Clamping the wrapped row HERE keeps a
   * genuinely too-narrow window overflowing (which the placement then pins, and
   * which is honest) instead of silently shrinking the menu into something no
   * one can use.
   */
  const actionsRowMinWidthClass = $derived(toolbarWrapped ? 'min-w-37' : '');

  /**
   * The `min-w-37` floor above, as the number the arithmetic needs.
   *
   * Tailwind v4's 4px spacing scale makes `min-w-37` 148px. The panel's VIEWPORT
   * bound is floored with this so a panel is never laid out narrower than the row
   * floating above it, in the one window narrow enough for that bound to engage
   * (`toolbarAvailableWidth` under 148px). Two values, one meaning: the class is
   * what the row renders at, this is what the bound is computed against.
   */
  const TOOLBAR_WRAPPED_MIN_WIDTH = 148;

  /**
   * Where the collapsed palette's popover is placed, in VIEWPORT coordinates,
   * from the real element the trigger occupies.
   *
   * This one DOES read the DOM, and deliberately: the popover is opened by a
   * click, so the geometry exists and is settled before anything needs to be
   * positioned. There is no first frame to be wrong on — unlike the toolbar,
   * which is placed while its own render is still in flight, and where the same
   * measurement would be the visible jump the placement module exists to avoid.
   *
   * ## Why `fixed` and not `absolute`
   *
   * `getBoundingClientRect()` is measured from the VIEWPORT, so these numbers are
   * only meaningful against a `fixed` element. The popover used to be `absolute`,
   * which resolves `top` against its nearest positioned ancestor — the
   * `class="group relative"` wrapper around the trigger, itself inside the pill.
   * Adding a viewport coordinate to a container-relative origin puts the popover
   * a container offset too low. MEASURED over CDP on the real app in `collapsed`,
   * on a 300x720 window:
   *
   *     popover  y 149..191   (view `top: 92.5px` + the group's own ~56px offset)
   *     pill     y  48..94
   *     toolbar  y  57..103, `overflow: hidden`
   *     elementFromPoint at the popover's centre: the chapter `iframe`
   *
   * — i.e. the popover exists in the DOM, sits past the box that clips it, and is
   * painted NOWHERE. The button appeared to do nothing.
   *
   * `fixed` is the same coordinate space the measurement is in, so no offset is
   * computed and none can be wrong. It is also what the sibling
   * `ColorPickerPopover.svelte` does with exactly these two numbers, which is the
   * existing precedent in this area.
   *
   * The horizontal side is measured too: the palette is up to five swatches wide
   * (168px + padding), and pinning it to the trigger's left edge would run it off
   * the right border in the narrow window that produced the `collapsed` layout in
   * the first place. `clampPickerLeft` keeps it inside the viewport instead,
   * mirroring how the toolbar itself is clamped.
   */
  const pickerRect = $derived(pickerAnchor?.getBoundingClientRect() ?? null);
  const pickerTop = $derived(pickerRect ? pickerRect.bottom + TOOLBAR_EDGE_PADDING / 2 : 0);
  const pickerLeft = $derived(
    clampPickerLeft(pickerRect?.left ?? 0, PICKER_ESTIMATED_WIDTH, resolvedViewport.width),
  );

  /**
   * How much height the placement reserves for what hangs BELOW the pill: the
   * definition and translation panels, on top of the row itself.
   *
   * Without this the placement only knew about a 56px row, so in a short window
   * it chose `below` — there was room for the row — and the panel that followed
   * pushed the box past the bottom edge. The panels are siblings, not children,
   * so they are invisible to the placement unless they are budgeted here.
   *
   * Two different reserves on purpose. The ordinary case adds TRANSLATION room:
   * one to a few lines, which is what `handleTranslate` produces. The full
   * definition panel is a list of glosses and much taller, so the rolled-up
   * layout — already the crowded case — is given more. Measuring either is not
   * an option: the panel is data-dependent and a measured one is the
   * place-then-correct jump this whole module exists to avoid.
   */
  // Room for the lookup panel: its tab header (~44px) plus a few lines of
  // answer. Longer answers scroll inside the panel instead of growing it.
  const PLACEMENT_PANEL_BUDGET = 200;
  const PLACEMENT_PANEL_BUDGET_COLLAPSED = PANEL_HEIGHT_FACTOR * TOOLBAR_HEIGHT_ESTIMATE;

  /**
   * WHICH of the two things below the pill is on screen, and — for the
   * placement — whether that thing RESERVES space.
   *
   * The panel budget is reserved only for a RESULT panel (`translation` /
   * `definition`), which is data-dependent, usually null, and many lines tall.
   * Feedback (`copyFeedback` / `dictionaryFeedback`) is a single short line that
   * disappears after 1.5s, so it renders exactly as before but reserves nothing.
   *
   * The earlier code put all six states in ONE bag (`resultPanelOpen`) and
   * reserved whenever any of them was set. That is the reported defect, in
   * numbers: pressing Copy raised a one-line message, the placement believed the
   * menu was ~120px taller, the menu was moved UP to make room for a panel that
   * was never going to need it, and when the message expired (1.5s later) it
   * moved back DOWN. MEASURED over CDP: the pill's `top` moved on the press and
   * moved back on the expiry, which is the "el menú sube hacia arriba y luego
   * vuelve hacia abajo" the reader reported.
   *
   * The two predicates live in `selectionPanelReservation.ts` so the table is a
   * unit test rather than a comment: `showsShortFeedback` is what the feedback
   * panel renders under, `reservesResultPanelSpace` is what the placement
   * budgets. A state in neither is a defect the tests make loud.
   */
  // Copy and dictionary feedback are toasts at the bottom of the window now,
  // so nothing short ever hangs under the pill: only the lookup panel does.
  const panelState = $derived({
    copyFeedback: false,
    dictionaryFeedback: false,
    translating: lookup.tab === 'translate' && lookup.translating,
    translation: lookup.tab === 'translate' && lookup.translation !== null,
    defining: lookup.tab === 'define' && lookup.defining,
    definition: lookup.tab === 'define' && lookup.definition !== null,
    definitionFailed: lookup.tab === 'define' && lookup.definitionFailed,
  });

  const reservesPanelSpace = $derived(reservesResultPanelSpace(panelState));

  /**
   * Whether the button tooltips are allowed to reveal.
   *
   * They reveal on `group-hover` AND `group-focus-within` — and the `focus-within`
   * half is what made them stick: pressing a button focuses it, so the tooltip
   * stayed visible exactly where the feedback / result panel is drawn, and
   * "Copiar" and "Copiado al portapapeles" landed on the same pixels.
   *
   * The reveal is kept — `group-focus-within` is the keyboard reader's only way
   * to see a tooltip — and the tooltip is simply suppressed while an answer is on
   * screen, because a hint about what a button DOES has nothing to add once the
   * button has answered.
   */
  const tooltipsAllowed = $derived(showsTooltips(panelState));

  const placementMenuSize = $derived({
    width: toolbarCollapsed ? TOOLBAR_COLLAPSED_WIDTH : TOOLBAR_MAX_WIDTH,
    height:
      (toolbarWrapped ? TOOLBAR_HEIGHT_ESTIMATE * 2 : TOOLBAR_HEIGHT_ESTIMATE) +
      (reservesPanelSpace
        ? toolbarCollapsed
          ? PLACEMENT_PANEL_BUDGET_COLLAPSED
          : PLACEMENT_PANEL_BUDGET
        : 0),
  });

  /**
   * The height the box is actually RENDERED at, when it is shorter than what the
   * placement reserved.
   *
   * `placementMenuSize.height` is the box that has to be RESERVED — the row plus,
   * while one is open, a result panel. This is the box the browser paints, which
   * is the same number whenever a panel is open and only the row when none is.
   * The two agree by construction now that `placementMenuSize` stops reserving a
   * panel that is not there, so the clamp has nothing to correct in the ordinary
   * case.
   *
   * The `max-h` cap below is `placementMenuSize.height`, the same number, so what
   * is clamped here and what the browser is allowed to paint cannot disagree —
   * which is what makes "the toolbar has no height budget at all, so it cannot be
   * off screen vertically" a property rather than a hope. It is also the only
   * correct answer in a very short window, where the budget exceeds the window
   * (768 + 120 against a 298px one): `max-height: 100%` resolves against the
   * positioning containing block, so the box stops at the viewport and the rows
   * wrap inside it.
   */
  const placementRenderedHeight = $derived(placementMenuSize.height);

  /** The same budget the placement reserved, as the cap the box is painted
   * under. `100%` is the floor so a window shorter than the budget still gets a
   * box inside the viewport instead of one taller than it. */
  const toolbarMaxHeight = $derived(`max(100%, ${placementMenuSize.height}px)`);

  /**
   * The RESULT PANEL's width, and why it is the pill's own width and not a
   * number of its own.
   *
   * Both of them share this one value — the translation panel and the definition
   * panel read it from here — and it is `placementMenuSize.width`, the SAME
   * value the pill's own class cascade and `placeFloatingMenu` are given
   * (`TOOLBAR_MAX_WIDTH` = 380 in `full`, `TOOLBAR_COLLAPSED_WIDTH` = 240 in
   * `collapsed`). A panel narrower than the pill above it is the defect this
   * replaces: the translated paragraph wrapped into a cramped column beside a
   * full-width language bar.
   *
   * The panel is a sibling of the placement-visible menu, not part of it, so its
   * left edge — `toolbarX`, which also reads `placementMenuSize` — lines the two
   * up only if the widths agree. Deriving it from the placement is what makes
   * that a property rather than a coincidence.
   *
   * The VIEWPORT bound is kept, and it is what keeps a 380px panel from
   * widening past the window in a 298px one: the pill wraps and stays usable at
   * any width, but the panel is content, so it has to be given a width it can
   * actually be laid out at. It only engages in that narrow band — a band too
   * narrow for the wrapped row's own 148px floor — so in every ordinary window
   * the panel IS the pill's fixed width. Fixed on purpose: content-based sizing
   * would make the panel jump every time the translation length changed.
   *
   * Leaving `selectionWindowBounds` out of the dependents is deliberate: a
   * panel that re-positioned itself when the reader adjusts the selection would
   * move out from under the text they are reading.
   */
  const resultPanelWidth = $derived(
    Math.min(placementMenuSize.width, Math.max(TOOLBAR_WRAPPED_MIN_WIDTH, toolbarAvailableWidth)),
  );

  const toolbarPlacement = $derived(
    placeFloatingMenu(selectionWindowBounds, placementMenuSize, resolvedViewport, {
      edgePadding: TOOLBAR_EDGE_PADDING,
      offset: TOOLBAR_OFFSET,
      renderedHeight: placementRenderedHeight,
    }),
  );

  const toolbarX = $derived(toolbarPlacement.x);
  const toolbarY = $derived(toolbarPlacement.y);

  /**
   * The caret that ties the pill to the selection, like the highlight menu's.
   * It is drawn OUTSIDE the toolbar box (which clips its overflow for the
   * panels' height ceiling), at a position computed from the same numbers the
   * pill uses: the selection's centre, clamped inside the pill, on the pill's
   * edge that faces the text. The wrapped layout has no single edge, so it
   * goes without.
   */
  const caret = $derived.by((): { x: number; y: number; up: boolean } | null => {
    // With the lookup panel open the pill is no longer the edge facing the
    // text, so the caret would point into the panel: it steps aside.
    if (toolbarWrapped || lookup.open) return null;
    const pillLeft = toolbarX + pillOffset;
    const pillWidth = toolbarCollapsed ? PILL_WIDTH_COLLAPSED : PILL_WIDTH_FULL;
    const centre = (selectionWindowBounds.left + selectionWindowBounds.right) / 2;
    const x = Math.max(pillLeft + 18, Math.min(centre, pillLeft + pillWidth - 18));
    const above = toolbarPlacement.placement === 'above';
    return {
      x: x - 5,
      y: above ? toolbarY + TOOLBAR_HEIGHT_ESTIMATE - 6 : toolbarY - 5,
      up: !above,
    };
  });

  /**
   * The panel's height ceiling, in pixels.
   *
   * A result panel is bounded by the ROOM IN THE WINDOW, not by a fixed
   * `max-h-80` guess: below the pill there is whatever is left of the screen, so
   * a 320px ceiling is a third of a page in a tall window and taller than the
   * whole window in a short one. Two numbers per layout, because the pill is a
   * different height in each:
   *
   *   - `above` — the pill sits below the panel, so the panel has the pill's own
   *     top edge to spend. `max(120, …)` keeps it usable when the anchor is near
   *     the top edge and there is almost nothing to give it.
   *   - `below` — the panel starts under the pill, so it has the window minus the
   *     pill's bottom edge.
   *
   * `overflow-y-auto` on the panel turns the bound into scrolling rather than a
   * clipped definition.
   */
  const resultPanelMaxHeight = $derived(
    toolbarPlacement.placement === 'above'
      ? // Above the selection the panel hangs between the pill and the text,
        // so its ceiling is the room the placement reserved for it — never
        // more, or it covers the very words it is about.
        Math.max(120, placementMenuSize.height - TOOLBAR_HEIGHT_ESTIMATE - 8)
      : Math.max(120, resolvedViewport.height - (toolbarY + placementMenuSize.height)),
  );

  /**
   * The placement-move transition, as a class, and why it is conditional.
   *
   * The menu is repositioned by writing `top` — and before this, that write was
   * INSTANT. For Translate and Define the budget genuinely changes when the
   * panel opens, so the menu genuinely has to move; the reader asked for that
   * move to be a small transition rather than a jump.
   *
   * Only `top` is transitioned. `left` moves at most a few pixels (the clamp
   * against the window edge) and animating it buys nothing, while `top` is the
   * axis the reported defect was on.
   *
   * The values are the PROJECT's tokens, not invented: `--duration-fast` is
   * 150ms and `--ease-smooth` is `cubic-bezier(0.4, 0, 0.2, 1)`, both declared
   * in `tokens.css` under `@theme`, so Tailwind v4 exposes them as
   * `duration-fast` and `ease-smooth`. 150ms is the "short transition" the
   * request named: long enough to read as movement, short enough that the menu is
   * not seen travelling.
   *
   * Zeroed under `prefers-reduced-motion`: no class, no transition, the same
   * instant reposition as before.
   *
   * It is NOT an entrance animation. The element is a fresh node on mount with
   * its final `top` already written, and a CSS transition only interpolates
   * between two values on the same element — so the first paint cannot animate
   * the menu into place. The existing `in:scale` transition owns the entrance.
   */
  const placementMoveTransitionClass = $derived(
    reducedMotion ? '' : 'transition-[top] duration-fast ease-smooth',
  );

  /** Tailwind v4 folds `flex` + `hidden` at the same breakpoint into one static
   * class list, so the two branches below are spelled out per branch.
   * `actionsRowMinWidthClass` is folded in for the same reason it exists: the
   * wrapped row keeps a usable width instead of collapsing to its longest
   * label. */
  /**
   * The pill's own width, computed rather than measured: every piece has a
   * fixed size (28px swatches, 32px actions, 4px gaps, 10px padding, a 1px
   * divider with 4px margins, a 1px border each side). The placement reserves
   * a wider box (380 / 240) so the result panels have room; the pill is
   * centred inside it with this offset instead of being stretched across it.
   */
  const PILL_WIDTH_FULL = 5 * 28 + 4 * 2 + 9 + 4 * 32 + 5 * 4 + 2 * 10 + 2;
  const PILL_WIDTH_COLLAPSED = 28 + 9 + 4 * 32 + 5 * 4 + 2 * 10 + 2;
  const pillOffset = $derived(
    toolbarWrapped
      ? 0
      : Math.max(
          0,
          Math.round(
            (placementMenuSize.width -
              (toolbarCollapsed ? PILL_WIDTH_COLLAPSED : PILL_WIDTH_FULL)) /
              2,
          ),
        ),
  );

  const actionsRowClass = $derived(
    toolbarWrapped
      ? `flex flex-wrap items-center justify-center gap-1.5 rounded-3xl border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) px-3 py-2 shadow-[0_16px_36px_-14px_rgba(0,0,0,0.6)] ${actionsRowMinWidthClass}`
      : 'flex w-max items-center gap-1 rounded-full border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) px-2.5 py-1.5 shadow-[0_16px_36px_-14px_rgba(0,0,0,0.6)]',
  );

  function selectColor(hex: string): void {
    selectedColor = hex;
    // Forward the selection data captured at mount time so the parent's
    // handler can persist the highlight even if the browser's
    // selectionchange has already cleared its global selection state.
    if (selectionData) {
      onColorSelect(hex, selectionData);
    }
  }

  /** The collapsed palette's trigger: it opens the palette, it does not write. */
  function togglePicker(): void {
    pickerOpen = !pickerOpen;
  }

  /** The popover's swatches apply a color through the same path as the full row. */
  function selectFromPicker(hex: string): void {
    selectColor(hex);
    pickerOpen = false;
  }

  function handleCopy(): void {
    onCopy();
    pushToast('success', t('reader.copiedToClipboard'));
  }

  async function handleAddToDictionary(): Promise<void> {
    // The parent resolves the selection and writes at most one entry, so the
    // evidence captured with the selection (quote + chapter) travels in the
    // argument instead of the trimmed word string it replaced.
    if (!selectionData) return;
    if (!selectionData.text.trim()) return;

    const feedback = await onAddToDictionary(selectionData);
    pushToast(
      DICTIONARY_FEEDBACK_TONE[feedback] ?? 'info',
      t(DICTIONARY_FEEDBACK_KEYS[feedback] ?? 'reader.addedToDictionary'),
    );
  }
</script>

{#if caret}
  <span
    class="pointer-events-none fixed z-[51] size-2.5 rotate-45 border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) {caret.up
      ? 'border-t border-l'
      : 'border-r border-b'}"
    style="left: {caret.x}px; top: {caret.y}px;"
    aria-hidden="true"
    data-testid="selection-toolbar-caret"
    in:scale={{ duration: 140, start: 0.6, easing: cubicOut }}
  ></span>
{/if}

<div
  class="selection-toolbar fixed z-50 flex flex-col overflow-hidden {placementMoveTransitionClass}"
  data-floating-menu="true"
  style="left: {toolbarX}px; top: {toolbarY}px; max-height: {toolbarMaxHeight};"
  role="presentation"
  in:scale={{ duration: 140, start: 0.92, easing: cubicOut }}
  out:scale={{ duration: 100, start: 0.95, easing: cubicOut }}
  onmouseup={(e) => e.stopPropagation()}
>
  <!-- Main toolbar. `actionsRowClass` carries the wrap rung of the layout
       cascade: in a normal window this renders the same single-line pill it
       always has, and the row only breaks once the viewport leaves it no
       choice. -->
  <div
    class={actionsRowClass}
    style="margin-left: {pillOffset}px;"
    role="toolbar"
    aria-label={t('highlight.menuAriaLabel')}
  >
    <!-- Color row. Expanded (five circles) is the default; collapsed is one
         swatch that opens the palette, which is the same trade the highlight
         context menu makes with its multicolor `+` trigger. Both branches
         render every color, so a narrow window loses screen space, not actions.
         `key` is the HIGHLIGHT_COLORS index — the palette is a constant and is
         not reordered, so the index identifies an entry as well as its hex. -->
    {#if !toolbarCollapsed}
      <div class="flex items-center gap-0.5">
        {#each HIGHLIGHT_COLORS as color, index (index)}
          <button
            type="button"
            class={MENU_SWATCH}
            onclick={() => selectColor(color.hex)}
            aria-label={t('highlight.selectColor', { color: t(color.i18nKey) })}
          >
            <span
              class="{MENU_SWATCH_DOT} {selectedColor === color.hex ? MENU_SWATCH_DOT_ACTIVE : ''}"
              style="background-color: {color.hex};"
            ></span>
          </button>
        {/each}
      </div>
    {:else}
      <div class="group relative">
        <button
          type="button"
          bind:this={pickerAnchor}
          class={MENU_SWATCH}
          onclick={togglePicker}
          aria-label={t('highlight.changeColor')}
          aria-expanded={pickerOpen}
          title={t('highlight.changeColor')}
        >
          <span
            class="{MENU_SWATCH_DOT} {MENU_SWATCH_DOT_ACTIVE}"
            style="background-color: {selectedColor};"
          ></span>
        </button>
        <span
          class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white shadow-lg transition-opacity duration-150 {tooltipsAllowed
            ? 'group-hover:opacity-100 group-focus-within:opacity-100 opacity-0'
            : 'opacity-0'}"
          role="tooltip"
        >
          {t('highlight.changeColor')}
        </span>
        {#if pickerOpen}
          <div
            class="fixed z-[60] flex items-center gap-0.5 rounded-full border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) p-1.5 shadow-[0_16px_36px_-14px_rgba(0,0,0,0.6)]"
            style="left: {pickerLeft}px; top: {pickerTop}px;"
            role="group"
            aria-label={t('highlight.menuAriaLabel')}
          >
            {#each HIGHLIGHT_COLORS as color, index (index)}
              <button
                type="button"
                class={MENU_SWATCH}
                onclick={() => selectFromPicker(color.hex)}
                aria-label={t('highlight.selectColor', { color: t(color.i18nKey) })}
              >
                <span
                  class="{MENU_SWATCH_DOT} {selectedColor === color.hex
                    ? MENU_SWATCH_DOT_ACTIVE
                    : ''}"
                  style="background-color: {color.hex};"
                ></span>
              </button>
            {/each}
          </div>
        {/if}
      </div>
    {/if}

    <span class={MENU_DIVIDER} aria-hidden="true"></span>

    <!-- Translate -->
    <div class="group relative">
      <button
        type="button"
        class="{MENU_ICON_BUTTON} {translationHighlight}"
        onclick={() => lookup.toggle('translate')}
        disabled={lookup.translating && lookup.tab === 'translate'}
        aria-pressed={lookup.tab === 'translate'}
        aria-label={t('reader.translate')}
        title={t('reader.translate')}
      >
        <Languages size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span
        class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white shadow-lg transition-opacity duration-150 {tooltipsAllowed
          ? 'group-hover:opacity-100 group-focus-within:opacity-100 opacity-0'
          : 'opacity-0'}"
        role="tooltip"
      >
        {t('reader.translate')}
      </span>
    </div>

    <!-- Define. Disabled — not hidden — outside the C+A limit: a vanished button
         reads as a broken toolbar, a disabled one shows the action exists and
         the tooltip explains when it applies. The tooltip is suppressed while any
         panel is below the pill, so a disabled Define and a definition result
         never fight for the same pixels. -->
    <div class="group relative">
      <button
        type="button"
        class="{MENU_ICON_BUTTON} {definitionActive}"
        onclick={() => lookup.toggle('define')}
        disabled={(lookup.defining && lookup.tab === 'define') || !canDefine}
        aria-pressed={lookup.tab === 'define'}
        aria-label={t('reader.define')}
        title={t('reader.define')}
      >
        <BookOpenText size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span
        class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white shadow-lg transition-opacity duration-150 {tooltipsAllowed
          ? 'group-hover:opacity-100 group-focus-within:opacity-100 opacity-0'
          : 'opacity-0'}"
        role="tooltip"
      >
        {t('reader.define')}
      </span>
    </div>
    <!-- Add to Dictionary -->
    <div class="group relative">
      <button
        type="button"
        class={MENU_ICON_BUTTON}
        onclick={handleAddToDictionary}
        aria-label={t('reader.addToDictionary')}
        title={t('reader.addToDictionary')}
      >
        <BookPlus size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span
        class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white shadow-lg transition-opacity duration-150 {tooltipsAllowed
          ? 'group-hover:opacity-100 group-focus-within:opacity-100 opacity-0'
          : 'opacity-0'}"
        role="tooltip"
      >
        {t('reader.addToDictionary')}
      </span>
    </div>

    <!-- Copy -->
    <div class="group relative">
      <button
        type="button"
        class={MENU_ICON_BUTTON}
        onclick={handleCopy}
        aria-label={t('reader.copiar')}
        title={t('reader.copiar')}
      >
        <Copy size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span
        class="pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white shadow-lg transition-opacity duration-150 {tooltipsAllowed
          ? 'group-hover:opacity-100 group-focus-within:opacity-100 opacity-0'
          : 'opacity-0'}"
        role="tooltip"
      >
        {t('reader.copiar')}
      </span>
    </div>
  </div>

  {#if lookup.open}
    <LookupPanel {lookup} {t} width={resultPanelWidth} maxHeight={resultPanelMaxHeight} />
  {/if}
</div>
