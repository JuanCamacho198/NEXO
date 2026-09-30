import { writable } from 'svelte/store';

const STORAGE_KEY = 'nexo-theme';

type Theme = 'dark' | 'light';

function getInitialTheme(): Theme {
  if (typeof localStorage !== 'undefined') {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  }
  return 'dark';
}

function applyThemeToDom(value: Theme): void {
  document.documentElement.setAttribute('data-theme', value);
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(STORAGE_KEY, value);
  }
}

/** Reactive store — use $theme in Svelte components */
export const theme = writable<Theme>(getInitialTheme());

/** Toggle between dark and light, persists to localStorage */
export function toggleTheme(): void {
  theme.update((current) => {
    const next = current === 'dark' ? 'light' : 'dark';
    applyThemeToDom(next);
    return next;
  });
}

/** Call once in onMount to apply the stored theme to the DOM */
export function initTheme(): void {
  theme.subscribe((value) => {
    applyThemeToDom(value);
  });
}
