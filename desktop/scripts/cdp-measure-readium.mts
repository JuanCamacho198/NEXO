/**
 * Prints what the CDP reader spec measures, against the live app, so a passing
 * run can be inspected instead of trusted. The spec itself asserts the same
 * values; this exists so the evidence is visible without editing the spec to
 * add a console.log that would then have to be removed.
 *
 * Run against a LIVE app: `bun run scripts/cdp-measure-readium.mts`
 */
import { chromium } from '@playwright/test';

const browser = await chromium.connectOverCDP('http://127.0.0.1:9222', { isLocal: true });
try {
  let page = null;
  for (const ctx of browser.contexts()) {
    for (const p of ctx.pages()) {
      if (p.url().startsWith('http://127.0.0.1:1420')) page = p;
    }
  }
  if (!page) throw new Error('app page not found');

  let chapter = null;
  for (const f of page.frames()) {
    const has = await f
      .evaluate(() => Boolean(document.getElementById('nexo-reader-overrides')))
      .catch(() => false);
    if (has) {
      chapter = f;
      break;
    }
  }
  if (!chapter) throw new Error('chapter frame not found — open a book first');

  const measured = await chapter.evaluate(() => {
    const bodyStyle = getComputedStyle(document.body);
    const heading = document.querySelector('h1, h2, h3, h4, h5, h6');
    const headingStyle = heading ? getComputedStyle(heading) : null;
    const bodyText = document.body.innerText.trim();
    const readium = {
      dtBreakInside: '',
      dtBreakAfter: '',
      hrBreakAfter: '',
      captionBreakAfter: '',
      addressHyphens: '',
      bodyFontVariantNumeric: bodyStyle.getPropertyValue('font-variant-numeric'),
      tableFontVariantNumeric: '',
      cjkEmFontStyle: '',
      latinEmFontStyle: '',
    };

    const created: HTMLElement[] = [];
    const styleOf = (el: Element) => getComputedStyle(el);
    const mk = <T extends HTMLElement>(tag: string, text = 'probe'): T => {
      const el = document.createElement(tag) as T;
      el.textContent = text;
      document.body.appendChild(el);
      created.push(el);
      return el;
    };

    const dt = mk('dt');
    readium.dtBreakInside = styleOf(dt).getPropertyValue('break-inside');
    readium.dtBreakAfter = styleOf(dt).getPropertyValue('break-after');
    readium.hrBreakAfter = styleOf(mk('hr')).getPropertyValue('break-after');

    const table = document.createElement('table');
    const caption = document.createElement('caption');
    caption.textContent = 'probe';
    table.appendChild(caption);
    const row = document.createElement('tr');
    row.appendChild(document.createElement('td'));
    table.appendChild(row);
    document.body.appendChild(table);
    created.push(table);
    readium.captionBreakAfter = styleOf(caption).getPropertyValue('break-after');
    readium.tableFontVariantNumeric = styleOf(table).getPropertyValue('font-variant-numeric');

    readium.addressHyphens = styleOf(mk('address')).getPropertyValue('hyphens');

    const wrap = document.createElement('span');
    wrap.setAttribute('lang', 'ja');
    const em = document.createElement('em');
    em.textContent = 'probe';
    wrap.appendChild(em);
    document.body.appendChild(wrap);
    created.push(wrap);
    readium.cjkEmFontStyle = styleOf(em).getPropertyValue('font-style');

    readium.latinEmFontStyle = styleOf(mk('em')).getPropertyValue('font-style');

    for (const el of created) el.remove();

    return {
      BODY_TEXT_LEN: bodyText.length,
      BODY_TEXT_HEAD: bodyText.slice(0, 40),
      MEASURED: {
        headingBreakInside: headingStyle?.getPropertyValue('break-inside') ?? null,
        headingBreakAfter: headingStyle?.getPropertyValue('break-after') ?? null,
        headingHyphens: headingStyle?.getPropertyValue('hyphens') ?? null,
        hyphenateCharacter: bodyStyle.getPropertyValue('hyphenate-character'),
        hyphenateLimitChars: bodyStyle.getPropertyValue('hyphenate-limit-chars'),
      },
      READIUM: readium,
    };
  });

  console.log(JSON.stringify(measured, null, 2));
} finally {
  await browser.close();
}
