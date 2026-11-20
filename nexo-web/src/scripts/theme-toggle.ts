// The theme toggle. The stored choice is applied before first paint by the one
// inline script that remains in <head>; this module only handles the clicks and
// keeps aria-pressed in sync. There are two buttons (top bar and small-screen
// menu), so every instance is bound.

const root = document.documentElement;
const toggles = Array.from(document.querySelectorAll<HTMLElement>('[data-theme-toggle]'));

if (toggles.length) {
  function sync(): void {
    const pressed = root.dataset.theme === 'dark' ? 'true' : 'false';
    for (const toggle of toggles) toggle.setAttribute('aria-pressed', pressed);
  }

  for (const toggle of toggles) {
    toggle.addEventListener('click', () => {
      const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
      root.dataset.theme = next;
      try {
        localStorage.setItem('nexo-theme', next);
      } catch {
        // Storage unavailable: the choice simply will not persist.
      }
      sync();
    });
  }

  sync();
}

export {};
