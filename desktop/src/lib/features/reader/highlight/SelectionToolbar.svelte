<script lang="ts">
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import type { MessageKey } from '$lib/shared/i18n';
  import { HIGHLIGHT_COLORS } from '$lib/features/reader/highlight/highlightColors';
  import type { DictionaryCaptureFeedback } from '$lib/shared/dictionary/captureFromSelection';
  import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';
  import type { DefinitionResult } from '$lib/shared/ports/DictionaryLookupPort';
  import type {
    NoTranslationReason,
    SourceOrigin,
  } from '$lib/shared/services/translation/translationLanguageResolution';
  import { isDefinableSelection } from '$lib/shared/services/definition/definitionSelectionGuard';
  import TranslationLanguageBar from './TranslationLanguageBar.svelte';
  import {
    PANEL_HEIGHT_FACTOR,
    floatingMenuAvailableWidth,
    placeFloatingMenu,
    resolveFloatingMenuLayout,
  } from '$lib/shared/services/placement/floatingMenuPlacement';
  import {
    reservesResultPanelSpace,
    showsShortFeedback,
    showsTooltips,
  } from './selectionPanelReservation';

  /**
   * One message per refusal reason, and where a reason has more than one
   * provenance, one per provenance — because the FIX differs.
   *
   * `unknown-source` is the only genuine detection failure: nothing could name
   * the source, so the reader has to name one. `same-language` is told apart
   * because when the reader CHOSE the source, calling it a detection failure is
   * both false and useless — there was nothing to detect, and the choice is
   * theirs to revise. The chosen variant is therefore keyed on the source
   * ORIGIN, not on the reason alone: the same reason from an inferred source
   * keeps the existing book copy.
   *
   * Each names the fix rather than the failure: "the book has no language,
   * choose one in Settings" is actionable, a bare "no translation available" is
   * not.
   */
  const TRANSLATION_REFUSAL_KEYS: Record<NoTranslationReason, MessageKey> = {
    'same-language': 'reader.translationSameLanguage',
    'unknown-source': 'reader.translationUnknownSource',
    'invalid-source': 'reader.translationInvalidSource',
    'invalid-target': 'reader.translationInvalidTarget',
  };

  /**
   * The refusal key for an outcome, which consults the source ORIGIN before the
   * reason alone.
   *
   * A source the reader chose (`preference` in Settings, `bookPreference` for
   * this book) never reached the detector, so its failure must not be reported
   * as one: `unknown-source` is unreachable for those origins by construction,
   * but the two reasons that ARE reachable — a collision with the target and a
   * value the app cannot use — read as the reader's own setting and say so.
   * The detected/book origins keep the existing copy untouched.
   */
  function translationRefusalKey(
    reason: NoTranslationReason,
    sourceOrigin: SourceOrigin | null,
  ): MessageKey {
    const originIsAChoice = sourceOrigin === 'preference' || sourceOrigin === 'bookPreference';
    if (originIsAChoice) {
      if (reason === 'same-language') return 'reader.translationSameLanguageChosen';
      if (reason === 'invalid-source') return 'reader.translationInvalidSourceChosen';
    }
    return TRANSLATION_REFUSAL_KEYS[reason];
  }

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
  let copyFeedback = $state<string | null>(null);
  let dictionaryFeedback = $state<string | null>(null);
  let copyFeedbackTimer: ReturnType<typeof setTimeout> | null = null;
  let dictionaryFeedbackTimer: ReturnType<typeof setTimeout> | null = null;
  let translation = $state<TranslationOutcome | null>(null);
  let translating = $state(false);
  let translationActive = $state(false);
  const translationHighlight = $derived(
    translation !== null || translationActive ? 'bg-white/10' : '',
  );
  /**
   * The pair the panel's selector shows, or `null` when the bar must not render
   * at all. It is a `$derived` over BOTH `translation` and `pickedLanguages` so
   * the picked pair can never OUTLIVE the outcome it was chosen for.
   *
   * `pickedLanguages` is the overlay, and it is not redundant: the select is a
   * controlled input, so between the `change` and the fresh outcome the bar
   * would otherwise snap back to the old pair and read as an ignored click.
   *
   * But the overlay alone is not enough, and letting it win unconditionally is
   * the bug this shape fixes. `no-result` and `unavailable` carry NO pair on
   * purpose (see `TranslationOutcome`): one is a service answer, the other a
   * transport failure, and neither is a statement about languages — a selector
   * there would offer to change something that was not the problem. With the
   * overlay as the unconditional first branch, a change that landed on either
   * outcome left `pickedLanguages` set — nothing clears it — so the bar kept
   * rendering a pair beside "no translation" / "service unavailable", a pair
   * that belonged to a request that is no longer on screen.
   *
   * So the outcome decides FIRST, and the overlay only overrides a pair the
   * outcome already justified:
   *
   *   ok / refused     -> the outcome's own pair; `pickedLanguages` while a
   *                       change is in flight, so the bar holds the pair just
   *                       chosen instead of snapping back to the old one.
   *   no-result /
   *   unavailable      -> `null`, always. The overlay is not consulted.
   *
   * The source ORIGIN rides along with the pair and is deliberately NOT part of
   * the overlay: `pickedLanguages` is a pair the reader just chose, so its source
   * is by definition explicit (`preference`) — showing "Auto (español)" over a
   * language they picked would be a lie. Only the outcome can say the source was
   * inferred, and only the outcome is consulted for it.
   *
   * ## The overlay counts ONLY while a change is in flight
   *
   * `pickedLanguages` is written AND cleared by `handleLanguagesChange` — set
   * before the request, dropped the moment the answer lands — so its presence
   * means exactly "a retranslation is in flight". Reading it that way is what
   * this predicate states, and it is the single rule the pair and its origin are
   * both derived from.
   *
   * They have to share the rule, and the earlier shape is the reason: the pair
   * read the overlay as `pickedLanguages ?? outcome` (present OR absent) while
   * the origin read it as `pickedLanguages === null` (present vs absent), so a
   * single truth was decided by two different predicates. MEASURED on the running
   * app: a change to the source left the overlay set forever, so the bar printed
   * the pair the reader had picked (`Español → Inglés`) while the refusal message
   * beside it was drawn from the outcome and said both sides were the same.
   *
   * A change whose request never lands is covered by construction: the overlay is
   * cleared in the request's `finally`, so a hung request simply keeps showing the
   * pair the reader picked — which is what they asked for and what the bar was
   * already holding. No third state exists.
   */
  let pickedLanguages = $state<{ source: string | null; target: string | null } | null>(null);
  /**
   * The outcome that CARRIES a pair, narrowed to its two variants, or `null`.
   *
   * Every value below is derived from THIS rather than from a separate boolean
   * flag. TypeScript does not narrow a `$state` read across the branches of a
   * `$derived` that tests a DIFFERENT expression, so a shared `$derived` boolean
   * cannot make `translation.source` type-check — and, more to the point, a
   * boolean would let the pair and the flag drift the way `shownLanguages` and
   * `shownSourceOrigin` did. One narrowed value is the only shape in which the
   * four dependents below can disagree about nothing.
   */
  const pairOutcome = $derived(
    translation?.status === 'ok' || translation?.status === 'refused' ? translation : null,
  );
  /** The ONE overlay predicate. Both the pair and its origin are derived from
   * it, so they cannot disagree about whether the overlay applies — the drift
   * between them was the defect this replaces. */
  const overlayIsAhead = $derived(pairOutcome !== null && pickedLanguages !== null);
  const shownLanguages = $derived(
    pairOutcome === null
      ? null
      : overlayIsAhead
        ? pickedLanguages
        : { source: pairOutcome.source, target: pairOutcome.target },
  );
  const shownSourceOrigin = $derived(
    pairOutcome === null ? null : overlayIsAhead ? 'preference' : pairOutcome.sourceOrigin,
  );
  /**
   * The pair the RESOLVER actually used — the outcome's own, with the overlay
   * deliberately not consulted.
   *
   * This is what the bar reads for the side the reader did NOT move. The two
   * pairs are the same value whenever no change is in flight, and that is the
   * ordinary case; they differ exactly while the overlay is ahead, and there the
   * difference matters. The bar builds its payload as "the side that moved takes
   * its new value, the untouched side keeps what it had", and the value it had
   * has to be the one the resolver ran with, not a pair the resolver has not seen
   * yet. Sending the overlay's other side would hand the caller a pair the
   * request in flight never resolved, which is the whole of what `changed` exists
   * to prevent (`handleChangeLanguages` reads storage for exactly this reason —
   * this keeps the layer that OWNS both values from handing it the wrong one).
   *
   * A `null` side is carried through as `null`: it is a real value downstream
   * ("automatic"), and the caller is the one that decides whether a side is
   * written at all. This value is a FACT about the last resolution, never an
   * instruction to write.
   */
  const resolvedLanguages = $derived(
    pairOutcome === null ? null : { source: pairOutcome.source, target: pairOutcome.target },
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

  /**
   * The definition panel's state. `definition` is the last resolved result and
   * `definitionFailed` is a separate flag because a MISS and a FAILURE are
   * different truths: a miss is the sources answering "nothing", a failure is
   * the request never completing. Rendering the same copy for both would hide
   * an outage behind a bland "no definition".
   */
  let definition = $state<DefinitionResult | null>(null);
  let defining = $state(false);
  let definitionFailed = $state(false);
  const definitionActive = $derived(definition !== null || definitionFailed ? 'bg-white/10' : '');

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
  const PLACEMENT_PANEL_BUDGET = 120;
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
  const panelState = $derived({
    copyFeedback: Boolean(copyFeedback),
    dictionaryFeedback: Boolean(dictionaryFeedback),
    translating,
    translation: Boolean(translation),
    defining,
    definition: Boolean(definition),
    definitionFailed,
  });

  const reservesPanelSpace = $derived(reservesResultPanelSpace(panelState));
  const shortFeedbackOpen = $derived(showsShortFeedback(panelState));

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
      ? Math.max(120, toolbarY)
      : Math.max(120, resolvedViewport.height - (toolbarY + placementMenuSize.height)),
  );

  /**
   * The SHORT feedback panel's height ceiling: the room the pill leaves BELOW
   * itself, and nothing more.
   *
   * Feedback no longer reserves space, so the pill is no longer lifted to make
   * room for it — and a selection near the bottom edge is therefore the case
   * where the one-line message can hang past the window. The ceiling is the
   * window minus the pill's own bottom edge and the `mt-2` gap the panel sits
   * below it: the last coordinate that keeps the panel's bottom edge on screen.
   *
   * It is derived from the pill's REAL row height rather than from
   * `placementMenuSize.height`, which is the point: that is the box the
   * placement RESERVED, and for feedback the reservation is deliberately zero.
   * Bounding the feedback panel by it would let a 120px panel be drawn where 20px
   * of room exists, which is the overflow this constant exists to prevent.
   *
   * No `max(120, …)` floor, unlike the result panel. A result panel is content
   * the reader asked for and must stay usable; feedback is a transient one-line
   * message and a bound that is genuinely small there is honest. The floor is the
   * panel's own natural line height, so a message is never cut to a sliver.
   */
  const FEEDBACK_PANEL_MIN_HEIGHT = 26;
  const feedbackPanelMaxHeight = $derived(
    Math.max(
      FEEDBACK_PANEL_MIN_HEIGHT,
      resolvedViewport.height - (toolbarY + placementRenderedHeight + TOOLBAR_OFFSET),
    ),
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
  const actionsRowClass = $derived(
    toolbarWrapped
      ? `flex flex-wrap items-center justify-center gap-3 rounded-3xl border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) px-4 py-2 shadow-xl ${actionsRowMinWidthClass}`
      : 'flex items-center gap-3 rounded-full border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) px-4 py-2 shadow-xl',
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
    copyFeedback = t('reader.copiedToClipboard');
    if (copyFeedbackTimer) clearTimeout(copyFeedbackTimer);
    copyFeedbackTimer = setTimeout(() => {
      copyFeedback = null;
      copyFeedbackTimer = null;
    }, 1500);
  }

  async function handleAddToDictionary(): Promise<void> {
    // The parent resolves the selection and writes at most one entry, so the
    // evidence captured with the selection (quote + chapter) travels in the
    // argument instead of the trimmed word string it replaced.
    if (!selectionData) return;
    if (!selectionData.text.trim()) return;

    const feedback = await onAddToDictionary(selectionData);
    dictionaryFeedback = t(DICTIONARY_FEEDBACK_KEYS[feedback] ?? 'reader.addedToDictionary');
    if (dictionaryFeedbackTimer) clearTimeout(dictionaryFeedbackTimer);
    dictionaryFeedbackTimer = setTimeout(() => {
      dictionaryFeedback = null;
      dictionaryFeedbackTimer = null;
    }, 1500);
  }

  /**
   * Translate toggles a result panel. A second click (or the panel's close
   * button) clears it, so the selection stays usable while the result is up.
   * The request itself lives in the parent: the toolbar is presentational and
   * owns no port.
   */
  async function handleTranslate(): Promise<void> {
    if (translation) {
      translation = null;
      pickedLanguages = null;
      return;
    }
    if (!selectionData || !selectionData.text.trim()) return;

    translating = true;
    try {
      translation = await onTranslate(selectionData);
    } finally {
      translating = false;
    }
  }

  /**
   * Persist a language change and retranslate the SAME selection in place.
   *
   * It re-uses the request path `handleTranslate` uses instead of inventing a
   * second one, so a retranslation cannot drift from a first translation. It
   * does NOT call `handleTranslate`, though: that function's first branch CLEARS
   * the panel, and it would also skip the request for the same-language pair the
   * reader is asking to be told about — the refusal has to come from the
   * resolver, which is the only thing that knows the book's language and the
   * detected one.
   *
   * ## The overlay lives exactly as long as the request does
   *
   * `pickedLanguages` is written here and CLEARED here, the moment the answer
   * lands. That is what makes the overlay's lifetime mean something: it is ahead
   * of the outcome precisely while the retranslation is in flight (see
   * `overlayIsAhead`), and it is dropped the instant the outcome that belongs to
   * it arrives. Leaving it set — which is what the previous shape did — made the
   * overlay UNCONDITIONAL and permanent, so the bar kept rendering the pair the
   * reader picked even after a resolution had answered with a DIFFERENT one, and
   * the message beside it described the outcome. Two pairs, one panel.
   *
   * The OVERLAY holds the whole pair — it is what the bar renders, and the bar
   * renders two sides. The WRITE is a different question and is the caller's:
   * this function forwards `next` verbatim, `changed` included, so only the side
   * the reader moved is persisted as a choice.
   */
  async function handleLanguagesChange(next: {
    source: string | null;
    target: string | null;
    changed: 'source' | 'target';
  }): Promise<void> {
    pickedLanguages = { source: next.source, target: next.target };
    await onChangeLanguages(next);
    if (!selectionData || !selectionData.text.trim()) {
      // Nothing will replace the outcome, so the overlay has no answer to yield
      // to. Dropping it would snap the bar back to a pair the reader just moved
      // away from; keeping it is the honest "this is what you picked".
      return;
    }

    translating = true;
    translationActive = true;
    try {
      translation = await onTranslate(selectionData);
    } finally {
      translating = false;
      translationActive = false;
      // The outcome now belongs to the pair the reader picked, so the overlay has
      // nothing left to say. Clearing it HERE and not before is what keeps the
      // bar, the message and the payload reading the same value from the same
      // place. `no-result` / `unavailable` carry no pair of their own, so this
      // also hides the bar rather than leaving it advertising a pair the request
      // did not produce.
      pickedLanguages = null;
    }
  }

  /**
   * Define toggles a result panel, mirroring `handleTranslate`. The limit is
   * enforced by the button's `disabled`, and re-checked here because a keyboard
   * activation and a click on a stale render both reach this function — the
   * guard is cheap and the request is not.
   *
   * A rejection sets `definitionFailed` and leaves `definition` untouched, so
   * "could not reach the dictionary" never renders as "no definition".
   */
  async function handleDefine(): Promise<void> {
    if (definition || definitionFailed) {
      definition = null;
      definitionFailed = false;
      return;
    }
    if (!selectionData || !canDefine) return;

    defining = true;
    try {
      definition = await onDefine(selectionData);
    } catch {
      definitionFailed = true;
    } finally {
      defining = false;
    }
  }

  function closeDefinition(): void {
    definition = null;
    definitionFailed = false;
  }
</script>

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
  <div class={actionsRowClass} role="toolbar" aria-label={t('highlight.menuAriaLabel')}>
    <!-- Color row. Expanded (five circles) is the default; collapsed is one
         swatch that opens the palette, which is the same trade the highlight
         context menu makes with its multicolor `+` trigger. Both branches
         render every color, so a narrow window loses screen space, not actions.
         `key` is the HIGHLIGHT_COLORS index — the palette is a constant and is
         not reordered, so the index identifies an entry as well as its hex. -->
    {#if !toolbarCollapsed}
      <div class="flex items-center gap-3">
        {#each HIGHLIGHT_COLORS as color, index (index)}
          <button
            type="button"
            class="h-6 w-6 cursor-pointer rounded-full transition-transform hover:scale-110"
            class:ring-2={selectedColor === color.hex}
            class:ring-white={selectedColor === color.hex}
            style="background-color: {color.hex};"
            onclick={() => selectColor(color.hex)}
            aria-label={t('highlight.selectColor', { color: t(color.i18nKey) })}
          ></button>
        {/each}
      </div>
    {:else}
      <div class="group relative">
        <button
          type="button"
          bind:this={pickerAnchor}
          class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full shadow-inner ring-2 ring-(--color-text-inverse) transition-transform hover:scale-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
          style="background-image: linear-gradient(135deg, var(--color-error) 0%, var(--color-success) 50%, var(--color-accent-start) 100%);"
          onclick={togglePicker}
          aria-label={t('highlight.changeColor')}
          aria-expanded={pickerOpen}
          title={t('highlight.changeColor')}
        >
          <svg
            class="h-3 w-3 text-white drop-shadow-[0_1px_1px_rgba(0,0,0,0.35)]"
            viewBox="0 0 7 7"
            fill="currentColor"
            aria-hidden="true"
          >
            <path d="M3 4l-3 0 0-1 3 0 0-3 1 0 0 3 3 0 0 1-3 0 0 3-1 0 0-3 0 0" />
          </svg>
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
            class="fixed z-[60] flex items-center gap-2 rounded-full border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) p-2 shadow-xl"
            style="left: {pickerLeft}px; top: {pickerTop}px;"
            role="group"
            aria-label={t('highlight.menuAriaLabel')}
          >
            {#each HIGHLIGHT_COLORS as color, index (index)}
              <button
                type="button"
                class="h-6 w-6 cursor-pointer rounded-full transition-transform hover:scale-110"
                class:ring-2={selectedColor === color.hex}
                class:ring-white={selectedColor === color.hex}
                style="background-color: {color.hex};"
                onclick={() => selectFromPicker(color.hex)}
                aria-label={t('highlight.selectColor', { color: t(color.i18nKey) })}
              ></button>
            {/each}
          </div>
        {/if}
      </div>
    {/if}

    <!-- Separator -->
    <span class="text-base font-normal text-(--color-text-auxiliary)">|</span>

    <!-- Copy -->
    <div class="group relative">
      <button
        type="button"
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
        onclick={handleCopy}
        aria-label={t('reader.copiar')}
        title={t('reader.copiar')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
        </svg>
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

    <!-- Add to Dictionary -->
    <div class="group relative">
      <button
        type="button"
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
        onclick={handleAddToDictionary}
        aria-label={t('reader.addToDictionary')}
        title={t('reader.addToDictionary')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"></path>
          <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"></path>
          <path d="M6 8h2"></path>
          <path d="M6 12h2"></path>
          <path d="M16 8h2"></path>
          <path d="M16 12h2"></path>
        </svg>
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

    <!-- Translate -->
    <div class="group relative">
      <button
        type="button"
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky) {translationHighlight}"
        onclick={handleTranslate}
        disabled={translating}
        aria-label={t('reader.translate')}
        title={t('reader.translate')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="M4 5h12"></path>
          <path d="M10 5v2c0 4-2.5 7-6 9"></path>
          <path d="M6 11c1.5 2.5 4 4 7 4.5"></path>
          <path d="M14 21l4-9 4 9"></path>
          <path d="M15.5 18h5"></path>
        </svg>
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
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky) disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent {definitionActive}"
        onclick={handleDefine}
        disabled={defining || !canDefine}
        aria-label={t('reader.define')}
        title={t('reader.define')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
          <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
          <path d="M9 7h6"></path>
          <path d="M9 11h6"></path>
        </svg>
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
  </div>

  <!-- Action feedback (copy / dictionary). The panel spans the pill it belongs
       to, which is the fixed placement width in both layouts.

       It renders under `shortFeedbackOpen` and NOT under the panel-budget
       condition: the message still appears exactly as it always did, it simply
       no longer reserves room in the placement (see `reservesPanelSpace`), so it
       no longer moves the menu on every press. Its own ceiling is the room the
       pill leaves below itself, so a selection at the bottom edge cannot push the
       message off the window. -->
  {#if shortFeedbackOpen}
    <div
      class="mt-2 overflow-hidden rounded-lg bg-(--color-highlight-menu-bg) text-center text-xs text-(--color-text-inverse) shadow-lg"
      style="width: {placementMenuSize.width}px; max-height: {feedbackPanelMaxHeight}px;"
      role="status"
      aria-live="polite"
    >
      <p class="px-3 py-1">{copyFeedback ?? dictionaryFeedback}</p>
    </div>
  {/if}

  <!-- Translation result. `no-result` (echo / entity / untranslatable) and
       `unavailable` (offline / quota / HTTP) are shown apart on purpose: one
       is the service answering, the other is the service failing. -->
  {#if translating || translation}
    <div
      class="mt-2 overflow-y-auto rounded-lg bg-(--color-highlight-menu-bg) px-4 py-3 text-(--color-text-inverse) shadow-lg"
      style="width: {resultPanelWidth}px; max-height: {resultPanelMaxHeight}px;"
      role="status"
      aria-live="polite"
    >
      {#if translating}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translating')}</p>
      {:else if translation?.status === 'ok'}
        <div class="flex items-start gap-2">
          <p class="min-w-0 grow text-sm wrap-break-word">{translation.text}</p>
          <button
            type="button"
            class="shrink-0 cursor-pointer rounded p-0.5 text-sm leading-none font-medium text-(--color-text-auxiliary) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
            onclick={() => {
              translation = null;
              pickedLanguages = null;
            }}
            aria-label={t('reader.dismissTranslation')}
            title={t('reader.dismissTranslation')}
          >
            ✕
          </button>
        </div>
      {:else if translation?.status === 'no-result'}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translationNoResult')}</p>
      {:else if translation?.status === 'unavailable'}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translationUnavailable')}</p>
      {:else if translation?.status === 'refused'}
        <!-- The message is drawn from the outcome ONLY, and that is why it is
             suppressed while the overlay is ahead of it. A refusal describes a
             pair the RESOLVER refused, while the bar beside it would be showing
             the pair the reader just picked — so for the interval between the
             change and the answer the two describe DIFFERENT pairs, and the
             message reads as a contradiction ("source and target are the same")
             under a bar that plainly shows two different languages. Measured on
             the running app: the bar read `Español → Inglés` while the message
             said both sides were the same. Nothing can be rendered that makes a
             stale outcome consistent with a fresh pair, so nothing is rendered:
             the panel is already showing "translating", which is the truth.
             The moment the request resolves, `pickedLanguages` still holds the
             pair and the outcome now belongs to it, so the message and the bar
             are consistent by construction. -->
        {#if !overlayIsAhead}
          <p class="text-xs text-(--color-text-auxiliary)">
            {t(translationRefusalKey(translation.reason, translation.sourceOrigin))}
          </p>
        {/if}
      {/if}
      {#if !translating && shownLanguages}
        <div class="mt-2">
          <TranslationLanguageBar
            source={shownLanguages.source}
            target={shownLanguages.target}
            resolvedSource={resolvedLanguages?.source ?? null}
            resolvedTarget={resolvedLanguages?.target ?? null}
            sourceOrigin={shownSourceOrigin}
            {t}
            onChange={handleLanguagesChange}
          />
        </div>
      {/if}
    </div>
  {/if}

  <!-- Definition result. The attribution is rendered on EVERY branch including
       the empty one: the backend deliberately carries it on a miss too, because
       the CC BY-SA license requires it accompany the reused text and the UI
       cannot be trusted to reconstruct it. -->
  {#if defining || definition || definitionFailed}
    <div
      class="mt-2 overflow-y-auto rounded-lg bg-(--color-highlight-menu-bg) px-4 py-3 text-(--color-text-inverse) shadow-lg"
      style="width: {resultPanelWidth}px; max-height: {resultPanelMaxHeight}px;"
      role="status"
      aria-live="polite"
    >
      {#if defining}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.defining')}</p>
      {:else if definitionFailed}
        <div class="flex items-start gap-2">
          <p class="min-w-0 grow text-xs text-(--color-text-auxiliary)">
            {t('reader.definitionUnavailable')}
          </p>
          <button
            type="button"
            class="shrink-0 cursor-pointer rounded p-0.5 text-sm leading-none font-medium text-(--color-text-auxiliary) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
            onclick={closeDefinition}
            aria-label={t('reader.dismissDefinition')}
            title={t('reader.dismissDefinition')}
          >
            ✕
          </button>
        </div>
      {:else if definition}
        <div class="flex items-start gap-2">
          <div class="min-w-0 grow">
            {#if definition.definitions.length === 0}
              <p class="text-xs text-(--color-text-auxiliary)">
                {t('reader.definitionNoResult')}
              </p>
            {:else}
              <!-- The machine-translation mark. Not cosmetic: a translated gloss
                   is usually right and wrong in ways the reader cannot see, so
                   the reader is told which one they got. `native` needs no mark
                   — a human wrote it in the reader's own language. -->
              {#if definition.source === 'translated'}
                <p class="mb-1 text-xs text-(--color-text-auxiliary)">
                  {t('reader.definitionTranslated')}
                </p>
              {/if}
              <ul class="flex flex-col gap-1">
                {#each definition.definitions as item}
                  <li class="text-sm wrap-break-word">
                    {#if item.partOfSpeech}
                      <span
                        data-definition-pos
                        class="mr-1 text-xs italic text-(--color-text-auxiliary)"
                      >
                        {item.partOfSpeech}
                      </span>
                    {/if}
                    {item.text}
                  </li>
                {/each}
              </ul>
            {/if}
            <p class="mt-1 text-[0.65rem] text-(--color-text-auxiliary)">
              {definition.attribution}
            </p>
          </div>
          <button
            type="button"
            class="shrink-0 cursor-pointer rounded p-0.5 text-sm leading-none font-medium text-(--color-text-auxiliary) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)"
            onclick={closeDefinition}
            aria-label={t('reader.dismissDefinition')}
            title={t('reader.dismissDefinition')}
          >
            ✕
          </button>
        </div>
      {/if}
    </div>
  {/if}
</div>
