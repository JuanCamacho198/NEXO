# Reader and Sync Contract

**Status:** living document. Sections marked _unconfirmed_ are placeholders, not
decisions. Nothing here is a decision until the reader has confirmed it.

**Scope:** desktop and Android. Both apps ship these behaviours. Where the two
implementations differ, that is a defect in the app that diverges, not a licence
for two contracts.

## How to use this document

This is the answer to "what is it supposed to do?", which until now lived in one
person's memory. That is why defects have been discovered by using the app rather
than by reading a document, and why a suite of 3031 passing unit tests coexists
with defects that nobody had recorded.

A behaviour gets written here before it is implemented, not after. When an app
diverges from a line here, the line is the requirement and the app is the bug —
unless the reader changes the line deliberately.

## The governing rule

**Target: these behaviours work ~95% of the time. When one fails, the failure is
logged.**

A silent failure is worse than a visible one, because nobody can act on it. Every
defect found in the session preceding this document was silent:

- A Rust command that does not exist (`indexEpubText`) raised a dialog only when
  debug mode was on, and nothing when it was off.
- The "Available from other devices" shelf section disappears entirely when its
  list is empty — including the error banner, which renders *inside* the same
  conditional. Failures to load are stored and never shown.
- The reader's page field can move and be pulled back by the restore, roughly one
  open in six, with nothing recording that it happened.

So: when something fails, log it. Not every failure needs to block the reader;
every failure needs to be findable.

---

## Part 1 — Reading position

### 1.1 Resume where the reader stopped

Closing a book and reopening it returns to the same place. This holds across app
restarts and across devices.

_Confirmed. This is the product's central promise (PRODUCT.md: "Success means a
reader can return to the product and continue from the correct place")._

### 1.2 The position is stored once and restored once

**One subsystem owns the reading position.** Today two do: the CFI restore writes
the scroll offset, and a page turn writes the same scroll offset, with no
arbitration between them.

**Measured consequence:** a turn pressed while the restore is still in flight
lands at the restore target instead of one page forward (`0 → 23310`). Roughly
one open in six, the page field moves and is then pulled back.

This is not a pagination defect. It reproduces in scrolling mode with pagination
off.

_Confirmed as a defect. The requirement is that a single owner exists; the fix is
not specified here._

### 1.3 Position survives a layout change

Changing zoom, font size, window size or orientation keeps the reader on the same
text, not the same page number.

_Unconfirmed in detail — the reader has not exercised this. The principle is
recorded so it is not forgotten._

---

## Part 2 — Selecting text and highlighting

### 2.1 This is the primary interaction

Asked which feature is used most, the reader answered: **selecting text and
giving it a colour.** Everything in this part carries more weight than the rest of
the document.

### 2.2 The selection menu closes when the selection ends

The menu must close when:

| Action | Required |
|---|---|
| Tapping/clicking outside the selected text | menu closes, selection clears |
| Tapping/clicking inside the book but not selecting | menu closes, selection clears |
| Scrolling | menu closes, selection clears |
| Navigating — chapter, page, book | menu closes, selection clears |

**Desktop currently fails the first three.** The reader's report: after selecting
text and clicking elsewhere, the menu sometimes stays on screen.

**Android passes all of them.** Measured against the live app: 0 failures in 5
trials across tapping outside, repeating twice, and scrolling.

**Requirement:** desktop behaves as Android does. The correct behaviour already
exists in the product; it does not need to be designed.

### 2.3 Applying a colour never reopens the menu

After a colour is applied, the menu is gone.

**Desktop fails this intermittently** — the colour applies, and the menu comes
back.

**Android passes.** Measured: the menu closes and the selection clears.

### 2.4 A colour is applied and persisted

Choosing a colour marks the text with that colour, and the mark survives closing
and reopening the book.

**Both apps pass.** Desktop confirmed by the reader; Android confirmed against the
device database — two highlights present with colours `#EF4444` and `#3B82F6`.

Worth recording because an automated run reported the Android colour as "not
applied". It was applied. The database is the authority, not a screenshot.

### 2.5 Highlights for a book that is not here are not listed

The global highlights list shows highlights whose book is present.

**Desktop was failing this** — 11 highlights were listed whose book was absent or
a sync placeholder. Fixed and committed (`f4a8c8a3`).

