import { expect, it } from 'vitest';
import {
  centerBounds,
  insetBounds,
  maxPitchFor,
  maxTilt,
  minZoomFor,
  pitchRule,
  type Bounds,
} from './map-limits';

const archive: Bounds = [
  [4.866506, 47.189555],
  [5.235783, 47.42776],
];
const worldWidth = (bounds: Bounds) =>
  ((bounds[1][0] - bounds[0][0]) / 360) * 512;

it.each([
  { size: 'un téléphone', width: 390, height: 700 },
  { size: 'un ordinateur', width: 1280, height: 720 },
])(
  'le zoom minimal sur $size garde l’écran entier, même tourné, dans les bornes',
  ({ width, height }) => {
    const zoom = minZoomFor(archive, width, height);

    expect(Math.hypot(width, height)).toBeLessThanOrEqual(
      worldWidth(archive) * 2 ** zoom,
    );
  },
);

it('ne demande pas de zoomer davantage sur un grand écran que sur un petit', () => {
  expect(minZoomFor(archive, 1280, 720)).toBeGreaterThan(
    minZoomFor(archive, 390, 700),
  );
});

it('ne descend jamais sous zéro, même sans taille connue', () => {
  expect(minZoomFor(archive, 0, 0)).toBe(0);
});

it('rentre les bornes pour que les dernières tuiles restent hors écran', () => {
  const inside = insetBounds(archive, 0.02);

  expect(inside[0][0]).toBeGreaterThan(archive[0][0]);
  expect(inside[0][1]).toBeGreaterThan(archive[0][1]);
  expect(inside[1][0]).toBeLessThan(archive[1][0]);
  expect(inside[1][1]).toBeLessThan(archive[1][1]);
});

it('aplatit la vue en dézoomant et rend toute l’inclinaison de près', () => {
  const minZoom = 11;

  expect(maxPitchFor(minZoom, minZoom)).toBeLessThan(15);
  expect(maxPitchFor(15, minZoom)).toBe(maxTilt);
  expect(maxPitchFor(18, minZoom)).toBe(maxTilt);
  expect(maxPitchFor(13, minZoom)).toBeGreaterThan(maxPitchFor(12, minZoom));
});

it('laisse le centre plus de marge à fort zoom qu’à faible zoom', () => {
  const far = centerBounds(archive, 11.5, 390, 700);
  const near = centerBounds(archive, 15, 390, 700);

  expect(near[0][0]).toBeLessThan(far[0][0]);
  expect(near[1][1]).toBeGreaterThan(far[1][1]);
  expect(far[0][0]).toBeGreaterThan(archive[0][0]);
  expect(near[0][0]).toBeGreaterThanOrEqual(archive[0][0]);
});

it('aplatit en dézoomant puis rend l’inclinaison voulue en zoomant, sans la perdre en route', () => {
  const rule = pitchRule(() => 11, 55);

  // An ease proposes the stale pitch of its start: the clamp must not become the wish.
  const out = [15.4, 13, 11].map(
    (zoom) => rule.apply({ pitch: 55, zoom }).pitch,
  );
  const back = rule.apply({ pitch: out[2]!, zoom: 15.4 }).pitch;

  expect(out[0]).toBe(55);
  expect(out[1]!).toBeLessThan(out[0]!);
  expect(out[2]!).toBeLessThan(out[1]!);
  expect(back).toBe(55);
});

it('suit le geste du visiteur tant qu’il change l’inclinaison, dans la limite du zoom', () => {
  const rule = pitchRule(() => 11, 55);

  rule.setFree(true);
  const steeper = rule.apply({ pitch: 62, zoom: 15.4 }).pitch;
  const capped = rule.apply({ pitch: 62, zoom: 11 }).pitch;
  rule.setFree(false);

  expect(steeper).toBe(62);
  expect(capped).toBeLessThan(15);
  expect(rule.apply({ pitch: capped, zoom: 15.4 }).pitch).toBe(62);
});
