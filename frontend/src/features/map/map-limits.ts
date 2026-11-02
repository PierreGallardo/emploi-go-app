const tileSize = 512;
// Beyond zoom 15 the tiles are overzoomed: past this the picture only gets blurrier.
export const maxZoom = 17.5;
export const maxTilt = 65;
const flatTilt = 8;
// Zoom from which the tilt may reach its maximum.
const tiltZoom = 15;

export type Bounds = [[number, number], [number, number]];

const mercatorY = (lat: number) => {
  const radians = (lat * Math.PI) / 180;
  return (
    (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2
  );
};

/** Shrinks the archive bounds so that its last tiles never reach the edge of the screen. */
export function insetBounds(bounds: Bounds, ratio: number): Bounds {
  const [[west, south], [east, north]] = bounds;
  const dx = (east - west) * ratio;
  const dy = (north - south) * ratio;
  return [
    [west + dx, south + dy],
    [east - dx, north - dy],
  ];
}

/**
 * Lowest zoom at which a viewport of this size, whatever its bearing,
 * fits inside the bounds: the diagonal is the widest extent a rotation can reach.
 */
export function minZoomFor(bounds: Bounds, width: number, height: number) {
  const [[west, south], [east, north]] = bounds;
  const worldWidth = (east - west) / 360;
  const worldHeight = mercatorY(south) - mercatorY(north);
  const room = Math.min(worldWidth, worldHeight) * tileSize;
  return Math.max(0, Math.log2(Math.hypot(width, height) / room));
}

/** The farther out, the flatter: a tilted view shows the ground far beyond the bounds. */
export function maxPitchFor(zoom: number, minZoom: number) {
  const span = Math.max(tiltZoom - minZoom, 1);
  const progress = Math.min(Math.max((zoom - minZoom) / span, 0), 1);
  const eased = progress * progress * (3 - 2 * progress);
  return flatTilt + (maxTilt - flatTilt) * eased;
}

/**
 * Where the centre may go so that the whole viewport stays inside the bounds:
 * MapLibre's own maxBounds only keeps the centre inside, whatever the zoom.
 */
export function centerBounds(
  bounds: Bounds,
  zoom: number,
  width: number,
  height: number,
): Bounds {
  const [[west, south], [east, north]] = bounds;
  const half = Math.hypot(width, height) / 2 / (tileSize * 2 ** zoom);
  const middle = ((south + north) / 2 / 180) * Math.PI;
  const halfLng = Math.min(half * 360, (east - west) / 2);
  const halfLat = Math.min(half * 360 * Math.cos(middle), (north - south) / 2);
  return [
    [west + halfLng, south + halfLat],
    [east - halfLng, north - halfLat],
  ];
}

/**
 * Keeps the tilt the visitor asked for, within what the zoom allows, on every camera update:
 * zooming out flattens the view and zooming back in gives the tilt back, with no event to
 * interrupt a gesture or an ease in progress. While the tilt itself is being changed
 * (a drag, a recentring) the proposed value is followed instead, since an ease proposes
 * stale values that cannot tell a request from the clamp.
 */
export function pitchRule(minZoom: () => number, initial: number) {
  let wanted = initial;
  let free = false;
  return {
    setFree(value: boolean) {
      free = value;
    },
    apply({ pitch, zoom }: { pitch: number; zoom: number }) {
      if (free) wanted = pitch;
      return { pitch: Math.min(wanted, maxPitchFor(zoom, minZoom())) };
    },
  };
}
