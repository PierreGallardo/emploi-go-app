import {
  LngLat,
  Map,
  NavigationControl,
  AttributionControl,
  addProtocol,
  setWorkerUrl,
} from 'maplibre-gl';
import type {
  ExpressionSpecification,
  GeoJSONSource,
  IControl,
  MapGeoJSONFeature,
  MapLayerMouseEvent,
  MapMouseEvent,
  LayerSpecification,
  StyleSpecification,
  VectorSourceSpecification,
} from 'maplibre-gl';
import { Protocol } from 'pmtiles';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import 'maplibre-gl/dist/maplibre-gl.css';
import {
  centerBounds,
  type Bounds,
  insetBounds,
  pitchRule,
  maxTilt,
  maxZoom,
  minZoomFor,
} from './map-limits';
import { markersOf, themeStyle, type MapThemeId } from './map-themes';
import { sectorColors } from './sectors';
import { loadMapIcon, placePinId, stopIconId } from './map-icons';
import {
  maxGroupSize,
  type Coordinates,
  type MapPoint,
  type Datasets,
  type SectorCode,
} from './schemas';
import type { Feature, FeatureCollection, Point, Polygon } from 'geojson';
import { mapRoot } from './map-preload';

const center: [number, number] = [5.0465, 47.31781];
// Tilted and zoomed in: Protomaps tiles carry few buildings below zoom 14.
const view = { zoom: 15.4, pitch: 55, bearing: -17 };
// Slightly inside the archive: its last tiles are never shown.
const bounds = insetBounds(
  [
    [4.866506, 47.189555],
    [5.235783, 47.42776],
  ],
  0.02,
);
// One protocol and worker configuration per module; remounts reuse its bounded PMTiles cache.
const protocol = new Protocol();
addProtocol('pmtiles', protocol.tile);
setWorkerUrl(workerUrl);

export type MapState = 'loading' | 'ready' | 'unavailable';
export type Insets = {
  top: number;
  right: number;
  bottom: number;
  left: number;
};
/** What the pointer rests on, in pixels of the map: the tooltip of a point or of a cluster. */
export type MapHover =
  | { kind: 'point'; id: string; x: number; y: number; radius: number }
  | {
      kind: 'cluster';
      count: number;
      /** The establishments of a small cluster, once known. */
      ids: string[] | null;
      x: number;
      y: number;
      radius: number;
    };
export type SearchCircle = { lng: number; lat: number; km: number };
export type EngineOptions = {
  points: MapPoint[];
  /** The Divia stops and the dates of the open data; the map works without them. */
  datasets: Datasets | null;
  /** The place chosen in the field "Où ?", marked on the map. */
  place: Coordinates | null;
  selectedId: string | null;
  circle: SearchCircle | null;
  theme: MapThemeId;
  insets: Insets;
  onState: (state: MapState) => void;
  onPick: (id: string) => void;
  onGroup: (ids: string[]) => void;
  /** A pointer over a point or a cluster; null when it leaves. Never called on a touch screen. */
  onHover: (hover: MapHover | null) => void;
};
export type MapEngine = {
  setPoints: (points: MapPoint[]) => void;
  setDatasets: (datasets: Datasets | null) => void;
  setSelected: (id: string | null) => void;
  setHighlighted: (id: string | null) => void;
  setCircle: (circle: SearchCircle | null) => void;
  setPlace: (place: Coordinates | null) => void;
  /** Brings the whole search radius into view. */
  fitCircle: (circle: SearchCircle) => void;
  /** Brings all the points into view, as far as the navigation limits allow. */
  fitPoints: (points: MapPoint[]) => void;
  setInsets: (insets: Insets) => void;
  flyTo: (target: Coordinates) => void;
  recenter: () => void;
  setTheme: (theme: MapThemeId) => void;
  destroy: () => void;
};

const empty: FeatureCollection<Point> = {
  type: 'FeatureCollection',
  features: [],
};

/** The marks of an establishment travel as properties (1 or 0): clusters aggregate them, so MapLibre redraws them with every frame of a zoom. */
type Marks = { selected?: string | null; hovered?: string | null };

/** A point of the map, identified by the SIRET of its establishment. */
function pointFeature(
  { id, lng, lat, sector }: { id: string; sector?: SectorCode } & Coordinates,
  { selected = null, hovered = null }: Marks = {},
): Feature<Point> {
  return {
    type: 'Feature',
    id,
    properties: {
      id,
      color: (sector && sectorColors[sector]) ?? '#475569',
      selected: Number(id === selected),
      hovered: Number(id === hovered),
    },
    geometry: { type: 'Point', coordinates: [lng, lat] },
  };
}

