import { userEvent } from '@testing-library/user-event';
import { within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, expect, it, vi } from 'vitest';
import { recordRequests } from '@/test/requests';
import { server } from '@/test/server';
import { placePinId, stopIconId } from './map-icons';
import type { EngineOptions, MapEngine } from './map-engine';
import {
  circleSteps,
  clusterRadiusAt,
  fitRetries,
  fitStep,
  pointRadius,
} from './map-engine';
import { mapRoot } from './map-preload';
import { maxGroupSize, type Datasets } from './schemas';
import { sectorStyle } from './sectors';

const mocks = vi.hoisted(() => {
  const handlers: Record<string, (event?: unknown) => void> = {};
  const sources: Record<
    string,
    {
      setData: ReturnType<typeof vi.fn>;
      getClusterLeaves: ReturnType<typeof vi.fn>;
    }
  > = {};
  const map = {
    setStyle: vi.fn(),
    // Like MapLibre, several listeners may share an event: they all run.
    on: vi.fn((event: string, first: unknown, second?: unknown) => {
      const key =
        typeof first === 'function' ? event : `${event}:${String(first)}`;
      const listener = (typeof first === 'function' ? first : second) as (
        event?: unknown,
      ) => void;
      const before = handlers[key];
      handlers[key] = (argument) => {
        before?.(argument);
        listener(argument);
      };
    }),
    once: vi.fn(),
    addControl: vi.fn(),
    getCanvas: () => document.createElement('canvas'),
    getSource: vi.fn(
      (id: string) =>
        (sources[id] ??= { setData: vi.fn(), getClusterLeaves: vi.fn() }),
    ),
    setPaintProperty: vi.fn(),
    getPadding: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
    project: vi.fn(([lng, lat]: number[]) => ({
      x: (lng! - 5) * 10000,
      y: (47.3 - lat!) * 10000,
    })),
    getBounds: () => ({
      getWest: () => 5,
      getSouth: () => 47.3,
      getEast: () => 5.1,
      getNorth: () => 47.35,
    }),
    getZoom: () => 12,
    queryRenderedFeatures: vi.fn(() => []),
    setPadding: vi.fn(),
    setMissingStyleImageResolver: vi.fn(),
    hasImage: vi.fn(() => false),
    addImage: vi.fn(),
    remove: vi.fn(),
    easeTo: vi.fn(),
    fitBounds: vi.fn(),
    getBearing: () => -17,
    getPitch: () => 55,
    isMoving: () => false,
  };
  return { handlers, sources, map, options: vi.fn() };
});
vi.mock('maplibre-gl', () => ({
  Map: class {
    constructor(options: unknown) {
      mocks.options(options);
      return mocks.map;
    }
  },
  LngLat: class {
    constructor(
      public lng: number,
      public lat: number,
    ) {}
  },
  NavigationControl: class {},
  AttributionControl: class {},
  addProtocol: vi.fn(),
  setWorkerUrl: vi.fn(),
}));
vi.mock('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url', () => ({
  default: '/worker.mjs',
}));
vi.mock('pmtiles', () => ({
  Protocol: class {
    tile = vi.fn();
  },
}));

const point = {
  siret: 'a',
  longitude: 5.04,
  latitude: 47.32,
  sector: 'FOOD' as const,
};
const base = {
  version: 8,
  glyphs: '',
  sprite: '',
  sources: {
    protomaps: {
      type: 'vector',
      url: 'pmtiles://x',
      attribution: '© contributeurs fictifs',
    },
  },
  layers: [
    {
      id: 'buildings',
      type: 'fill',
      source: 'protomaps',
      'source-layer': 'buildings',
      paint: { 'fill-color': '#cccccc' },
    },
  ],
};
type Style = {
  sources: Record<
    string,
    { data?: { features: unknown[] }; attribution?: string }
  >;
  layers: Array<{
    id: string;
    type: string;
    minzoom?: number;
    layout?: Record<string, unknown>;
    filter?: unknown;
    paint?: Record<string, unknown>;
  }>;
};
// Stops of an invented network, as the API gives them.
const datasets: Datasets = {
  stops: [
    {
      name: 'Arrêt fictif',
      mode: 'TRAM',
      longitude: 5.04,
      latitude: 47.32,
    },
  ],
  transitPublishedAt: '2026-01-02T09:00:00Z',
  addressesPublishedAt: '2026-01-03T05:00:00Z',
};
const create = async (overrides: Partial<EngineOptions> = {}) => {
  server.use(http.get(`${mapRoot}/style.json`, () => HttpResponse.json(base)));
  const { createDijonMap } = await import('./map-engine');
  const container = document.createElement('div');
  Object.defineProperties(container, {
    clientWidth: { value: 390 },
    clientHeight: { value: 700 },
  });
  const options: EngineOptions = {
    points: [point],
    datasets,
    place: null,
    selectedId: null,
    circle: null,
    theme: 'dark',
    insets: { top: 0, right: 0, bottom: 0, left: 0 },
    onState: vi.fn(),
    onPick: vi.fn(),
    onGroup: vi.fn(),
    onHover: vi.fn(),
    ...overrides,
  };
  const engine = await createDijonMap(
    container,
    options,
    new AbortController().signal,
  );
  return { engine, options, container };
};
const styleOf = (call: number) =>
  (mocks.options.mock.calls[call]![0] as { style: Style }).style;
