/**
 * Reader CSS polish, measured against the real app.
 *
 * ## Why this exists
 *
 * `src/test/unit/epub/useEpubRender.test.ts` asserts the reader-override rules
 * are present *in the injected CSS string*. jsdom has no layout engine, so those
 * tests cannot prove a single rule takes effect. This spec closes that gap: it
 * opens a real EPUB from the developer's library in the running app and reads the
 * **computed** style inside the chapter iframe, which is what the CSS engine
 * actually resolved after cascading against the book's own stylesheet.
 *
 * ## Local only
 *
 * Like `smoke.spec.ts`, this needs the real Tauri app started with the WebView2
 * debug port open:
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 *
 * ## Reaching the chapter document
 *
 * The chapter is not in the app document. It lives in an iframe whose `srcdoc`
 * `useEpubRender` assigns (`iframeEl.srcdoc = srcdoc`, `useEpubRender.svelte.ts`),
 * so its document is a same-origin `about:srcdoc` frame. `page.frames()` exposes
 * that frame to Playwright; `page.frameLocator()` also works but is scoped to a
 * locator, and here we need to run `evaluate` against the whole document, so the
 * spec enumerates `page.frames()` and keeps the one that carries the injected
 * reader stylesheet (id `nexo-reader-overrides`) — a marker that cannot appear
 * in the app document or in the inspector frontend.
 *
 * ## What it proves, and what it does not
 *
 * It proves the fragmentation rules (`break-inside`/`break-after`) and the
 * hyphenation limits resolve to the values the builder emits. It does NOT prove
 * the visual outcome: `break-inside: avoid` on a heading only *changes* anything
 * when the heading would otherwise straddle a fragmentation boundary, which in a
 * single tall scrolled iframe never happens. So this is a computed-style proof,
 * not a "the heading no longer splits" screenshot proof. Ruby/CJK/math have the
 * same limit — a real Japanese or MathML book is needed to see them render, and
 * the library this runs against rarely has one.
 */
import { expect, test } from './harness/connectApp';
import type { Frame, Page } from '@playwright/test';

/** Subset of the Rust `listLibraryBooks` row needed to pick a readable EPUB. */
type LibraryRow = {
  id: string;
  title: string;
  format?: string;
  coverPath?: string | null;
};

/**
 * Returns the chapter frame: the one whose document carries the reader-override
 * `<style id="nexo-reader-overrides">` injected by `buildChapterSrcdoc`. We poll
 * because the frame is created, then its `srcdoc` parsed, asynchronously after
 * the click.
 */
