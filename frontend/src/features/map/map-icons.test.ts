import { expect, it } from 'vitest';
import { loadMapIcon, placePinSvg, stopIconSvg } from './map-icons';

it('dessine un pictogramme blanc dans un badge arrondi, différent pour le tram et le bus', () => {
  const tram = stopIconSvg('tram', 'light');
  const bus = stopIconSvg('bus', 'light');

  expect(tram).toContain('rx="10"');
  expect(tram).toContain('stroke="#ffffff"');
  expect(tram).not.toBe(bus);
  expect(stopIconSvg('tram', 'dark')).not.toBe(tram);
});

it('ne fournit rien pour une image qui n’est pas une icône d’arrêt', async () => {
  expect(await loadMapIcon('sprite-quelconque')).toBeNull();
});

it('dessine l’épingle du lieu dans une couleur propre à chaque thème, avec un liseré blanc', () => {
  const light = placePinSvg('light');

  expect(light).toContain('stroke="#ffffff"');
  expect(placePinSvg('dark')).not.toBe(light);
});
