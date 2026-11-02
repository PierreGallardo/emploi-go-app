import type { StyleSpecification } from 'maplibre-gl';
import { expect, it } from 'vitest';
import { contrastRatio } from '@/test/contrast';
import { themeStyle } from './map-themes';

const base: StyleSpecification = {
  version: 8,
  sources: { protomaps: { type: 'vector', url: 'pmtiles://x' } },
  layers: [
    {
      id: 'background',
      type: 'background',
      paint: { 'background-color': '#cccccc' },
    },
    {
      id: 'earth',
      type: 'fill',
      source: 'protomaps',
      'source-layer': 'earth',
      paint: { 'fill-color': '#e2dfda' },
    },
    {
      id: 'buildings',
      type: 'fill',
      source: 'protomaps',
      'source-layer': 'buildings',
      filter: ['in', 'kind', 'building', 'building_part'],
      paint: { 'fill-color': '#cccccc' },
    },
    {
      id: 'roads_rail',
      type: 'line',
      source: 'protomaps',
      'source-layer': 'roads',
      filter: ['==', 'kind', 'rail'],
      paint: { 'line-color': '#a7b1b3' },
    },
    {
      id: 'places',
      type: 'symbol',
      source: 'protomaps',
      'source-layer': 'places',
      paint: { 'text-color': '#5c5c5c', 'text-halo-color': '#ffffff' },
    },
  ],
};
const layer = (style: StyleSpecification, id: string) =>
  style.layers.find((item) => item.id === id) as unknown as {
    type: string;
    filter: unknown;
    paint: Record<string, unknown>;
  };

it.each(['dark', 'light'] as const)(
  'extrude les bâtiments du thème %s avec une hauteur par défaut',
  (theme) => {
    const buildings = layer(themeStyle(base, theme), 'buildings');

    expect(buildings.type).toBe('fill-extrusion');
    expect(buildings.filter).toEqual([
      'in',
      'kind',
      'building',
      'building_part',
    ]);
    expect(buildings.paint['fill-extrusion-height']).toEqual([
      'coalesce',
      ['get', 'height'],
      10,
    ]);
    expect(buildings.paint['fill-extrusion-base']).toEqual([
      'coalesce',
      ['get', 'min_height'],
      0,
    ]);
  },
);

it('garde le style clair tel que Protomaps le fournit', () => {
  const style = themeStyle(base, 'light');

  expect(layer(style, 'earth').paint['fill-color']).toBe('#e2dfda');
  expect(layer(style, 'places').paint['text-color']).toBe('#5c5c5c');
});

it('garde les textes du thème sombre lisibles sur les surfaces (4,5:1)', () => {
  const style = themeStyle(base, 'dark');

  expect(
    contrastRatio(
      layer(style, 'places').paint['text-color'] as string,
      layer(style, 'earth').paint['fill-color'] as string,
    ),
  ).toBeGreaterThanOrEqual(4.5);
});
