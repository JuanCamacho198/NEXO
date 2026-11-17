# One clear action per primary task — desktop review

Audit item #20. The product claims five primary tasks. For each, the question is
the same: when a user arrives at that flow, does the screen present **one**
obvious next action, or several that compete?

Verdicts below come from reading the shipped components, not from running the
app. Where a verdict would need a running build to settle, it says so.

| Flow | Verdict | Evidence |
| --- | --- | --- |
| Open a book | **Ambiguous — accepted** | `ShelfGrid.svelte:132-151` |
| Search | **Clear** | `SearchPanel.svelte:152-175` |
| Highlight | **Ambiguous — accepted (product decision)** | `SelectionToolbar.svelte:1003-1200` |
| Import / download | **Clear** | `LibraryShelfScreen.svelte:105-130, 260-268`; `DiscoverDetail.svelte:262-282` |
| Sync | **Ambiguous — fixed** | `SyncView.svelte` (see below) |

---

## 1. Open a book — Ambiguous, accepted

Every shelf card renders **two buttons of equal size, side by side**:
`Open book` (`variant="secondary"`) and `Continue` / `Start`
(`variant="accent"`). See `ShelfGrid.svelte:132-151`.

Clicking the cover itself also opens the book (`:95`), so a third affordance
exists on the same card.

Why it is ambiguous: for a book at 40% progress, "Open book" and "Continue" do the
same thing from the user's point of view — both land them in the reader. The
distinction only means something for an unstarted book ("Start") or a finished
one.

Why it is not fixed here: collapsing the pair is a product decision about what
"open" means for a partly-read book, and the two handlers are wired to different
parent callbacks (`onOpenBook` vs `onContinueReading`) whose difference is not
visible in this component. Removing one would silently delete a behaviour.

**Proposed decision (for the product owner, not taken):** one primary action per
card whose label follows progress — `Start` → `Continue` → `Read again` — and
make the cover click indistinguishable from it.

## 2. Search — Clear

`SearchPanel.svelte` is a drawer with one submit path: a `<form>` with a single
submit button and `Enter` bound to it (`:152-175`). Pagination appears only after
results exist, and "Next page" is the only secondary action in the results state.

No competing primary. Nothing to change.

## 3. Highlight — Ambiguous, accepted (product decision)

`SelectionToolbar.svelte:1003` renders `role="toolbar"` as a single row
containing, at the default (expanded) width:

- five colour buttons (`:1013-1021`),
- copy, add-to-dictionary, translate, define (`:1081-1198`).

That is **nine actions in the toolbar** for one selected sentence, with five
colours carrying equal visual weight and no marked default. The intent is
defensible — each colour is a separate deliberate act — but "highlight this" has
no single entry point: the user must choose a colour before the highlight exists.

Why it is not fixed here: any fix is a design decision (a default colour plus a
palette button, or a split button), it touches a 1,394-line component that is the
reader's most performance-sensitive surface, and it cannot be validated without
running the reader on a real book. Narrowing the toolbar is not a "small and
obvious" fix.

**Proposed decision:** default colour as the primary button, palette behind the
existing `changeColor` affordance (`:1026-1034`), which is already built.

## 4. Import / download — Clear

Two entry points, one action each, and they never appear together:

- Library: a single `importAction` snippet (`LibraryShelfScreen.svelte:105-114`)
  used in the header (`:129`) and, in the empty state, as the sole call to action
  (`:260-268`). Same handler, so the two instances are the same button in two
  places, not two competing ones.
- Discover: one button per state — `Download` while available
  (`DiscoverDetail.svelte:271-282`), a progress bar plus cancel while
  downloading, `Open book` once in the library (`:262-270`).

No state presents two primary actions for the same task.

## 5. Sync — Ambiguous, **fixed in this change**

In the error state, `SyncView.svelte` rendered **two buttons wired to the same
`handleSyncNow` handler**: a ghost `Retry` and an enabled secondary
`Sync now`. Two labels, one behaviour — the screen asked the user to choose
between two identical outcomes. The primary weight also sat on the wrong one:
the standing manual action looked more important than the recovery step.

Fix: in the error state, `Retry` is `variant="primary"` (the recovery step) and
`Sync now` is demoted to `secondary` and disabled while a sync is in flight. The
non-error state is unchanged (one secondary `Sync now`). Every button now carries
`data-action-emphasis="primary|secondary"`, so the intent is asserted rather than
implied by colour.

Locked by `src/test/unit/sync/SyncView.test.ts` — "one primary action on
failure: retry is primary, manual sync steps back" (observed RED before the
change: the attribute did not exist).

---

## Summary

- 1 flow fixed (sync) — small, testable, no redesign.
- 2 flows clear as shipped (search, import/download).
- 2 flows genuinely ambiguous (open a book, highlight) and left as **explicit
  product decisions** rather than silently reworked.