afterEach(() => {
  vi.clearAllMocks();
  vi.unstubAllGlobals();
  for (const key of Object.keys(mocks.sources)) delete mocks.sources[key];
  for (const key of Object.keys(mocks.handlers)) delete mocks.handlers[key];
});

it('charge le style de la carte depuis son dossier versionné', async () => {
  const requests = recordRequests();

  await create();

  expect(requests.of('GET', `${mapRoot}/style.json`)).toHaveLength(1);
});

it('crée la carte en 3D avec le thème demandé et les établissements dans le style', async () => {
  await create();

  const options = mocks.options.mock.calls[0]![0] as {
    pitch: number;
    dragRotate?: boolean;
    touchPitch?: boolean;
  };
  const style = styleOf(0);
  expect(options.pitch).toBeGreaterThan(0);
  expect(options.dragRotate).not.toBe(false);
  expect(options.touchPitch).not.toBe(false);
  expect(style.layers.find((l) => l.id === 'buildings')?.type).toBe(
    'fill-extrusion',
  );
  expect(style.sources.establishments?.data?.features).toHaveLength(1);
  expect(style.layers.map((l) => l.id)).toEqual(
    expect.arrayContaining([
      'establishment-clusters',
      'establishment-points',
      'establishment-highlighted',
      'search-radius-line',
    ]),
  );
});

it('cite les données ouvertes avec leur date dans l’attribution du fond', async () => {
  await create();

  expect(styleOf(0).sources.protomaps?.attribution).toBe(
    'DiviaMobilités / Dijon Métropole (données du 02/01/2026) · Base Adresse Nationale (données du 03/01/2026) | © contributeurs fictifs',
  );
});

it('donne au fond un symbole par arrêt reçu', async () => {
  await create();

  expect(styleOf(0).sources['transit-stops']?.data?.features).toHaveLength(
    datasets.stops.length,
  );
});

it('montre les arrêts de tram avant ceux de bus en s’approchant', async () => {
  await create();

  const layers = styleOf(0).layers;
  const minzoomOf = (id: string) =>
    layers.find((l) => l.id === id)?.minzoom as number;
  expect(minzoomOf('transit-tram-stops')).toBeLessThan(
    minzoomOf('transit-bus-stops'),
  );
});

it.each([
  { id: 'transit-tram-stops', mode: 'tram' },
  { id: 'transit-bus-stops', mode: 'bus' },
] as const)(
  'affiche le nom et l’icône des arrêts de $mode, sans rien rendre optionnel',
  async ({ id, mode }) => {
    await create();

    const layout = styleOf(0).layers.find((l) => l.id === id)?.layout;
    expect(layout).toMatchObject({
      'icon-image': stopIconId(mode, 'dark'),
      'text-field': ['get', 'name'],
    });
    expect(layout).not.toHaveProperty('text-optional');
    expect(layout).not.toHaveProperty('icon-optional');
  },
);

it('place les arrêts de tram après ceux de bus, pour qu’ils gagnent une collision', async () => {
  await create();

  const ids = styleOf(0).layers.map((l) => l.id);
  expect(ids.indexOf('transit-bus-stops')).toBeLessThan(
    ids.indexOf('transit-tram-stops'),
  );
});

it('fait dessiner les icônes d’arrêt à la demande du style', async () => {
  await create();

  expect(mocks.map.setMissingStyleImageResolver).toHaveBeenCalledOnce();
});