**Android passes** — it filters per book at the data layer.

---

## Part 3 — The library

### 3.1 Books that are not on this device are offered for download

A book present in the reader's account but not downloaded to this device appears
in the library under "Available from other devices", with its cover, title,
author, format and a Download button. Downloading it brings the book into the
local library with its highlights.

The section carries a count badge and closes itself when the list is empty.

_Confirmed by the reader as expected behaviour, and described as something that
used to appear and no longer does._

**The code exists and is complete:** `ShelfDownloadsSection.svelte` renders the
header, count, cover, title, author, format badge, Download button, spinner and
error banner; `downloadableCatalog.loadAvailableFromDrive()` populates it from
Drive. It is not missing.

**It does not appear because the list is empty.** Known failure points, all
currently silent:

- `gdrive.list('')` — the listing call. The store's own documentation says it
  reads `Nexo/Books/`; the argument is empty.
- `parseCanonicalBookName(name)` returning null — such files are skipped without
  comment, so a naming mismatch in Drive silently yields nothing.
- The book being filtered out as already-local, when the local id differs from
  the Drive id.

**And the error banner is unreachable.** It renders inside
`{#if downloadableCatalog.books.length > 0}` (line 36), so when the load fails
and the list stays empty, the error it stored is never displayed. The section
disappears without saying why.

This is the governing rule's clearest example: the failure is stored and never
shown.

### 3.2 Sync placeholders are never shown as books

A placeholder created to hold a highlight whose book is absent — titled
`Unknown Title (sync stub) …`, author `Unknown`, no file — must never appear in
the library as a book.

**Desktop:** no longer created. Highlights for an absent book are deferred and
applied when the book arrives (`f4a8c8a3`). Existing placeholders are a data
question, not a code one.

**Android:** the placeholder mechanism and its visibility are _unconfirmed_.

### 3.3 Removing a book from the device keeps it in the library

A book can be removed from this device without leaving the account, and offered
for download again. The existing copy states this exactly.

_Confirmed as existing behaviour._

---

## Part 4 — Synchronisation

_Unconfirmed. The reader's position: sync becomes the priority once the reader is
finished, and the questions below have not been exercised._

Recorded so the gaps are visible rather than forgotten:

- Highlights made on one device appear on the other.
- Reading position made on one device appears on the other.
- How long propagation takes, and whether it requires reopening the app.
- What happens when the same text is highlighted on both devices.
- What happens when the same book is open on both devices.
- Whether a highlight has ever been lost or duplicated.

The reader reports that highlights have moved between devices without
investigation, and has not observed a loss. No timing has been measured.

---

## Part 5 — What this document does not cover

- **Library management, discovery, search, settings, statistics.** Not in the
  reader's scope for now.
- **Pagination.** Off by default, with two defects recorded separately in
  `odd/tasks/epub-pagination.md`. Cross-chapter page numbers are not part of it.
- **Android's reader internals.** Its known architectural problem —
  `ReadiumReaderContent.kt` at 627 lines with 13 effects and a 300 ms selection
  poll — is recorded in Engram `#2308` and is not a behaviour contract.

---

## Confirmed defects, by app

Recorded here so neither is lost. Neither is fixed by this document.

| # | Behaviour | Desktop | Android |
|---|---|---|---|
| 2.2 | Menu closes when the selection ends | **Fails** (outside click, inside click, scroll) | Passes |
| 2.3 | Applying a colour does not reopen the menu | **Fails intermittently** | Passes |
| 1.2 | A single owner of the reading position | **Fails** — affects scrolling mode too | Unconfirmed |
| 3.1 | Cloud books are offered for download | **Section exists, list empty, error hidden** | Unconfirmed |
| 3.2 | Sync placeholders are never shown | Fixed | Unconfirmed |

**Android is ahead of desktop on selection.** It is the reference for 2.2 and 2.3.

## Open questions

1. Why does `loadAvailableFromDrive()` return nothing when Drive is connected?
2. Does Android create sync placeholders, and are they visible in the library?
3. Does Android's download section populate?
4. Does the same text highlighted on two devices merge, duplicate, or conflict?
5. How long does sync take, and does it need a reopen?
