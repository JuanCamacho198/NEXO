/**
 * U3 evidence probe — not a test, a one-shot measurement.
 *
 * ReadiumCSS-base ships `h1, h2, h3 { line-height: normal }` with the comment
 * "1.5 being too loose with larger font-sizes, we reset headings to normal
 * (which value is 1.125-1.375 for our font-stacks)". NEXO's `h1..h6` default
 * font-size (2em, 1.5em, 1.17em) is well above the 1em that Readium's own
 * comment says leaves `normal` already looser than 1.5.
 *
 * Before adopting that rule, measure what Chromium actually uses for `normal`
 * at those sizes versus what NEXO's `body { line-height: 1.8 }` currently
 * gives them. Run: `bun run scripts/u3-heading-lineheight-probe.mts`.
 */
import { chromium } from '@playwright/test';

const browser = await chromium.launch();
const page = await browser.newPage();

await page.setContent(`<!DOCTYPE html><html><head><style>
  body { line-height: 1.8; font-family: serif; }
</style></head><body>
  <h1>Heading one</h1>
  <h2>Heading two</h2>
  <h3>Heading three</h3>
  <h4>Heading four</h4>
  <p>Body paragraph text used as the 1em control.</p>
</body></html>`);

const measure = () =>
  page.evaluate(() => {
    // `getComputedStyle().lineHeight` returns the *specified* value: for
    // `normal` that is the string "normal", not a length. To get the used
    // value, read the geometry of a single-line box instead — (height -
    // borderTop - borderBottom) with a zeroed margin is the used line-height.
    const probe = (tag: string) => {
      const el = document.querySelector(tag)!;
      const cs = getComputedStyle(el);
      const holder = document.createElement('div');
      const clone = el.cloneNode(true) as HTMLElement;
      clone.style.cssText = 'margin:0;padding:0;border:0;width:200px';
      holder.style.cssText = 'margin:0;padding:0;border:0;position:absolute;left:-9999px';
      holder.appendChild(clone);
      document.body.appendChild(holder);
      const rect = clone.getBoundingClientRect();
      holder.remove();
      return { text: cs.lineHeight, usedPx: Number(rect.height.toFixed(2)) };
    };

    const out: Record<string, unknown> = {};
    for (const tag of ['h1', 'h2', 'h3', 'h4', 'p']) {
      const el = document.querySelector(tag)!;
      const cs = getComputedStyle(el);
      const fontSize = parseFloat(cs.fontSize);
      const { text, usedPx } = probe(tag);
      out[tag] = {
        fontSize,
        computedLineHeight: text,
        usedLineHeightPx: usedPx,
        usedMultiplier: Number((usedPx / fontSize).toFixed(4)),
      };
    }
    return out;
  });

console.log('BASE — inherited from body { line-height: 1.8 }');
console.log(JSON.stringify(await measure(), null, 2));

await page.addStyleTag({ content: 'h1, h2, h3 { line-height: normal; }' });

console.log('AFTER — ReadiumCSS-base `h1, h2, h3 { line-height: normal }`');
console.log(JSON.stringify(await measure(), null, 2));

await browser.close();
