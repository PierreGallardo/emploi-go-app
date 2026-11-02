import { useEffect } from 'react';

// Why these two traces: docs/frontend/conventions.md, « Focus quand un écran en remplace un autre ».
let lastTouched: Element | null = null;
let shownScreen: Element | null = null;

// A click counts only on a control: the text of a message that goes away later is not where the visitor was.
const control =
  'a[href], button, input, select, textarea, summary, [tabindex], [role="button"], [role="link"], [role="menuitem"]';

function remember(event: Event) {
  if (!(event.target instanceof Element)) return;
  const target =
    event.type === 'click' ? event.target.closest(control) : event.target;
  if (target) lastTouched = target;
}

/** Installed once, by the application's providers. */
export function useFocusTracking() {
  useEffect(() => {
    document.addEventListener('focusin', remember, true);
    document.addEventListener('click', remember, true);
    return () => {
      document.removeEventListener('focusin', remember, true);
      document.removeEventListener('click', remember, true);
      lastTouched = null;
      shownScreen = null;
    };
  }, []);
}

/** Call it after `focusWasLost`, which compares with the previous screen. */
export function markScreenShown(screen: Element | null) {
  if (screen) shownScreen = screen;
}

export function focusWasLost() {
  const active = document.activeElement;
  if (active && active !== document.body) return false;
  return [lastTouched, shownScreen].some(
    (element) => element !== null && !element.isConnected,
  );
}
