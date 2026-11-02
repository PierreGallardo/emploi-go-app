import { useSyncExternalStore } from 'react';

/** A screen wide enough for side-by-side layouts: below it, the layout of a phone. */
export const wideScreen = '(min-width: 768px)';

/** Follows a media query; the prerendered page assumes `fallback`, the browser then decides. */
export function useMediaQuery(query: string, fallback: boolean) {
  return useSyncExternalStore(
    (listener) => {
      const list = window.matchMedia(query);
      list.addEventListener('change', listener);
      return () => list.removeEventListener('change', listener);
    },
    () => window.matchMedia(query).matches,
    () => fallback,
  );
}
