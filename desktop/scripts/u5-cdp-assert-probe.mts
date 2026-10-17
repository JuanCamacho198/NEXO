/**
 * U5 pre-flight — validate the CDP spec's assumptions in the SAME engine
 * (the Playwright Chromium that matches WebView2), before paying for the slow
 * `tauri:dev:debug` cycle.
 *
 * It reproduces the injected R1/R2/R3 rules verbatim and checks that each
 * property the spec asserts on actually resolves through `getComputedStyle`.
 * A property that returns an empty string would make the CDP assertion either
 * always-fail or vacuously-pass, which is exactly the trap this avoids.
 *
 * Run: `bun run scripts/u5-cdp-assert-probe.mts`
 */
import { chromium } from '@playwright/test';

const RULES = `
  body h2, body h3, body h4, body h5, body h6, body dt, body hr, body caption {
    break-after: avoid !important;
  }
  body dt { break-inside: avoid !important; }
  body code, body pre, body figcaption, body caption, body var,
  body dt, body address, body center { hyphens: none !important; }
  body { font-variant-numeric: oldstyle-nums proportional-nums; }
  body table { font-variant-numeric: lining-nums tabular-nums; }
  body h1, body h2, body h3, body h4, body h5, body h6, body dt {
    font-variant-numeric: lining-nums proportional-nums;
  }
  body :lang(ja) :is(em, i, cite, dfn),
  body :lang(zh) :is(em, i, cite, dfn),
  body :lang(ko) :is(em, i, cite, dfn) { font-style: normal; }
`;

const browser = await chromium.launch();
const page = await browser.newPage();
await page.setContent(`<!DOCTYPE html><html><head><style>${RULES}</style></head><body>
  <h1>h1</h1>
  <dl><dt>dt</dt></dl>
  <hr>
  <table><caption>caption</caption><tr><td>cell</td></tr></table>
  <address>address</address>
  <span lang="ja"><em>cjk emphasis</em></span>
  <em id="latin">latin emphasis</em>
</body></html>`);

const out = await page.evaluate(() => {
  const pick = (sel: string) => {
    const el = document.querySelector(sel)!;
    const s = getComputedStyle(el);
    return {
      breakInside: JSON.stringify(s.getPropertyValue('break-inside')),
      breakAfter: JSON.stringify(s.getPropertyValue('break-after')),
      hyphens: JSON.stringify(s.getPropertyValue('hyphens')),
      fontVariantNumeric: JSON.stringify(s.getPropertyValue('font-variant-numeric')),
      fontStyle: JSON.stringify(s.getPropertyValue('font-style')),
    };
  };
  return {
    dt: pick('dt'),
    hr: pick('hr'),
    caption: pick('caption'),
    address: pick('address'),
    table: pick('table'),
    bodyFvn: JSON.stringify(getComputedStyle(document.body).getPropertyValue('font-variant-numeric')),
    cjkEm: pick('span[lang="ja"] em'),
    latinEm: pick('#latin'),
  };
});

console.log(JSON.stringify(out, null, 2));

const failures: string[] = [];
const expectEq = (label: string, actual: string, expected: string) => {
  if (JSON.parse(actual) !== expected) failures.push(`${label}: got ${actual}, want "${expected}"`);
};
expectEq('dt.breakInside', out.dt.breakInside, 'avoid');
expectEq('dt.breakAfter', out.dt.breakAfter, 'avoid');
expectEq('hr.breakAfter', out.hr.breakAfter, 'avoid');
expectEq('caption.breakAfter', out.caption.breakAfter, 'avoid');
expectEq('address.hyphens', out.address.hyphens, 'none');
expectEq('cjkEm.fontStyle', out.cjkEm.fontStyle, 'normal');
if (!JSON.parse(out.bodyFvn).includes('oldstyle-nums')) failures.push(`body font-variant-numeric: ${out.bodyFvn}`);
if (!JSON.parse(out.table.fontVariantNumeric).includes('tabular-nums')) failures.push(`table fvn: ${out.table.fontVariantNumeric}`);
if (!JSON.parse(out.latinEm.fontStyle).includes('italic')) failures.push(`latin em should stay italic: ${out.latinEm.fontStyle}`);

await browser.close();

if (failures.length > 0) {
  console.error(`\nU5 pre-flight FAILED (${failures.length}):`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log('\nU5 pre-flight: all assertions resolve as expected.');
