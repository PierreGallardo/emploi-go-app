import { expect, it } from 'vitest';
import { contrastRatio } from './contrast';

it('compte 21:1 entre le noir et le blanc, quel que soit l’ordre', () => {
  expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
  expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(21, 5);
});

it('lit une couleur hsl comme la même couleur écrite en hexadécimal', () => {
  expect(contrastRatio('hsl(0 100% 50%)', '#ffffff')).toBeCloseTo(
    contrastRatio('#ff0000', '#ffffff'),
    2,
  );
});
