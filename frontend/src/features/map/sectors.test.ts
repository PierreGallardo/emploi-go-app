import { expect, it } from 'vitest';
import { contrastRatio } from '@/test/contrast';
import { sectorCodes, sectorStyle } from './sectors';

// The primary blue of the app (`--primary` in app.css), kept for the clusters and the selection.
const primary = '#2c67fc';

const toLinear = (channel: number) => {
  const value = channel / 255;
  return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
};
function oklab(hex: string): [number, number, number] {
  const [r, g, b] = [1, 3, 5].map((at) =>
    toLinear(parseInt(hex.slice(at, at + 2), 16)),
  );
  const l = Math.cbrt(
    0.4122214708 * r! + 0.5363325363 * g! + 0.0514459929 * b!,
  );
  const m = Math.cbrt(
    0.2119034982 * r! + 0.6806995451 * g! + 0.1073969566 * b!,
  );
  const s = Math.cbrt(
    0.0883024619 * r! + 0.2817188376 * g! + 0.6299787005 * b!,
  );
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}
const distance = (a: string, b: string) =>
  Math.hypot(...oklab(a).map((v, i) => v - oklab(b)[i]!));

it('donne à chaque secteur une couleur à lui', () => {
  const colors = sectorCodes.map((code) => sectorStyle[code].color);

  expect(new Set(colors).size).toBe(sectorCodes.length);
});

it.each(sectorCodes.map((code) => ({ code, color: sectorStyle[code].color })))(
  'garde le bleu de l’application loin de la couleur du secteur $code',
  ({ color }) => {
    expect(distance(color, primary)).toBeGreaterThan(0.12);
  },
);

it.each(sectorCodes.map((code) => ({ code, color: sectorStyle[code].color })))(
  'garde la couleur du secteur $code lisible en icône sur fond blanc (3:1)',
  ({ color }) => {
    expect(contrastRatio(color, '#ffffff')).toBeGreaterThanOrEqual(3);
  },
);