const placeData = (place: Coordinates): FeatureCollection<Point> => ({
  type: 'FeatureCollection',
  features: [pointFeature({ id: 'place', ...place })],
});

function points(items: MapPoint[], marks: Marks): FeatureCollection<Point> {
  return {
    type: 'FeatureCollection',
    features: items.map(({ siret, longitude, latitude, sector }) =>
      pointFeature({ id: siret, lng: longitude, lat: latitude, sector }, marks),
    ),
  };
}

/** The stops as the layers draw them: a name and a mode. */
function stopsData(datasets: Datasets): FeatureCollection<Point> {
  return {
    type: 'FeatureCollection',
    features: datasets.stops.map(({ name, mode, longitude, latitude }) => ({
      type: 'Feature',
      properties: { name, mode: mode === 'TRAM' ? 'tram' : 'bus' },
      geometry: { type: 'Point', coordinates: [longitude, latitude] },
    })),
  };
}

const frenchDate = (instant: string) =>
  new Date(instant).toLocaleDateString('fr-FR', { timeZone: 'Europe/Paris' });

/** The mentions the Licence Ouverte asks for: the producer, and the date of the data shown. */
function openDataAttribution({
  transitPublishedAt,
  addressesPublishedAt,
}: Datasets) {
  return [
    transitPublishedAt &&
      `DiviaMobilités / Dijon Métropole (données du ${frenchDate(transitPublishedAt)})`,
    addressesPublishedAt &&
      `Base Adresse Nationale (données du ${frenchDate(addressesPublishedAt)})`,
  ]
    .filter(Boolean)
    .join(' · ');
}

// Corners of the polygon that draws the search radius.
export const circleSteps = 64;

/** A circle of the search radius, as a polygon: close enough at the scale of a metropole. */
function circlePolygon({ lng, lat, km }: SearchCircle): Feature<Polygon> {
  const dLat = km / 111.32;
  const dLng = km / (111.32 * Math.cos((lat * Math.PI) / 180));
  const ring = Array.from({ length: circleSteps + 1 }, (_, step) => {
    const angle = (step / circleSteps) * 2 * Math.PI;
    return [lng + dLng * Math.cos(angle), lat + dLat * Math.sin(angle)];
  });
  return {
    type: 'Feature',
    properties: {},
    geometry: { type: 'Polygon', coordinates: [ring] },
  };
}

const stopFont = ['Noto Sans Medium'];

// Tram stops show before bus stops, and only close up. Icon and name make one symbol: they appear at
// the same zoom and, when they collide with another stop, they go together (neither is optional).
// The tram layer comes after the bus one, so its symbols are placed first and win a collision.
const stopZooms = { tram: 14, bus: 16 } as const;

function transitLayers(
  markers: ReturnType<typeof markersOf>,
  theme: MapThemeId,
) {
  const stops = (mode: 'tram' | 'bus') =>
    ({
      id: `transit-${mode}-stops`,
      type: 'symbol',
      source: 'transit-stops',
      filter: ['==', ['get', 'mode'], mode],
      minzoom: stopZooms[mode],
      layout: {
        'icon-image': stopIconId(mode, theme),
        'icon-size': mode === 'tram' ? 1 : 0.85,
        'text-field': ['get', 'name'],
        'text-font': stopFont,
        'text-size': 12,
        'text-anchor': 'left',
        'text-justify': 'left',
        'text-offset': [1.3, 0],
      },
      paint: {
        'text-color': markers.stopText,
        'text-halo-color': markers.stopHalo,
        'text-halo-width': 1.5,
      },
    }) satisfies LayerSpecification;
  return [stops('bus'), stops('tram')];
}

/** The two waves of each kind: soft circles of the colour of the point or cluster, drawn under the markers. */
function waveLayers(markers: ReturnType<typeof markersOf>) {
  return (
    Object.entries(waves) as [
      keyof typeof waves,
      (typeof waves)[keyof typeof waves],
    ][]
  ).map(([id, kind]): LayerSpecification => {
    const color: ExpressionSpecification | string =
      kind === 'point' ? ['get', 'color'] : markers.cluster;
    return {
      id,
      type: 'circle',
      source: 'establishments',
      filter: marked('hovered', kind),
      paint: {
        'circle-color': color,
        'circle-radius': kind === 'point' ? pointRadius : clusterRadius(),
        'circle-blur': 0.3,
        'circle-opacity': 0,
        'circle-stroke-width': 1.5,
        'circle-stroke-color': color,
        'circle-stroke-opacity': 0,
      },
    };
  });
}