async function waitForChapterFrame(page: Page): Promise<Frame> {
  const deadline = Date.now() + 30_000;

  while (Date.now() < deadline) {
    for (const frame of page.frames()) {
      const hasOverrides = await frame
        .evaluate(() => Boolean(document.getElementById('nexo-reader-overrides')))
        .catch(() => false);
      if (hasOverrides) return frame;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  const urls = page.frames().map((frame) => frame.url());
  throw new Error(
    `No chapter frame with #nexo-reader-overrides appeared within 30s. ` +
      `Frames seen: ${urls.length > 0 ? urls.join(', ') : '(none)'}.`,
  );
}

test('the injected reader CSS is applied inside the real chapter iframe', async ({ appPage }) => {
  // The book open path runs a real Rust extraction plus a chapter render; 30s
  // (the default) is not enough on a cold cache.
  test.setTimeout(180_000);

  // Pick a real EPUB from the user's library. `format` is only trustworthy from
  // the Rust side, so ask it rather than guessing from the title.
  //
  // A library can hold *sync stubs* — rows synced from another device that have
  // no local file. Those never render (extraction has nothing to unzip), so
  // preferring an EPUB that carries a `coverPath` skips them: the cover is only
  // written by a real local import. If every EPUB is a stub, the spec proceeds
  // and fails honestly at the chapter-frame wait rather than skipping green.
  const epub = await appPage.evaluate(async (): Promise<LibraryRow | null> => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;

    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');

    const rows = (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as LibraryRow[];

    const epubs = rows.filter((row) => (row.format ?? '').toLowerCase() === 'epub');
    return epubs.find((row) => Boolean(row.coverPath)) ?? epubs[0] ?? null;
  });

  // No EPUB in this library: skip loudly instead of inventing a fixture. A green
  // run on an empty library would be a lie; a skip is honest.
  test.skip(epub === null, 'No EPUB book in the library — cannot measure the chapter frame.');

  // Normalise the starting screen. A previous run may have left the app in the
  // reader, where the sidebar does not exist, and the rest of this spec needs a
  // navigable screen. If a chapter iframe is present we are in the reader; the
  // reader top bar is a `<header>` whose first button is "back" (`onBackToHome`
  // in `ReaderTopBar.svelte`). Its label is i18n, so target position, not text.
  if ((await appPage.locator('iframe[title="chapter"]').count()) > 0) {
    await appPage.locator('header button').first().click();
    await expect(appPage.locator('aside nav > button').first()).toBeVisible({ timeout: 20_000 });
  }

  // The sidebar nav is built from a model whose button labels come from the
  // app's i18n, so a hard-coded English name would fail on a Spanish UI. Match
  // the *label* for the library route across the locales this repo ships
  // (`sidebar.library`: "Library" / "Estantería"). If the label model changes,
  // the fallback below still finds the card on the home screen.
  const libraryNav = appPage.locator('aside nav').getByRole('button', {
    name: /^(Library|Estanter[ií]a)$/,
  });
  if ((await libraryNav.count()) > 0) {
    await libraryNav.first().click();
  }

  // `BookCard` renders an `<article>` labelled `"{title}, {author}, {progress}%"`,
  // both on the home recent rail and on the shelf grid, so this locator is
  // stable across both screens.
  const card = appPage.locator('article').filter({ hasText: epub!.title }).first();
  await expect(card).toBeVisible({ timeout: 30_000 });

  // "Read" / "Continue" (`app.read` / `app.continue`) starts reading. It is the
  // first `<button>` with visible text; the options trigger is icon-only.
  const readButton = card.locator('button').filter({ hasText: /\S/ }).first();
  await readButton.click();

  const chapter = await waitForChapterFrame(appPage);

  // The chapter document must have real content, or every measurement below is
  // vacuous.
  const bodyText = await chapter.evaluate(() => document.body.innerText.trim());
  expect(bodyText.length).toBeGreaterThan(0);

  // Measure the rules the builder emits, as the CSS engine resolved them.
  //
  // Only properties Chromium actually computes are read. `hyphenate-limit-lines`
  // is deliberately NOT measured: a headless-Chromium probe (Playwright, same
  // engine as WebView2) returned an empty string for it *and* for its
  // `-webkit-` form, i.e. the property is not exposed through
  // `getComputedStyle` even when authored. Asserting on it here would be a test
  // that always fails, or worse, one loosened until it passes and proves
  // nothing. `hyphenate-character` and `hyphenate-limit-chars` DO resolve, so
  // the hyphenation group is still measured for real.
  const measured = await chapter.evaluate(() => {
    const bodyStyle = getComputedStyle(document.body);
    const heading = document.querySelector('h1, h2, h3, h4, h5, h6');
    const headingStyle = heading ? getComputedStyle(heading) : null;

    return {
      // S2 — hyphenation limits live on body.
      hyphenateCharacter: bodyStyle.getPropertyValue('hyphenate-character'),
      hyphenateLimitChars: bodyStyle.getPropertyValue('hyphenate-limit-chars'),
      // S1 — headings are atomic in the fragmentation model.
      headingBreakInside: headingStyle?.getPropertyValue('break-inside') ?? null,
      headingBreakAfter: headingStyle?.getPropertyValue('break-after') ?? null,
      // S2 — the wrong-element opt-out.
      headingHyphens: headingStyle?.getPropertyValue('hyphens') ?? null,
      headingCount: document.querySelectorAll('h1, h2, h3, h4, h5, h6').length,
    };
  });

  // S2: the limits the engine can report resolved to the authored values.
  expect(measured.hyphenateCharacter).toBe('"-"');
  expect(measured.hyphenateLimitChars).toBe('6 3 3');

  // S1 + S2: at least one heading exists and carries the atomic fragment rules
  // plus the hyphens opt-out. `break-inside` computes to `avoid`, which is the
  // value the injected rule sets — proof the rule beat the publisher stylesheet.
  expect(measured.headingCount).toBeGreaterThan(0);
  expect(measured.headingBreakInside).toBe('avoid');
  expect(measured.headingBreakAfter).toBe('avoid');
  expect(measured.headingHyphens).toBe('none');

  // ── U3 — the readium-css rules, measured in the same real chapter ──────────
  //
  // The library book is picked by the user's shelf, so it may not contain a
  // `dt`, a `table` or any CJK text. Rather than skip, the probe *creates* the
  // elements it needs inside the chapter document and reads the computed style:
  // the stylesheet is already applied to that document, so a fresh element
  // cascades against the same rules a book's own element would. That is what
  // makes these assertions honest — they exercise the real CSS engine against
  // the real injected stylesheet, not a fixture.
  const readium = await chapter.evaluate(() => {
    const created: HTMLElement[] = [];
    const probe = <T extends HTMLElement>(tag: string, configure?: (el: T) => void) => {
      const el = document.createElement(tag) as T;
      el.textContent = 'probe';
      configure?.(el);
      document.body.appendChild(el);
      created.push(el);
      const style = getComputedStyle(el);
      return {
        breakInside: style.getPropertyValue('break-inside'),
        breakAfter: style.getPropertyValue('break-after'),
        hyphens: style.getPropertyValue('hyphens'),
        fontVariantNumeric: style.getPropertyValue('font-variant-numeric'),
        fontStyle: style.getPropertyValue('font-style'),
      };
    };

    const result = {
      // R1a — dt gets both break rules; hr and caption get break-after.
      dt: probe('dt'),
      hr: probe('hr'),
      caption: probe('caption'),
      // R1b — the widened hyphenation opt-out.
      address: probe('address'),
      // R2 — OTF numeric variants.
      body: (() => {
        const s = getComputedStyle(document.body);
        return s.getPropertyValue('font-variant-numeric');
      })(),
      table: probe('table'),
      // R3 — CJK emphasis is upright. `:lang(ja)` needs the attribute on the
      // `em` itself or on an ancestor, so the element is created INSIDE a
      // `lang="ja"` wrapper and never reparented afterwards: removing and
      // re-appending it (as an earlier version of this probe did) detaches it
      // from the language context and the rule stops matching, which is a
      // mistake in the probe, not in the stylesheet.
      cjkEm: (() => {
        const wrap = document.createElement('span');
        wrap.setAttribute('lang', 'ja');
        const em = document.createElement('em');
        em.textContent = 'probe';
        wrap.appendChild(em);
        document.body.appendChild(wrap);
        created.push(wrap);
        const style = getComputedStyle(em);
        return {
          breakInside: style.getPropertyValue('break-inside'),
          breakAfter: style.getPropertyValue('break-after'),
          hyphens: style.getPropertyValue('hyphens'),
          fontVariantNumeric: style.getPropertyValue('font-variant-numeric'),
          fontStyle: style.getPropertyValue('font-style'),
        };
      })(),
      // Control: a Latin `<em>` must stay italic, proving the CJK rule is scoped
      // and not a global `font-style: normal`.
      latinEm: (() => {
        const em = document.createElement('em');
        em.textContent = 'probe';
        document.body.appendChild(em);
        created.push(em);
        return { fontStyle: getComputedStyle(em).getPropertyValue('font-style') };
      })(),
    };

    for (const el of created) el.remove();
    return result;
  });

  // R1a: the fragmentation Readium adds on top of S1.
  expect(readium.dt.breakInside).toBe('avoid');
  expect(readium.dt.breakAfter).toBe('avoid');
  expect(readium.hr.breakAfter).toBe('avoid');
  expect(readium.caption.breakAfter).toBe('avoid');

  // R1b: the widened opt-out.
  expect(readium.address.hyphens).toBe('none');

  // R2: the numeric variants the engine resolved.
  expect(readium.body).toContain('oldstyle-nums');
  expect(readium.table.fontVariantNumeric).toContain('tabular-nums');

  // R3: CJK emphasis is upright rather than synthesised-oblique.
  expect(readium.cjkEm.fontStyle).toBe('normal');
  // …and the rule is scoped: Latin emphasis keeps its italic.
  expect(readium.latinEm.fontStyle).toBe('italic');
});