it('crée la carte sans arrêts tant que leurs données manquent', async () => {
  await create({ datasets: null });

  const style = styleOf(0);
  expect(style.sources['transit-stops']).toBeUndefined();
  expect(style.layers.some((l) => l.id.startsWith('transit-'))).toBe(false);
  expect(style.sources.establishments?.data?.features).toHaveLength(1);
});

it('colore chaque point selon son secteur', async () => {
  await create();

  const points = styleOf(0).layers.find((l) => l.id === 'establishment-points');
  expect(points?.paint?.['circle-color']).toEqual(['get', 'color']);
  const feature = styleOf(0).sources.establishments!.data!.features[0] as {
    properties: { color: string };
  };
  expect(feature.properties.color).toBe(sectorStyle.FOOD.color);
});

it('borne le zoom et l’inclinaison selon la taille de la carte', async () => {
  await create();

  const options = mocks.options.mock.calls[0]![0] as {
    minZoom: number;
    maxZoom: number;
    maxPitch: number;
    zoom: number;
    pitch: number;
  };
  expect(options.minZoom).toBeGreaterThan(9);
  expect(options.zoom).toBeGreaterThan(options.minZoom);
  expect(options.zoom).toBeLessThanOrEqual(options.maxZoom);
  expect(options.pitch).toBeLessThanOrEqual(options.maxPitch);
});

it('empêche le centre de sortir de l’archive, quel que soit le zoom', async () => {
  await create();

  const { transformConstrain } = mocks.options.mock.calls[0]![0] as {
    transformConstrain: (
      position: { lng: number; lat: number },
      zoom: number,
    ) => { center: { lng: number; lat: number }; zoom: number };
  };
  const far = transformConstrain({ lng: 0, lat: 80 }, 40);

  expect(far.center.lng).toBeGreaterThan(4.8);
  expect(far.center.lng).toBeLessThan(5.3);
  expect(far.center.lat).toBeGreaterThan(47.1);
  expect(far.center.lat).toBeLessThan(47.5);
  expect(far.zoom).toBe(17.5);
  expect(transformConstrain({ lng: 5, lat: 47.3 }, 1).zoom).toBeGreaterThan(9);
});

it('offre un contrôle « Recentrer sur Dijon » qui rétablit la vue 3D initiale', async () => {
  await create();

  const control = mocks.map.addControl.mock.calls
    .map(([added]) => added as { onAdd?: (map: unknown) => HTMLElement })
    .find((added) => 'onAdd' in added && added.constructor === Object);
  const button = within(control!.onAdd!(mocks.map)).getByRole('button', {
    name: 'Recentrer sur Dijon',
  });
  expect(button).toHaveAttribute('type', 'button');
  await userEvent.setup().click(button);

  expect(mocks.map.easeTo).toHaveBeenCalledWith(
    expect.objectContaining({
      center: [5.0465, 47.31781],
      zoom: 15.4,
      pitch: 55,
      bearing: -17,
    }),
  );
});

it('rend la carte indisponible quand le fond échoue avant son chargement', async () => {
  const { options } = await create();

  mocks.handlers.error!();

  expect(options.onState).toHaveBeenLastCalledWith('unavailable');
});

it('garde la carte prête quand une tuile échoue après son chargement', async () => {
  const { options } = await create();
  mocks.handlers.load!();

  mocks.handlers.error!();

  expect(options.onState).toHaveBeenLastCalledWith('ready');
  expect(options.onState).not.toHaveBeenCalledWith('unavailable');
});

it('envoie les nouveaux points à la source, une fois la carte prête', async () => {
  const { engine } = await create();

  engine.setPoints([]);
  expect(mocks.sources.establishments).toBeUndefined();
  mocks.handlers.load!();
  engine.setPoints([point, { ...point, siret: 'b' }]);

  const data = mocks.sources.establishments!.setData.mock.calls.at(-1)![0] as {
    features: unknown[];
  };
  expect(data.features).toHaveLength(2);
});

it('dessine au chargement les points reçus après un changement des données ouvertes', async () => {
  const { engine } = await create({ points: [], datasets: null });

  engine.setDatasets(datasets);
  engine.setPoints([point, { ...point, siret: 'b' }]);
  mocks.handlers.load!();

  const data = mocks.sources.establishments!.setData.mock.calls.at(-1)![0] as {
    features: unknown[];
  };
  expect(data.features).toHaveLength(2);
});

