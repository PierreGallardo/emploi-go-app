import type { ExpressionSpecification, StyleSpecification } from 'maplibre-gl';

export type MapThemeId = 'dark' | 'light';

type Hsl = { h: number; s: number; l: number };
type Recolor = (color: Hsl) => Hsl;
type Markers = {
  cluster: string;
  point: string;
  selected: string;
  stroke: string;
  tram: string;
  stopText: string;
  stopHalo: string;
};
type Theme = {
  surface: Recolor;
  text: Recolor;
  halo: Recolor;
  buildings: [string, string, string];
  markers: Markers;
};

const clamp = (value: number, min = 0, max = 1) =>
  Math.min(max, Math.max(min, value));
const colorPattern = /^(#[0-9a-f]{6}|rgba?\([^)]*\))$/i;

function parse(color: string): Hsl | null {
  const rgb = color.startsWith('#')
    ? [1, 3, 5].map((start) => parseInt(color.slice(start, start + 2), 16))
    : (color.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number);
  if (rgb.length !== 3 || rgb.some(Number.isNaN)) return null;
  const [r, g, b] = rgb.map((channel) => channel! / 255) as [
    number,
    number,
    number,
  ];
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  if (d === 0) return { h: 0, s: 0, l };
  const s = d / (1 - Math.abs(2 * l - 1));
  const h =
    max === r
      ? ((g - b) / d + (g < b ? 6 : 0)) * 60
      : max === g
        ? ((b - r) / d + 2) * 60
        : ((r - g) / d + 4) * 60;
  return { h, s, l };
}

function format({ h, s, l }: Hsl): string {
  return `hsl(${Math.round(h)} ${Math.round(clamp(s) * 100)}% ${Math.round(clamp(l) * 100)}%)`;
}

// Recolours every colour literal of a paint value, including those nested in expressions.
function recolor(value: unknown, change: Recolor): unknown {
  if (typeof value === 'string' && colorPattern.test(value)) {
    const hsl = parse(value);
    return hsl ? format(change(hsl)) : value;
  }
  return Array.isArray(value)
    ? value.map((item) => recolor(item, change))
    : value;
}

const themes: Record<MapThemeId, Theme> = {
  light: {
    surface: (color) => color,
    text: (color) => color,
    halo: (color) => color,
    buildings: ['#ddd9d3', '#cdc9c2', '#bdb8b0'],
    markers: {
      cluster: '#2c67fc',
      point: '#2897d5',
      selected: '#842854',
      stroke: '#ffffff',
      tram: '#a9b0b5',
      stopText: '#3b2a35',
      stopHalo: '#ffffff',
    },
  },
  dark: {
    surface: ({ h, s, l }) => ({ h, s: s * 0.45, l: 0.04 + 0.34 * l ** 6 }),
    text: ({ h, s, l }) => ({ h, s: s * 0.4, l: 1 - 0.55 * l }),
    halo: ({ h, l }) => ({ h, s: 0.1, l: 0.06 + 0.08 * l }),
    buildings: ['#323a4e', '#3f4963', '#505b7e'],
    markers: {
      cluster: '#2c67fc',
      point: '#38b6ff',
      selected: '#ffd166',
      stroke: '#ffffff',
      tram: '#5d6675',
      stopText: '#f1e4ec',
      stopHalo: '#12141c',
    },
  },
};

export const markersOf = (theme: MapThemeId): Markers => themes[theme].markers;

// Only ~7% of buildings carry a height: the rest get a plausible default.
const buildingHeight: ExpressionSpecification = [
  'coalesce',
  ['get', 'height'],
  10,
];

// Derives a theme from the single Protomaps light style, so no second style file has to be kept in sync.
export function themeStyle(
  base: StyleSpecification,
  id: MapThemeId,
): StyleSpecification {
  const theme = themes[id];
  const [low, middle, high] = theme.buildings;
  const mapped = base.layers.map((layer) => {
    if (layer.id === 'buildings' && layer.type === 'fill')
      return {
        id: layer.id,
        type: 'fill-extrusion',
        source: layer.source,
        'source-layer': layer['source-layer'],
        filter: layer.filter,
        minzoom: 13,
        paint: {
          'fill-extrusion-height': buildingHeight,
          'fill-extrusion-base': ['coalesce', ['get', 'min_height'], 0],
          'fill-extrusion-opacity': 0.92,
          'fill-extrusion-color': [
            'interpolate',
            ['linear'],
            buildingHeight,
            0,
            low,
            25,
            middle,
            80,
            high,
          ],
        },
      } as StyleSpecification['layers'][number];
    if (id === 'light' || !('paint' in layer) || !layer.paint) return layer;
    const paint = Object.fromEntries(
      Object.entries(layer.paint).map(([name, value]) => {
        const change =
          name === 'text-color'
            ? theme.text
            : name === 'text-halo-color'
              ? theme.halo
              : name.endsWith('color')
                ? theme.surface
                : null;
        return [name, change ? recolor(value, change) : value];
      }),
    );
    return { ...layer, paint } as typeof layer;
  });
  // The tram tracks, over the dashed rail of the base map: thin, neutral, barely there.
  const tracks: StyleSpecification['layers'] = [
    {
      id: 'tram-line',
      type: 'line',
      source: 'protomaps',
      'source-layer': 'roads',
      filter: [
        'all',
        ['==', ['get', 'kind'], 'rail'],
        ['==', ['get', 'kind_detail'], 'tram'],
      ] as ExpressionSpecification,
      minzoom: 11,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': theme.markers.tram,
        'line-opacity': 0.8,
        'line-width': [
          'interpolate',
          ['linear'],
          ['zoom'],
          11,
          0.6,
          14,
          1.2,
          18,
          2.5,
        ],
      },
    },
  ];
  const rail = mapped.findIndex((layer) => layer.id === 'roads_rail');
  const layers =
    rail < 0
      ? mapped
      : [...mapped.slice(0, rail + 1), ...tracks, ...mapped.slice(rail + 1)];
  // Beyond the last tile the ground colour goes on, so the end of the data never shows as an edge.
  const earth = layers.find((layer) => layer.id === 'earth');
  const ground =
    earth && 'paint' in earth
      ? (earth.paint as Record<string, string>)['fill-color']
      : undefined;
  if (!ground) return { ...base, layers };
  return {
    ...base,
    layers: layers.map((layer) =>
      layer.type === 'background'
        ? { ...layer, paint: { ...layer.paint, 'background-color': ground } }
        : layer,
    ),
    sky: {
      'sky-color': ground,
      'horizon-color': ground,
      'horizon-fog-blend': 0.5,
      'sky-horizon-blend': 0.5,
      'atmosphere-blend': 0,
    },
  };
}
