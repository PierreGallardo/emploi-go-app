import { preload } from 'react-dom';

export const mapRoot = '/cartes/dijon-20260930-v2';

/** Asks for the engine and its style at once: the engine's own requests then reuse these downloads instead of following each other. */
export function preloadMap() {
  // A failed download is reported by the map, which imports the engine again when it mounts.
  import('./map-engine').catch(() => undefined);
  // The mode of the engine's `fetch`: a preload of another mode would not be reused, and the style would be downloaded twice.
  preload(`${mapRoot}/style.json`, { as: 'fetch', crossOrigin: 'anonymous' });
}