it('dessine le rayon de recherche comme un cercle de points', async () => {
  const { engine } = await create();
  mocks.handlers.load!();

  engine.setCircle({ lng: 5.04, lat: 47.32, km: 5 });

  const drawn = mocks.sources['search-radius']!.setData.mock.calls.at(
    -1,
  )![0] as { geometry: { coordinates: number[][][] } };
  expect(drawn.geometry.coordinates[0]).toHaveLength(circleSteps + 1);
});

it('efface le rayon de recherche quand il n’y en a plus', async () => {
  const { engine } = await create();
  mocks.handlers.load!();
  engine.setCircle({ lng: 5.04, lat: 47.32, km: 5 });

  engine.setCircle(null);

  const erased = mocks.sources['search-radius']!.setData.mock.calls.at(
    -1,
  )![0] as { features: unknown[] };
  expect(erased.features).toEqual([]);
});

const settle = () => mocks.map.once.mock.calls.at(-1)![1] as () => void;

it('rapproche la carte quand les limites de navigation laissent le lieu hors de l’écran', async () => {
  const { engine } = await create();
  // The pin projects above the screen: (5.04, 47.32) → y = -200.
  engine.fitCircle({ lng: 5.04, lat: 47.32, km: 5 });

  settle()();

  expect(mocks.map.easeTo).toHaveBeenCalledWith(
    expect.objectContaining({
      center: [5.04, 47.32],
      zoom: mocks.map.getZoom() + fitStep,
    }),
  );
});

it('ne rapproche pas la carte quand le lieu est déjà à l’écran', async () => {
  const { engine } = await create();
  // Projects at (100, 100): inside the 390 × 700 map.
  engine.fitCircle({ lng: 5.01, lat: 47.29, km: 5 });

  settle()();

  expect(mocks.map.easeTo).not.toHaveBeenCalled();
});

it('cesse de rapprocher la carte après quelques essais', async () => {
  const { engine } = await create();
  engine.fitCircle({ lng: 5.04, lat: 47.32, km: 5 });

  Array.from({ length: fitRetries + 4 }, () => settle()());

  expect(mocks.map.easeTo).toHaveBeenCalledTimes(fitRetries);
});

it('ignore le cadrage précédent dès qu’un nouveau cadrage commence', async () => {
  const { engine } = await create();
  engine.fitCircle({ lng: 5.04, lat: 47.32, km: 5 });
  const stale = settle();
  engine.fitCircle({ lng: 5.01, lat: 47.29, km: 5 });

  stale();

  expect(mocks.map.easeTo).not.toHaveBeenCalled();
});

it('amène tout le rayon de recherche à l’écran, sans changer l’orientation ni l’inclinaison', async () => {
  const { engine } = await create();

  engine.fitCircle({ lng: 5.04, lat: 47.32, km: 5 });

  const [box, options] = mocks.map.fitBounds.mock.calls[0]! as [
    [[number, number], [number, number]],
    Record<string, number>,
  ];
  expect(box[0][0]).toBeLessThan(5.04);
  expect(box[1][1]).toBeGreaterThan(47.32);
  expect(options).toMatchObject({ bearing: -17, pitch: 55 });
});

const frames = () => vi.mocked(requestAnimationFrame);
/** What the last `setData` of the establishments says about each of them. */
const lastMarks = () =>
  (
    mocks.sources.establishments!.setData.mock.calls.at(-1)![0] as {
      features: { properties: Record<string, unknown> }[];
    }
  ).features.map(({ properties }) => ({
    id: properties.id,
    selected: properties.selected,
    hovered: properties.hovered,
  }));
const rendered = (answers: { points?: unknown[]; clusters?: unknown[] }) =>
  mocks.map.queryRenderedFeatures.mockImplementation(((
    first: { layers: string[] } | unknown[],
    second?: { layers: string[] },
  ) => {
    // The first argument is a box when the query is around a click, the options otherwise.
    const { layers } = Array.isArray(first) ? second! : first;
    return [
      ...(layers.includes('establishment-points')
        ? (answers.points ?? [])
        : []),
      ...(layers.includes('establishment-clusters')
        ? (answers.clusters ?? [])
        : []),
    ];
  }) as never);
const cluster = (id: number, count: number, lng: number, lat: number) => ({
  geometry: { type: 'Point', coordinates: [lng, lat] },
  properties: { cluster_id: id, point_count: count },
});
const reduce = (reduced: boolean) =>
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: reduced })),
  );
const hover = async () => {
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn(() => 7),
  );
  vi.stubGlobal('cancelAnimationFrame', vi.fn());
  reduce(false);
  const { engine } = await create();
  mocks.handlers.load!();
  return engine;
};

