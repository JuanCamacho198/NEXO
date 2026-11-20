// The header's only client behaviour: hide-on-scroll-down, the dark-section
// theme flip under the floating bar, and the scroll-spy dot.
//
// The point of this file is the ORDER of reads and writes. The browser merges
// every style write into one layout pass, so a loop that reads geometry after
// writing an attribute forces a synchronous reflow per iteration — that is the
// 124 ms "forced reflow" Lighthouse used to report at 4x CPU throttling. So:
//
//   1. read window.scrollY (cheap, no layout)
//   2. build the list of attribute writes without touching the DOM
//   3. flush them in one batch
//
// Section geometry is cached instead of measured per frame. The sections are
// static once the page settles, so a ResizeObserver on <body> — which also sees
// the font swap and late content — is the only thing that invalidates the cache.

interface SectionBox {
  el: Element;
  top: number;
  bottom: number;
}

const header = document.querySelector<HTMLElement>('.site-header');

if (header) {
  const HIDE_DEAD_ZONE_PX = 6;
  const ALWAYS_SHOWN_TOP_PX = 40;
  const SPY_LINE_RATIO = 0.4;

  const overHero = header.hasAttribute('data-hero');
  const spyLinks = Array.from(header.querySelectorAll<HTMLElement>('[data-spy]'));

  let ticking = false;
  let lastY = window.scrollY;
  // Geometry, cached in DOCUMENT space. Rebuilt only when the document size or
  // the viewport changes — never from the scroll handler, because measuring
  // after a style write is exactly what forces a synchronous reflow.
  let darkBoxes: SectionBox[] = [];
  let spyEdges: Array<{ link: HTMLElement; top: number }> = [];

  function measure(): void {
    // At document top, getBoundingClientRect().top IS the document offset, so
    // no scroll compensation is needed while nothing has been written yet.
    const boxes: SectionBox[] = [];
    for (const el of document.querySelectorAll('main section[data-theme="dark"]')) {
      const rect = el.getBoundingClientRect();
      boxes.push({ el, top: rect.top, bottom: rect.bottom });
    }
    darkBoxes = boxes;

    spyEdges = [];
    for (const link of spyLinks) {
      const el = document.getElementById(link.getAttribute('data-spy') ?? '');
      if (el) spyEdges.push({ link, top: el.getBoundingClientRect().top });
    }
  }

  function commit(): void {
    const y = window.scrollY;
    const line = header!.offsetHeight;

    // ---- decide (no DOM writes yet) --------------------------------------
    let hidden: boolean | null = null;
    if (y <= ALWAYS_SHOWN_TOP_PX) hidden = false;
    else if (y > lastY + HIDE_DEAD_ZONE_PX) hidden = true;
    else if (y < lastY - HIDE_DEAD_ZONE_PX) hidden = false;
    lastY = y;

    let dark: boolean | null = null;
    if (overHero) {
      dark = false;
      for (const box of darkBoxes) {
        // Document-space box vs the band the bar occupies.
        if (box.top < y + line && box.bottom > y) {
          dark = true;
          break;
        }
      }
    }

    let activeLink: HTMLElement | null = null;
    if (spyEdges.length) {
      const spyLine = y + window.innerHeight * SPY_LINE_RATIO;
      for (const edge of spyEdges) {
        if (edge.top <= spyLine) activeLink = edge.link;
      }
    }

    // ---- write, in one batch ---------------------------------------------
    if (hidden === true) header!.setAttribute('data-hidden', '');
    else if (hidden === false) header!.removeAttribute('data-hidden');

    if (dark === true) header!.setAttribute('data-theme', 'dark');
    else if (dark === false) header!.removeAttribute('data-theme');

    for (const link of spyLinks) {
      if (link === activeLink) link.setAttribute('aria-current', 'location');
      else link.removeAttribute('aria-current');
    }
  }

  function requestUpdate(): void {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(() => {
      ticking = false;
      commit();
    });
  }

  window.addEventListener('scroll', requestUpdate, { passive: true });
  window.addEventListener('resize', () => {
    measure();
    requestUpdate();
  });

  // A resize observer on <body> also catches the font swap and any late content
  // that moves the sections, without polling layout on scroll.
  if ('ResizeObserver' in window) {
    new ResizeObserver(() => {
      measure();
      requestUpdate();
    }).observe(document.body);
  }

  measure();
  commit();
  document.addEventListener('DOMContentLoaded', () => {
    measure();
    commit();
  });
}

export {};