// Part of every themed style, so a theme switch never drops the establishments.
function establishmentStyle(
  base: StyleSpecification,
  state: {
    points: MapPoint[];
    marks: Marks;
    circle: SearchCircle | null;
    place: Coordinates | null;
    datasets: Datasets | null;
  },
  theme: MapThemeId,
): StyleSpecification {
  const markers = markersOf(theme);
  const themed = themeStyle(base, theme);
  const background = themed.sources.protomaps as VectorSourceSpecification;
  return {
    ...themed,
    sources: {
      ...themed.sources,
      // The background is always drawn: it credits the open data, whose stops a zoom may hide.
      protomaps: {
        ...background,
        attribution: [
          state.datasets && openDataAttribution(state.datasets),
          background.attribution,
        ]
          .filter(Boolean)
          .join(' | '),
      },
      establishments: {
        type: 'geojson',
        data: points(state.points, state.marks),
        cluster: true,
        clusterProperties: {
          selected: ['max', ['get', 'selected']],
          hovered: ['max', ['get', 'hovered']],
        },
        clusterRadius: 45,
        clusterMaxZoom: 17,
        promoteId: 'id',
      },
      'chosen-place': {
        type: 'geojson',
        data: state.place ? placeData(state.place) : empty,
      },
      'search-radius': {
        type: 'geojson',
        data: state.circle
          ? circlePolygon(state.circle)
          : { type: 'FeatureCollection', features: [] },
      },
      ...(state.datasets
        ? {
            'transit-stops': {
              type: 'geojson',
              data: stopsData(state.datasets),
            } as const,
          }
        : {}),
    },
    layers: [
      ...themed.layers,
      ...(state.datasets ? transitLayers(markers, theme) : []),
      {
        id: 'search-radius-fill',
        type: 'fill',
        source: 'search-radius',
        paint: { 'fill-color': markers.selected, 'fill-opacity': 0.08 },
      },
      {
        id: 'search-radius-line',
        type: 'line',
        source: 'search-radius',
        paint: {
          'line-color': markers.selected,
          'line-width': 2,
          'line-dasharray': [2, 2],
        },
      },
      // Two soft waves of the colour of the point or cluster, under the marker; `pulse` animates them.
      ...waveLayers(markers),
      // Points first, then the clusters and their counts over them: a cluster always hides a point.
      {
        id: 'establishment-points',
        type: 'circle',
        source: 'establishments',
        filter: ['!', ['has', 'point_count']],
        paint: {
          'circle-color': ['get', 'color'],
          'circle-radius': 9,
          'circle-stroke-width': 3,
          'circle-stroke-color': markers.stroke,
        },
      },
      {
        id: 'establishment-clusters',
        type: 'circle',
        source: 'establishments',
        filter: ['has', 'point_count'],
        paint: {
          'circle-color': markers.cluster,
          'circle-radius': clusterRadius(),
          'circle-stroke-width': 3,
          'circle-stroke-color': markers.stroke,
        },
      },
      {
        id: 'establishment-cluster-count',
        type: 'symbol',
        source: 'establishments',
        filter: ['has', 'point_count'],
        layout: {
          'text-field': ['get', 'point_count_abbreviated'],
          'text-font': ['Noto Sans Medium'],
          'text-size': 13,
          'text-allow-overlap': true,
        },
        paint: { 'text-color': '#ffffff' },
      },
      {
        id: 'establishment-selected',
        type: 'circle',
        source: 'establishments',
        filter: marked('selected', 'point'),
        paint: {
          'circle-color': markers.cluster,
          'circle-radius': selectedRadius,
          'circle-stroke-width': 3,
          'circle-stroke-color': markers.stroke,
        },
      },
      {
        id: 'establishment-selected-dot',
        type: 'circle',
        source: 'establishments',
        filter: marked('selected', 'point'),
        paint: { 'circle-color': '#ffffff', 'circle-radius': 3.5 },
      },
      {
        // The cluster that holds the selected establishment: a ring tight around it.
        id: 'establishment-selected-cluster',
        type: 'circle',
        source: 'establishments',
        filter: marked('selected', 'cluster'),
        paint: {
          'circle-opacity': 0,
          'circle-radius': clusterRadius(1, 4.5),
          'circle-stroke-width': 3,
          'circle-stroke-color': markers.cluster,
        },
      },
      {
        id: 'chosen-place',
        type: 'symbol',
        source: 'chosen-place',
        layout: {
          'icon-image': placePinId(theme),
          'icon-anchor': 'bottom',
          'icon-allow-overlap': true,
          'icon-ignore-placement': true,
        },
      },
    ],
  };
}