it('marque l’établissement survolé dans la source', async () => {
  const engine = await hover();

  engine.setHighlighted('a');

  expect(lastMarks()).toEqual([{ id: 'a', selected: 0, hovered: 1 }]);
});

it('fait pulser la lueur du point survolé, sans bouger la carte', async () => {
  const engine = await hover();

  engine.setHighlighted('a');

  await vi.waitFor(() => expect(frames()).toHaveBeenCalledOnce());
  const tick = frames().mock.calls[0]![0] as (now: number) => void;
  tick(performance.now() + 700);
  expect(frames()).toHaveBeenCalledTimes(2);
  expect(mocks.map.easeTo).not.toHaveBeenCalled();
  expect(mocks.map.fitBounds).not.toHaveBeenCalled();
});

it('remplace le pouls par un halo fixe quand l’utilisateur réduit les animations', async () => {
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn(() => 7),
  );
  vi.stubGlobal('cancelAnimationFrame', vi.fn());
  reduce(true);
  const { engine } = await create();
  mocks.handlers.load!();

  engine.setHighlighted('a');

  expect(frames()).not.toHaveBeenCalled();
});

it('arrête l’animation et retire la marque quand la carte n’est plus survolée', async () => {
  const engine = await hover();
  engine.setHighlighted('a');
  await vi.waitFor(() => expect(frames()).toHaveBeenCalledOnce());

  engine.setHighlighted(null);

  expect(cancelAnimationFrame).toHaveBeenCalledWith(7);
  expect(lastMarks()).toEqual([{ id: 'a', selected: 0, hovered: 0 }]);
});

it('ne redessine rien quand le survol ne change pas', async () => {
  const engine = await hover();
  engine.setHighlighted('a');
  const calls = mocks.sources.establishments!.setData.mock.calls.length;

  engine.setHighlighted('a');

  expect(mocks.sources.establishments!.setData).toHaveBeenCalledTimes(calls);
});

const finePointer = (fine: boolean) =>
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: fine && query.includes('hover: hover'),
    })),
  );
const over = (layer: string, properties: object, coordinates = [5.04, 47.32]) =>
  mocks.handlers[`mousemove:${layer}`]!({
    features: [{ properties, geometry: { type: 'Point', coordinates } }],
  });

it('annonce le point sous la souris une seule fois tant qu’elle y reste', async () => {
  finePointer(true);
  const { options } = await create();

  over('establishment-points', { id: 'a' });
  over('establishment-points', { id: 'a' });

  expect(options.onHover).toHaveBeenCalledOnce();
  expect(options.onHover).toHaveBeenCalledWith({
    kind: 'point',
    id: 'a',
    x: expect.any(Number),
    y: expect.any(Number),
    radius: pointRadius,
  });
});

it('annonce qu’il n’y a plus de point survolé quand la souris le quitte', async () => {
  finePointer(true);
  const { options } = await create();
  over('establishment-points', { id: 'a' });

  mocks.handlers['mouseleave:establishment-points']!();

  expect(options.onHover).toHaveBeenLastCalledWith(null);
});

it('nomme les établissements d’un petit regroupement sous la souris, une fois ses feuilles connues', async () => {
  finePointer(true);
  mocks.sources.establishments = {
    setData: vi.fn(),
    getClusterLeaves: vi
      .fn()
      .mockResolvedValue([
        { properties: { id: 'a' } },
        { properties: { id: 'b' } },
      ]),
  };
  const { options } = await create();

  over('establishment-clusters', { cluster_id: 3, point_count: 2 });

  expect(options.onHover).toHaveBeenCalledWith(
    expect.objectContaining({ kind: 'cluster', count: 2, ids: null }),
  );
  await vi.waitFor(() =>
    expect(options.onHover).toHaveBeenLastCalledWith(
      expect.objectContaining({ kind: 'cluster', count: 2, ids: ['a', 'b'] }),
    ),
  );
});

it('ne demande pas les feuilles d’un gros regroupement, dont on ne donne que le nombre', async () => {
  finePointer(true);
  mocks.sources.establishments = {
    setData: vi.fn(),
    getClusterLeaves: vi.fn(),
  };
  const { options } = await create();

  over('establishment-clusters', { cluster_id: 4, point_count: 12 });

  expect(options.onHover).toHaveBeenCalledWith(
    expect.objectContaining({ kind: 'cluster', count: 12, ids: null }),
  );
  expect(mocks.sources.establishments.getClusterLeaves).not.toHaveBeenCalled();
});

