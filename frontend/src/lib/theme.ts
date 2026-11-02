import { useSyncExternalStore } from 'react';

export type ThemeChoice = 'light' | 'dark' | 'system';

// Also read by theme-init.js, the script of the <head>.
export const themeKey = 'emploi-go-theme';

const listeners = new Set<() => void>();

function explicit(value: string | null | undefined) {
  return value === 'light' || value === 'dark' ? value : null;
}

function apply(choice: ThemeChoice) {
  const root = document.documentElement;
  if (choice === 'system') delete root.dataset.theme;
  else root.dataset.theme = choice;
  listeners.forEach((listener) => listener());
}

/** The attribute of the page is the truth: the storage may be blocked. */
export function readTheme(): ThemeChoice {
  return explicit(document.documentElement.dataset.theme) ?? 'system';
}

export function setTheme(choice: ThemeChoice) {
  try {
    if (choice === 'system') localStorage.removeItem(themeKey);
    else localStorage.setItem(themeKey, choice);
  } catch {
    /* Without storage, the choice holds until the page is left. */
  }
  apply(choice);
}

function onStorage(event: StorageEvent) {
  if (event.key === themeKey || event.key === null)
    apply(explicit(event.newValue) ?? 'system');
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  if (listeners.size === 1) window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) window.removeEventListener('storage', onStorage);
  };
}

/** The choice of the visitor; the prerendered page knows only the system one. */
export function useTheme() {
  return useSyncExternalStore(
    subscribe,
    readTheme,
    (): ThemeChoice => 'system',
  );
}

const dark = '(prefers-color-scheme: dark)';

function subscribeSystem(listener: () => void) {
  const query = window.matchMedia(dark);
  query.addEventListener('change', listener);
  return () => query.removeEventListener('change', listener);
}

/** The theme in force: « Système » resolves to the preference of the system. */
export function useResolvedTheme(): 'light' | 'dark' {
  const choice = useTheme();
  const system = useSyncExternalStore(
    subscribeSystem,
    () => window.matchMedia(dark).matches,
    () => false,
  );
  if (choice === 'system') return system ? 'dark' : 'light';
  return choice;
}