// Size of a point, for a ring that starts exactly where it ends.
export const pointRadius = 9;
// A cluster is a little larger than a point (diameter 18 + 3 of outline): about 1.7× for the smallest,
// growing gently with the count and capped, and a little smaller when the map is far away.
const clusterSizes: [count: number, radius: number][] = [
  [2, 14],
  [10, 16],
  [50, 19],
  [200, 22],
];
const farZoom = 9;
const farScale = 0.85;
export const clusterRadiusAt = (count: number, zoom: number) => {
  const first = clusterSizes[0]!;
  const last = clusterSizes.at(-1)!;
  let radius = count <= first[0] ? first[1] : last[1];
  for (let at = 1; at < clusterSizes.length; at += 1) {
    const [from, fromRadius] = clusterSizes[at - 1]!;
    const [to, toRadius] = clusterSizes[at]!;
    if (count > from && count <= to)
      radius =
        fromRadius + ((count - from) / (to - from)) * (toRadius - fromRadius);
  }
  const scale =
    farScale +
    (1 - farScale) *
      Math.min(Math.max((zoom - farZoom) / (14 - farZoom), 0), 1);
  return radius * scale;
};
const countRadius = (scale: number, extra: number): ExpressionSpecification => [
  'interpolate',
  ['linear'],
  ['get', 'point_count'],
  ...clusterSizes.flatMap(([count, radius]) => [count, radius * scale + extra]),
];
// Zoom may only be the input of a top-level interpolate: each stop carries its own count scale.
// `factor` grows the cluster (the waves of its pulse), `extra` adds pixels (the ring around it).
const clusterRadius = (factor = 1, extra = 0): ExpressionSpecification => [
  'interpolate',
  ['linear'],
  ['zoom'],
  farZoom,
  countRadius(farScale * factor, extra),
  14,
  countRadius(factor, extra),
];
/** The establishments, or the clusters, that hold a marked one (`selected` or `hovered`). */
const marked = (
  mark: 'selected' | 'hovered',
  kind: 'point' | 'cluster',
): ExpressionSpecification => [
  'all',
  kind === 'point' ? ['!', ['has', 'point_count']] : ['has', 'point_count'],
  ['==', ['get', mark], 1],
];
// Up to this many establishments, a cluster names them in its tooltip.
const tooltipNames = 4;
const fitMargin = 24;
const noInsets: Insets = { top: 0, right: 0, bottom: 0, left: 0 };
// A single point, or a few close ones, are not worth a closer look than this.
const fitPointsZoom = 15;
// Zoom steps taken, at most, to bring a place near the border of the map into view.
export const fitStep = 0.4;
export const fitRetries = 6;
const pulsePeriod = 1600;
// A wave grows from the marker's size to (1 + growth) times it, from 0.6 opaque to nothing.
const pulseGrowth = 1;
const pulseOpacity = 0.6;
const pulseStroke = 0.9;
// Layer of each wave, and what it surrounds.
const waves = {
  'establishment-highlighted': 'point',
  'establishment-highlighted-wave': 'point',
  'establishment-cluster-highlighted': 'cluster',
  'establishment-cluster-highlighted-wave': 'cluster',
} as const;
const selectedRadius = 11;

const recenterLabel = 'Recentrer sur Dijon';

// MapLibre has no control for a custom action: same markup as its own buttons.
function recenterControl(onClick: () => void): IControl {
  const group = document.createElement('div');
  group.className = 'maplibregl-ctrl maplibregl-ctrl-group';
  const button = document.createElement('button');
  button.type = 'button';
  button.title = recenterLabel;
  button.setAttribute('aria-label', recenterLabel);
  button.addEventListener('click', onClick);
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('viewBox', '0 0 24 24');
  icon.setAttribute('width', '20');
  icon.setAttribute('height', '20');
  icon.setAttribute('aria-hidden', 'true');
  icon.setAttribute('fill', 'none');
  icon.setAttribute('stroke', 'currentColor');
  icon.setAttribute('stroke-width', '2');
  icon.setAttribute('stroke-linecap', 'round');
  icon.innerHTML =
    '<circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="1.5" fill="currentColor"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4"/>';
  button.append(icon);
  group.append(button);
  return { onAdd: () => group, onRemove: () => group.remove() };
}