it('retire l’infobulle quand la carte se déplace', async () => {
  finePointer(true);
  const { options } = await create();
  over('establishment-points', { id: 'a' });

  mocks.handlers.movestart!({});

  expect(options.onHover).toHaveBeenLastCalledWith(null);
});

it('n’écoute aucune souris sur un écran tactile', async () => {
  finePointer(false);
  await create();

  expect(mocks.handlers['mousemove:establishment-points']).toBeUndefined();
  expect(mocks.handlers['mousemove:establishment-clusters']).toBeUndefined();
});

it('marque l’établissement sélectionné dans la source', async () => {
  const { engine } = await create();
  mocks.handlers.load!();

  engine.setSelected('a');

  expect(lastMarks()).toEqual([{ id: 'a', selected: 1, hovered: 0 }]);
});

it('retire la marque à la désélection', async () => {
  const { engine } = await create({ selectedId: 'a' });
  mocks.handlers.load!();

  engine.setSelected(null);

  expect(lastMarks()).toEqual([{ id: 'a', selected: 0, hovered: 0 }]);
});

it('garde la sélection quand le style est reposé pour un autre thème', async () => {
  const { engine } = await create({ selectedId: 'a' });

  engine.setTheme('light');

  const style = mocks.map.setStyle.mock.calls[0]![0] as Style;
  expect(
    (
      style.sources.establishments?.data?.features[0] as {
        properties: { selected: number };
      }
    ).properties.selected,
  ).toBe(1);
});

it('marque le lieu choisi d’une épingle', async () => {
  const { engine } = await create();
  mocks.handlers.load!();

  engine.setPlace({ lng: 5.03, lat: 47.32 });

  const pinned = mocks.sources['chosen-place']!.setData.mock.calls.at(
    -1,
  )![0] as {
    features: { geometry: { coordinates: number[] } }[];
  };
  expect(pinned.features[0]!.geometry.coordinates).toEqual([5.03, 47.32]);
});

it('efface l’épingle quand il n’y a plus de lieu choisi', async () => {
  const { engine } = await create();
  mocks.handlers.load!();
  engine.setPlace({ lng: 5.03, lat: 47.32 });

  engine.setPlace(null);

  const erased = mocks.sources['chosen-place']!.setData.mock.calls.at(
    -1,
  )![0] as {
    features: unknown[];
  };
  expect(erased.features).toEqual([]);
});

it('dessine l’épingle au-dessus de tout, avec l’image du thème', async () => {
  await create({ theme: 'light' });

  const layers = styleOf(0).layers;
  expect(layers.at(-1)).toMatchObject({
    id: 'chosen-place',
    layout: { 'icon-image': placePinId('light') },
  });
});

it('transmet le clic sur un point, cherché dans un petit carré autour du clic', async () => {
  const { options } = await create();
  rendered({ points: [{ properties: { id: 'a' } }] });

  mocks.handlers.click!({ point: { x: 100, y: 200 } });

  expect(options.onPick).toHaveBeenCalledWith('a');
  const [box] = mocks.map.queryRenderedFeatures.mock.calls.at(
    -1,
  ) as unknown as [[[number, number], [number, number]]];
  expect(box[0][0]).toBeLessThan(100);
  expect(box[1][0]).toBeGreaterThan(100);
});

it('préfère le regroupement au point quand le clic touche les deux', async () => {
  const { options } = await create();
  mocks.sources.establishments = {
    setData: vi.fn(),
    getClusterLeaves: vi.fn().mockResolvedValue([{ properties: { id: 'a' } }]),
    getClusterExpansionZoom: vi.fn().mockResolvedValue(14),
  } as never;
  rendered({
    points: [{ properties: { id: 'a' } }],
    clusters: [cluster(1, 3, 5.04, 47.32)],
  });

  mocks.handlers.click!({ point: { x: 100, y: 200 } });

  await vi.waitFor(() => expect(options.onGroup).toHaveBeenCalledWith(['a']));
  expect(options.onPick).not.toHaveBeenCalled();
});

