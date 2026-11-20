// <details> gives the language menu its toggle for free, but it cannot be
// dismissed with Escape or a click outside. One pass over every instance covers
// both the top bar and the small-screen menu, which is why this lives here and
// not inside LanguagePicker.astro (which would emit it twice per page).
//
// Dismissal stays optional: without this module the menu still opens and its
// links still work.

const pickers = Array.from(document.querySelectorAll<HTMLDetailsElement>('.lang-picker'));

if (pickers.length) {
  function closeAll(): void {
    for (const picker of pickers) picker.open = false;
  }

  // Capturing, so the panel is already closed by the time the click lands
  // anywhere else. A click on a link inside a menu also closes it, so going
  // back does not return to a page with a stale open panel.
  document.addEventListener(
    'click',
    (event) => {
      const target = event.target as Node | null;
      for (const picker of pickers) {
        if (!target || !picker.contains(target) || (target as Element).closest?.('a')) {
          picker.open = false;
        }
      }
    },
    true,
  );

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeAll();
  });
}

export {};