export async function createDijonMap(
  container: HTMLElement,
  options: EngineOptions,
  signal: AbortSignal,
): Promise<MapEngine> {
  // The style is a static file of the map, not a read of the API: it does not go through `api.ts`.
  const response = await fetch(`${mapRoot}/style.json`, { signal });
  if (!response.ok) throw new Error('Fond indisponible.');
  const style: StyleSpecification = await response.json();
  // Resolve our versioned resources on this origin, including inside the bundled worker.
  style.glyphs = new URL(
    `${mapRoot}/fonts/{fontstack}/{range}.pbf`,
    window.location.origin,
  ).href
    .replaceAll('%7B', '{')
    .replaceAll('%7D', '}');
  style.sprite = new URL(
    `${mapRoot}/sprites/light`,
    window.location.origin,
  ).href;
  const source = style.sources.protomaps;
  if (!source || source.type !== 'vector') throw new Error('Fond invalide.');
  source.url = `pmtiles://${new URL(`${mapRoot}/dijon.pmtiles`, window.location.origin).href}`;
  if (signal.aborted) throw new DOMException('Cancelled', 'AbortError');
  const { onState, onPick, onGroup, onHover } = options;
  let destroyed = false;
  let current = options.points;
  let datasets = options.datasets;
  let selection = options.selectedId;
  let hovered: string | null = null;
  let circle = options.circle;
  let place = options.place;
  let theme = options.theme;
  let loaded = false;
  let groupRequest = 0;
  const fit = () =>
    minZoomFor(bounds, container.clientWidth, container.clientHeight);
  const pitch = pitchRule(fit, view.pitch);
  const styleFor = () =>
    establishmentStyle(
      style,
      {
        points: current,
        marks: { selected: selection, hovered },
        circle,
        place,
        datasets,
      },
      theme,
    );
  const map = new Map({
    container,
    style: styleFor(),
    center,
    ...view,
    minZoom: fit(),
    maxZoom,
    maxPitch: maxTilt,
    transformCameraUpdate: pitch.apply,
    transformConstrain: (position, zoom) => {
      const [[west, south], [east, north]] = centerBounds(
        bounds,
        zoom,
        container.clientWidth,
        container.clientHeight,
      );
      return {
        center: new LngLat(
          Math.min(Math.max(position.lng, west), east),
          Math.min(Math.max(position.lat, south), north),
        ),
        zoom: Math.min(Math.max(zoom, fit()), maxZoom),
      };
    },
    attributionControl: false,
    // Counts and names change at once while zooming: no cross-fade between the old and the new.
    fadeDuration: 0,
    canvasContextAttributes: { preserveDrawingBuffer: false },
    locale: {
      'NavigationControl.ZoomIn': 'Zoomer',
      'NavigationControl.ZoomOut': 'Dézoomer',
      'NavigationControl.ResetBearing': 'Remettre le nord en haut',
      'AttributionControl.ToggleAttribution':
        'Afficher les crédits cartographiques',
      'Map.Title': 'Carte des établissements de Dijon Métropole',
    },
  });
  // The icons of the stops and the pin are drawn when the style first asks for them, whatever the theme.
  map.setMissingStyleImageResolver(async (id) => {
    const icon = await loadMapIcon(id);
    if (icon && !destroyed && !map.hasImage(id))
      map.addImage(id, icon, { pixelRatio: 2 });
  });
  // What the panel or the sheet covers. It is never the map's own padding: that would move the camera
  // each time the panel opens or closes. It only steers the calls that frame something.
  let insets = options.insets;
  const visibleOffset = (): [number, number] => [
    (insets.left - insets.right) / 2,
    (insets.top - insets.bottom) / 2,
  ];
  map.on('pitchstart', (event) => {
    if ('originalEvent' in event) pitch.setFree(true);
  });
  map.on('pitchend', () => pitch.setFree(false));
  // A visitor who takes hold of the map ends the search for a place: no ease is to fight the drag.
  map.on('movestart', (event) => {
    if ('originalEvent' in event) fitRequest++;
  });
  function recenter() {
    if (destroyed) return;
    pitch.setFree(true);
    map.once('moveend', () => pitch.setFree(false));
    map.easeTo({ center, ...view, offset: visibleOffset(), duration: 600 });
  }
  // The panel takes the right of the screen: the controls and the credits stay on the left.
  map.addControl(new NavigationControl({ visualizePitch: true }), 'top-left');
  map.addControl(recenterControl(recenter), 'top-left');
  map.addControl(new AttributionControl({ compact: false }), 'bottom-left');
  const canvas = map.getCanvas();
  canvas.setAttribute(
    'aria-label',
    'Carte interactive de Dijon Métropole. La liste des établissements, à côté, donne accès à tout ce qu’elle montre.',
  );
  // Set on the element, not in React state: a move must not re-render the page.
  map.on('movestart', () => {
    container.dataset.mapMotion = 'moving';
  });
  map.on('idle', () => {
    container.dataset.mapMotion = 'idle';
    // What the map draws of the establishments, points and clusters: the E2E check it.
    container.dataset.mapPoints = String(
      map.queryRenderedFeatures({
        layers: ['establishment-points', 'establishment-clusters'],
      }).length,
    );
  });
  const observer = new ResizeObserver(([entry]) => {
    if (
      !destroyed &&
      entry &&
      entry.contentRect.width > 0 &&
      entry.contentRect.height > 0
    ) {
      map.resize();
      map.setMinZoom(fit());
    }
  });
  observer.observe(container);

  let fitRequest = 0;
  /** Whether a place is in the part of the map that the panel or the sheet leaves free. */
  function placeShows(lng: number, lat: number) {
    const at = map.project([lng, lat]);
    return (
      at.x >= insets.left + fitMargin &&
      at.x <= container.clientWidth - insets.right - fitMargin &&
      at.y >= insets.top + fitMargin &&
      at.y <= container.clientHeight - insets.bottom - fitMargin
    );
  }
  const hasRoom = () =>
    container.clientWidth - insets.left - insets.right > 2 * fitMargin &&
    container.clientHeight - insets.top - insets.bottom > 2 * fitMargin;
  /** Frames a box in the part of the map that the panel or the sheet leaves free. */
  function fitBox(box: Bounds, zoom: number) {
    // A new framing ends the search for a place of the previous one.
    fitRequest++;
    // A sheet raised over the whole map leaves nothing to frame in: it is framed as if it were lowered.
    const free = hasRoom() ? insets : noInsets;
    map.fitBounds(box, {
      padding: {
        top: fitMargin + free.top,
        left: fitMargin + free.left,
        right: fitMargin + free.right,
        bottom: fitMargin + free.bottom,
      },
      maxZoom: zoom,
      bearing: map.getBearing(),
      pitch: map.getPitch(),
      duration: 700,
    });
  }
  /** The navigation limits may hold the camera away from a place: the map comes closer, which widens what it may reach, until the place shows. */
  function bringIntoView(lng: number, lat: number) {
    const request = ++fitRequest;
    const settle = (attempt: number) =>
      map.once('moveend', () => {
        if (destroyed || request !== fitRequest || attempt >= fitRetries)
          return;
        if (placeShows(lng, lat)) return;
        map.easeTo({
          center: [lng, lat],
          zoom: Math.min(map.getZoom() + fitStep, view.zoom),
          offset: visibleOffset(),
          duration: 400,
        });
        settle(attempt + 1);
      });
    settle(0);
  }
  const dataOf = (id: string) => map.getSource(id) as GeoJSONSource | undefined;

  let pulse = 0;
  // The ids of the points of a cluster, by cluster: asked once per cluster of the current data.
  let clusterIds: Record<number, Promise<string[]>> = {};
  const membersOf = (clusterId: number, count: number) => {
    const source = dataOf('establishments');
    if (!source) return Promise.resolve([] as string[]);
    clusterIds[clusterId] ??= source
      .getClusterLeaves(clusterId, count, 0)
      .then((leaves) =>
        leaves.flatMap((leaf) =>
          typeof leaf.properties?.id === 'string' ? [leaf.properties.id] : [],
        ),
      );
    return clusterIds[clusterId]!.catch(() => [] as string[]);
  };
  const reducedMotion = () =>
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  const stopPulse = () => {
    if (pulse) cancelAnimationFrame(pulse);
    pulse = 0;
  };
  /** The waves of the hovered point or cluster: the filters of their layers pick it, wherever the zoom puts it. */
  function startPulse() {
    stopPulse();
    if (!hovered) return;
    const layers = Object.entries(waves) as [
      keyof typeof waves,
      (typeof waves)[keyof typeof waves],
    ][];
    const size = (kind: 'point' | 'cluster', factor: number) =>
      kind === 'point' ? pointRadius * factor : clusterRadius(factor);
    if (reducedMotion()) {
      // No movement: a still halo around the marker.
      for (const [layer, kind] of layers) {
        const first = !layer.endsWith('-wave');
        map.setPaintProperty(layer, 'circle-radius', size(kind, 1.4));
        map.setPaintProperty(layer, 'circle-opacity', first ? 0.55 : 0);
        map.setPaintProperty(layer, 'circle-stroke-opacity', first ? 0.8 : 0);
      }
      return;
    }
    const start = performance.now();
    const tick = (now: number) => {
      if (destroyed) return;
      for (const [layer, kind] of layers) {
        // The second wave follows half a period behind the first.
        const offset = layer.endsWith('-wave') ? 0.5 : 0;
        const progress = ((((now - start) / pulsePeriod + offset) % 1) + 1) % 1;
        const eased = 1 - (1 - progress) ** 2;
        // From the marker's own size to twice that, fading as it spreads: seen only outside the marker.
        map.setPaintProperty(
          layer,
          'circle-radius',
          size(kind, 1 + eased * pulseGrowth),
        );
        map.setPaintProperty(
          layer,
          'circle-opacity',
          pulseOpacity * (1 - progress),
        );
        map.setPaintProperty(
          layer,
          'circle-stroke-opacity',
          pulseStroke * (1 - progress),
        );
      }
      pulse = requestAnimationFrame(tick);
    };
    pulse = requestAnimationFrame(tick);
  }
  function applyData() {
    dataOf('establishments')?.setData(
      points(current, { selected: selection, hovered }),
    );
    dataOf('chosen-place')?.setData(place ? placeData(place) : empty);
    dataOf('search-radius')?.setData(
      circle
        ? circlePolygon(circle)
        : { type: 'FeatureCollection', features: [] },
    );
  }
  // A style that cannot be diffed reloads its sources: they come back with the data they had at the switch.
  map.on('style.load', () => {
    if (destroyed || !loaded) return;
    applyData();
    startPulse();
  });
  map.on('load', () => {
    if (destroyed) return;
    loaded = true;
    // What came before the load was only kept: a style rebuilt meanwhile (`setDatasets`) does not hold it.
    applyData();
    onState('ready');
  });
  // Only a failure before `load` (style, archive) makes the map unavailable: a later tile or glyph does not.
  map.on('error', () => {
    if (!destroyed && !loaded) onState('unavailable');
  });
  // Map pixels have a complete keyboard alternative in the adjacent HTML list.
  function openCluster(feature: MapGeoJSONFeature) {
    if (feature.geometry.type !== 'Point') return;
    const clusterId = Number(feature.properties.cluster_id);
    const count = Number(feature.properties.point_count);
    const coordinates = feature.geometry.coordinates as [number, number];
    const request = ++groupRequest;
    const clusters = map.getSource('establishments') as GeoJSONSource;
    void Promise.all([
      clusters.getClusterExpansionZoom(clusterId),
      clusters.getClusterLeaves(clusterId, Math.min(count, maxGroupSize), 0),
    ])
      .then(([zoom, leaves]) => {
        if (destroyed || request !== groupRequest) return;
        const ids = leaves.flatMap((leaf) =>
          typeof leaf.properties?.id === 'string' ? [leaf.properties.id] : [],
        );
        onGroup(ids);
        // Identical coordinates stay clustered: their HTML choices remain available at maximum zoom.
        map.easeTo({
          center: coordinates,
          zoom: Math.min(zoom, maxZoom),
          offset: visibleOffset(),
          duration: 300,
        });
      })
      // A cluster whose points cannot be read only comes closer: the list still has them all.
      .catch(() => {
        if (destroyed || request !== groupRequest) return;
        map.easeTo({
          center: coordinates,
          zoom: Math.min(map.getZoom() + 2, maxZoom),
          offset: visibleOffset(),
          duration: 300,
        });
      });
  }
  // A click takes the cluster or the point near it, a finger's width on a touch screen.
  const touchScreen = window.matchMedia?.('(pointer: coarse)').matches ?? false;
  map.on('click', (event: MapMouseEvent) => {
    const pad = touchScreen ? 16 : 3;
    const box: [[number, number], [number, number]] = [
      [event.point.x - pad, event.point.y - pad],
      [event.point.x + pad, event.point.y + pad],
    ];
    const cluster = map.queryRenderedFeatures(box, {
      layers: ['establishment-clusters'],
    })[0];
    if (cluster) return openCluster(cluster);
    const feature = map.queryRenderedFeatures(box, {
      layers: ['establishment-points'],
    })[0];
    if (typeof feature?.properties.id === 'string')
      onPick(feature.properties.id);
  });
  // A tooltip follows a mouse, never a finger: touch keeps its tap.
  if (window.matchMedia?.('(hover: hover) and (pointer: fine)').matches) {
    let shown: string | null = null;
    const hide = () => {
      if (shown === null) return;
      shown = null;
      onHover(null);
    };
    map.on('mousemove', 'establishment-points', (event: MapLayerMouseEvent) => {
      const feature = event.features?.[0];
      if (feature?.geometry.type !== 'Point') return;
      const key = `point:${String(feature.properties.id)}`;
      if (key === shown) return;
      shown = key;
      const at = map.project(feature.geometry.coordinates as [number, number]);
      onHover({
        kind: 'point',
        id: String(feature.properties.id),
        x: at.x,
        y: at.y,
        radius: pointRadius,
      });
    });
    map.on(
      'mousemove',
      'establishment-clusters',
      (event: MapLayerMouseEvent) => {
        const feature = event.features?.[0];
        if (feature?.geometry.type !== 'Point') return;
        const clusterId = Number(feature.properties.cluster_id);
        const count = Number(feature.properties.point_count);
        const key = `cluster:${clusterId}`;
        if (key === shown) return;
        shown = key;
        const at = map.project(
          feature.geometry.coordinates as [number, number],
        );
        const place = {
          x: at.x,
          y: at.y,
          radius: clusterRadiusAt(count, map.getZoom()),
        };
        onHover({ kind: 'cluster', count, ids: null, ...place });
        // A small cluster names its establishments; a big one only says how many.
        if (count <= tooltipNames)
          void membersOf(clusterId, count).then((ids) => {
            if (!destroyed && shown === key)
              onHover({ kind: 'cluster', count, ids, ...place });
          });
      },
    );
    for (const layer of ['establishment-points', 'establishment-clusters'])
      map.on('mouseleave', layer, hide);
    map.on('movestart', hide);
    map.on('click', hide);
  }
  for (const layer of ['establishment-points', 'establishment-clusters']) {
    map.on('mouseenter', layer, () => {
      canvas.style.cursor = 'pointer';
    });
    map.on('mouseleave', layer, () => {
      canvas.style.cursor = '';
    });
  }
  return {
    setPoints: (items) => {
      if (destroyed) return;
      current = items;
      groupRequest++;
      clusterIds = {};
      if (loaded) {
        applyData();
      }
    },
    setDatasets: (next) => {
      if (destroyed || next === datasets) return;
      datasets = next;
      // Before the load MapLibre cannot diff a style: it rebuilds it, which is asked directly instead of failing a diff first.
      map.setStyle(styleFor(), { diff: loaded });
    },
    setSelected: (id) => {
      if (destroyed || id === selection) return;
      selection = id;
      if (loaded) applyData();
    },
    setHighlighted: (id) => {
      if (destroyed || id === hovered) return;
      hovered = id;
      if (loaded) {
        applyData();
        startPulse();
      }
    },
    setPlace: (next) => {
      if (destroyed) return;
      place = next;
      if (loaded) applyData();
    },
    setCircle: (next) => {
      if (destroyed) return;
      circle = next;
      if (loaded) applyData();
    },
    fitCircle: ({ lng, lat, km }) => {
      if (destroyed) return;
      const dLat = km / 111.32;
      const dLng = km / (111.32 * Math.cos((lat * Math.PI) / 180));
      fitBox(
        [
          [lng - dLng, lat - dLat],
          [lng + dLng, lat + dLat],
        ],
        view.zoom,
      );
      bringIntoView(lng, lat);
    },
    fitPoints: (items) => {
      if (destroyed || items.length === 0) return;
      const lngs = items.map((item) => item.longitude);
      const lats = items.map((item) => item.latitude);
      fitBox(
        [
          [Math.min(...lngs), Math.min(...lats)],
          [Math.max(...lngs), Math.max(...lats)],
        ],
        fitPointsZoom,
      );
    },
    setInsets: (next) => {
      insets = next;
    },
    flyTo: ({ lng, lat }) => {
      if (destroyed) return;
      map.easeTo({
        center: [lng, lat],
        zoom: Math.max(map.getZoom(), 16),
        offset: visibleOffset(),
        duration: 600,
      });
    },
    recenter,
    setTheme: (next) => {
      if (destroyed || next === theme) return;
      theme = next;
      map.setStyle(styleFor(), { diff: loaded });
    },
    destroy: () => {
      if (destroyed) return;
      destroyed = true;
      fitRequest++;
      groupRequest++;
      stopPulse();
      observer.disconnect();
      map.remove();
    },
  };
}