it('ne lit d’un gros regroupement que les points que la liste montre', async () => {
  await create();
  const leaves = vi.fn().mockResolvedValue([{ properties: { id: 'a' } }]);
  mocks.sources.establishments = {
    setData: vi.fn(),
    getClusterLeaves: leaves,
    getClusterExpansionZoom: vi.fn().mockResolvedValue(14),
  } as never;
  rendered({
    points: [],
    clusters: [cluster(1, maxGroupSize + 50, 5.04, 47.32)],
  });

  mocks.handlers.click!({ point: { x: 100, y: 200 } });

  await vi.waitFor(() =>
    expect(leaves).toHaveBeenCalledWith(1, maxGroupSize, 0),
  );
});

it('rapproche la carte d’un regroupement dont les points ne se lisent pas, sans la masquer', async () => {
  const { options } = await create();
  mocks.handlers.load!();
  mocks.sources.establishments = {
    setData: vi.fn(),
    getClusterLeaves: vi.fn().mockRejectedValue(new Error('panne fictive')),
    getClusterExpansionZoom: vi.fn().mockResolvedValue(14),
  } as never;
  rendered({ points: [], clusters: [cluster(1, 3, 5.04, 47.32)] });

  mocks.handlers.click!({ point: { x: 100, y: 200 } });

  await vi.waitFor(() => expect(mocks.map.easeTo).toHaveBeenCalled());
  expect(options.onState).not.toHaveBeenCalledWith('unavailable');
  expect(options.onGroup).not.toHaveBeenCalled();
});

it('dit combien de points et de regroupements la carte dessine quand elle s’arrête', async () => {
  const { container } = await create();
  rendered({
    points: [{ properties: { id: 'a' } }],
    clusters: [cluster(1, 3, 5.04, 47.32)],
  });

  mocks.handlers.idle!({});

  expect(container.dataset.mapPoints).toBe('2');
});

it('élargit la zone de clic sur un écran tactile', async () => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({ matches: query.includes('pointer: coarse') })),
  );
  await create();
  rendered({ points: [{ properties: { id: 'a' } }] });

  mocks.handlers.click!({ point: { x: 100, y: 200 } });

  const [box] = mocks.map.queryRenderedFeatures.mock.calls.at(
    -1,
  ) as unknown as [[[number, number], [number, number]]];
  expect(box[1][0] - box[0][0]).toBeGreaterThanOrEqual(32);
});

it('donne aux regroupements une taille qui croît doucement avec leur nombre, plafonnée, et plus petite de loin', () => {
  const near = [2, 10, 50, 200, 5000].map((count) =>
    clusterRadiusAt(count, 14),
  );

  expect(near).toEqual([...near].sort((a, b) => a - b));
  expect(near.at(-1)).toBe(near.at(-2));
  expect(clusterRadiusAt(50, 9)).toBeLessThan(clusterRadiusAt(50, 14));
});

it('ne déplace pas la caméra quand le panneau s’ouvre ou se ferme : les marges ne servent qu’aux cadrages', async () => {
  const { engine } = await create();

  engine.setInsets({ top: 0, right: 300, bottom: 0, left: 0 });
  engine.setInsets({ top: 0, right: 0, bottom: 0, left: 0 });

  expect(mocks.map.setPadding).not.toHaveBeenCalled();
  expect(mocks.map.easeTo).not.toHaveBeenCalled();
  expect(mocks.map.fitBounds).not.toHaveBeenCalled();
});

it('vole vers un établissement en le centrant dans la partie que le panneau laisse libre, sans le dézoomer', async () => {
  const { engine } = await create();

  engine.setInsets({ top: 0, right: 300, bottom: 100, left: 0 });
  engine.flyTo({ lng: 5.04, lat: 47.32 });

  expect(mocks.map.easeTo).toHaveBeenCalledWith(
    expect.objectContaining({
      center: [5.04, 47.32],
      zoom: 16,
      offset: [-150, -50],
    }),
  );
});

const dragged = () => mocks.handlers.movestart!({ originalEvent: {} });

it('cesse de rapprocher la carte d’un lieu quand le visiteur la prend en main', async () => {
  const { engine } = await create();
  engine.fitCircle({ lng: 5.04, lat: 47.32, km: 5 });

  dragged();
  settle()();

  expect(mocks.map.easeTo).not.toHaveBeenCalled();
});

it('cesse de rapprocher la carte d’un lieu quand une recherche cadre ses points', async () => {
  const { engine } = await create();
  engine.fitCircle({ lng: 5.04, lat: 47.32, km: 5 });
  const stale = settle();

  engine.fitPoints([
    { siret: '1', sector: 'FOOD', longitude: 5.02, latitude: 47.3 },
  ]);
  stale();

  expect(mocks.map.easeTo).not.toHaveBeenCalled();
});

