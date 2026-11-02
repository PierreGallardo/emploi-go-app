import { useEffect, useRef } from 'react';
import { focusWasLost } from '@/lib/lost-focus';

/** How long « Chargement terminé. » stays: a polite message waits for the speech in progress, then must go. */
export const endOfLoadingDuration = 5_000;

/**
 * Render it where the loading and loaded screens share a position, so React keeps the live region; `end` is written directly because React must not own its children.
 */
export function LoadingStatus({
  loading,
  silent = false,
  focusSpeaks = true,
}: {
  loading: boolean;
  silent?: boolean;
  focusSpeaks?: boolean;
}) {
  const end = useRef<HTMLSpanElement>(null);
  const wasLoading = useRef(loading);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => {
    const region = end.current;
    if (!region) return;
    if (loading) {
      clearTimeout(timer.current);
      region.textContent = '';
    } else if (wasLoading.current) {
      // Child effects run before the parent's, which moves the focus: `focusWasLost()` still sees the screen about to take it.
      // A focus that names its screen replaces the end message unless `focusSpeaks={false}` (the unnamed `<main>` of the shell).
      if (!silent && !(focusSpeaks && focusWasLost())) {
        region.textContent = 'Chargement terminé.';
        timer.current = setTimeout(() => {
          region.textContent = '';
        }, endOfLoadingDuration);
      }
    }
    wasLoading.current = loading;
  }, [loading, silent, focusSpeaks]);
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <p role="status" className="sr-only">
      {loading ? 'Chargement…' : null}
      <span ref={end} />
    </p>
  );
}