it('amène tous les points à l’écran dans la partie libre, avec un zoom maximal', async () => {
  const { engine } = await create();
  const point = { siret: '1', sector: 'FOOD' } as const;

  engine.setInsets({ top: 0, right: 300, bottom: 0, left: 0 });
  engine.fitPoints([
    { ...point, longitude: 5.02, latitude: 47.3 },
    { ...point, longitude: 5.06, latitude: 47.34 },
  ]);

  const [box, options] = mocks.map.fitBounds.mock.calls[0]! as [
    unknown,
    { padding: { right: number }; maxZoom: number },
  ];
  expect(box).toEqual([
    [5.02, 47.3],
    [5.06, 47.34],
  ]);
  expect(options.padding.right).toBeGreaterThan(300);
  expect(options.maxZoom).toBeLessThan(16);
});

it('ne bouge pas quand il n’y a aucun point à cadrer', async () => {
  const { engine } = await create();

  engine.fitPoints([]);

  expect(mocks.map.fitBounds).not.toHaveBeenCalled();
});

it('cadre le rayon dans la partie libre de la carte, marges du panneau comprises', async () => {
  const { engine } = await create();

  engine.setInsets({ top: 0, right: 300, bottom: 0, left: 0 });
  engine.fitCircle({ lng: 5.04, lat: 47.32, km: 5 });

  const [, options] = mocks.map.fitBounds.mock.calls[0]! as [
    unknown,
    { padding: { right: number; left: number } },
  ];
  expect(options.padding.right).toBeGreaterThan(300);
  expect(options.padding.left).toBeLessThan(100);
});

it('ne repose pas le style quand le thème demandé est déjà affiché', async () => {
  const { engine } = await create({ theme: 'light' });

  engine.setTheme('light');

  expect(mocks.map.setStyle).not.toHaveBeenCalled();
});

it('repose le style pour un autre thème, établissements et rayon compris', async () => {
  const { engine } = await create({
    theme: 'light',
    circle: { lng: 5, lat: 47.3, km: 2 },
  });

  engine.setTheme('dark');

  expect(mocks.map.setStyle).toHaveBeenCalledOnce();
  const style = mocks.map.setStyle.mock.calls[0]![0] as Style;
  expect(style.sources.establishments?.data?.features).toHaveLength(1);
  expect(style.sources['search-radius']?.data).toMatchObject({
    geometry: { type: 'Polygon' },
  });
});

it('dessine les arrêts arrivés après la création de la carte, en citant les seules données reçues', async () => {
  const { engine } = await create({ datasets: null });

  engine.setDatasets({ ...datasets, addressesPublishedAt: null });

  const style = mocks.map.setStyle.mock.calls[0]![0] as Style;
  expect(style.sources['transit-stops']?.data?.features).toHaveLength(
    datasets.stops.length,
  );
  expect(style.sources.protomaps?.attribution).toBe(
    'DiviaMobilités / Dijon Métropole (données du 02/01/2026) | © contributeurs fictifs',
  );
  expect(style.layers.map((l) => l.id)).toContain('transit-tram-stops');
});

it.each([
  {
    cas: 'les arrêts avant le chargement',
    load: () => undefined,
    diff: false,
    change: (engine: MapEngine) => engine.setDatasets(datasets),
  },
  {
    cas: 'les arrêts après le chargement',
    load: () => mocks.handlers.load!(),
    diff: true,
    change: (engine: MapEngine) => engine.setDatasets(datasets),
  },
  {
    cas: 'le thème avant le chargement',
    load: () => undefined,
    diff: false,
    change: (engine: MapEngine) => engine.setTheme('light'),
  },
  {
    cas: 'le thème après le chargement',
    load: () => mocks.handlers.load!(),
    diff: true,
    change: (engine: MapEngine) => engine.setTheme('light'),
  },
])(
  'ne compare les styles qu’une fois la carte chargée : $cas',
  async ({ load, diff, change }) => {
    const { engine } = await create({ datasets: null, theme: 'dark' });
    load();

    change(engine);

    expect(mocks.map.setStyle).toHaveBeenCalledWith(expect.anything(), {
      diff,
    });
  },
);

it('ne change plus rien après destruction', async () => {
  const { engine } = await create();

  engine.destroy();
  engine.setTheme('light');
  engine.setPoints([]);

  expect(mocks.map.setStyle).not.toHaveBeenCalled();
  expect(mocks.map.remove).toHaveBeenCalledOnce();
});
