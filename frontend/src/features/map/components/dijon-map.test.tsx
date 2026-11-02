import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { setTheme } from '@/lib/theme';
import type { EngineOptions, MapEngine } from '../map-engine';
import type { Datasets, MapPoint } from '../schemas';
import { DijonMap } from './dijon-map';

const factory = vi.hoisted(() => vi.fn());
vi.mock('../map-engine', () => ({ createDijonMap: factory }));

const point: MapPoint = {
  siret: '12345678900012',
  longitude: 5.04,
  latitude: 47.32,
  sector: 'FOOD',
};
const insets = { top: 0, right: 0, bottom: 0, left: 0 };
const props = {
  points: [] as MapPoint[],
  pointsUnavailable: false,
  searchKey: 'first',
  pointsSettled: true,
  datasets: null,
  selectedId: null,
  highlightedId: null,
  circle: null,
  place: null,
  insets,
  focus: null,
  onPick: vi.fn(),
  onGroup: vi.fn(),
  onHover: vi.fn(),
};
const engine = () => ({
  setPoints: vi.fn(),
  setDatasets: vi.fn(),
  setSelected: vi.fn(),
  setHighlighted: vi.fn(),
  setCircle: vi.fn(),
  setPlace: vi.fn(),
  fitCircle: vi.fn(),
  fitPoints: vi.fn(),
  setInsets: vi.fn(),
  flyTo: vi.fn(),
  recenter: vi.fn(),
  setTheme: vi.fn(),
  destroy: vi.fn(),
});
const pending = () => {
  let resolve!: (engine: MapEngine) => void;
  factory.mockImplementation(
    () =>
      new Promise<MapEngine>((accept) => {
        resolve = accept;
      }),
  );
  return (instance: MapEngine) => resolve(instance);
};
const options = () => factory.mock.calls[0]![1] as EngineOptions;
afterEach(() => factory.mockReset());

it('crée la carte avec les derniers points arrivés pendant la création asynchrone', async () => {
  const resolve = pending();
  const instance = engine();
  const view = render(<DijonMap {...props} />);
  await waitFor(() => expect(factory).toHaveBeenCalledOnce());
  view.rerender(<DijonMap {...props} points={[point]} />);
  await act(async () => resolve(instance));

  expect(instance.setPoints).toHaveBeenLastCalledWith([point]);
  expect(factory).toHaveBeenCalledOnce();
  view.unmount();
  expect(instance.destroy).toHaveBeenCalledOnce();
});

it('annule le chargement et détruit une instance reçue après démontage', async () => {
  const resolve = pending();
  const instance = engine();
  const view = render(<DijonMap {...props} points={[point]} />);
  await waitFor(() => expect(factory).toHaveBeenCalledOnce());
  const signal = factory.mock.calls[0]![2] as AbortSignal;
  view.unmount();
  expect(signal.aborted).toBe(true);
  await act(async () => resolve(instance));

  expect(instance.destroy).toHaveBeenCalledOnce();
  expect(instance.setPoints).not.toHaveBeenCalled();
});

it('annonce le chargement du fond puis le retire quand la carte est prête', async () => {
  factory.mockResolvedValue(engine());
  render(<DijonMap {...props} />);
  expect(screen.getByRole('status')).toHaveTextContent(
    'Chargement de la carte…',
  );
  await waitFor(() => expect(factory).toHaveBeenCalledOnce());
  await act(async () => options().onState('ready'));

  expect(screen.queryByRole('status')).not.toBeInTheDocument();
});

it('présente un fond indisponible sans message technique, la liste restant consultable', async () => {
  factory.mockRejectedValue(new Error('Détail technique privé'));
  render(<DijonMap {...props} />);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Fond de carte indisponible. La liste reste consultable.',
  );
  expect(screen.queryByText('Détail technique privé')).not.toBeInTheDocument();
});

it('crée la carte avec le thème du site', async () => {
  setTheme('dark');
  factory.mockResolvedValue(engine());
  render(<DijonMap {...props} />);
  await waitFor(() => expect(factory).toHaveBeenCalledOnce());

  expect(options().theme).toBe('dark');
});

it('suit le thème du site quand il change', async () => {
  setTheme('light');
  const instance = engine();
  factory.mockResolvedValue(instance);
  render(<DijonMap {...props} />);
  await waitFor(() => expect(instance.setPoints).toHaveBeenCalled());

  act(() => setTheme('dark'));
  expect(instance.setTheme).toHaveBeenLastCalledWith('dark');
  act(() => setTheme('light'));
  expect(instance.setTheme).toHaveBeenLastCalledWith('light');
});

it('suit la préférence du système quand le site n’impose aucun thème', async () => {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query) =>
      ({
        matches: true,
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }) as unknown as MediaQueryList,
  );
  setTheme('system');
  factory.mockResolvedValue(engine());
  render(<DijonMap {...props} />);
  await waitFor(() => expect(factory).toHaveBeenCalledOnce());

  expect(options().theme).toBe('dark');
});

const mounted = async () => {
  const instance = engine();
  factory.mockResolvedValue(instance);
  const view = render(<DijonMap {...props} />);
  await waitFor(() => expect(instance.setPoints).toHaveBeenCalled());
  return { instance, view };
};

it('transmet à la carte les données ouvertes à sa création, puis quand elles changent', async () => {
  const datasets: Datasets = {
    addressesPublishedAt: null,
    transitPublishedAt: null,
    stops: [],
  };
  const instance = engine();
  factory.mockResolvedValue(instance);
  const view = render(<DijonMap {...props} datasets={datasets} />);
  await waitFor(() => expect(instance.setDatasets).toHaveBeenCalled());

  expect(options().datasets).toEqual(datasets);
  const next = { ...datasets, transitPublishedAt: '2026-10-01T00:00:00Z' };
  view.rerender(<DijonMap {...props} datasets={next} />);
  expect(instance.setDatasets).toHaveBeenLastCalledWith(next);
});

it('met en évidence l’établissement sélectionné et celui que la liste survole', async () => {
  const { instance, view } = await mounted();

  view.rerender(<DijonMap {...props} selectedId="a" highlightedId="b" />);

  expect(instance.setSelected).toHaveBeenLastCalledWith('a');
  expect(instance.setHighlighted).toHaveBeenLastCalledWith('b');
});

it('répercute le lieu et le rayon choisis, et cadre le rayon', async () => {
  const { instance, view } = await mounted();
  const circle = { lng: 5, lat: 47.3, km: 5 };

  view.rerender(
    <DijonMap {...props} circle={circle} place={{ lng: 5, lat: 47.3 }} />,
  );

  expect(instance.setCircle).toHaveBeenLastCalledWith(circle);
  expect(instance.setPlace).toHaveBeenLastCalledWith({ lng: 5, lat: 47.3 });
  expect(instance.fitCircle).toHaveBeenLastCalledWith(circle);
});

it('répercute sur la carte les marges occupées par le panneau', async () => {
  const { instance, view } = await mounted();
  const next = { top: 0, right: 424, bottom: 0, left: 0 };

  view.rerender(<DijonMap {...props} insets={next} />);

  expect(instance.setInsets).toHaveBeenLastCalledWith(next);
});

it('amène la carte à un établissement une seule fois, même quand la page le redonne', async () => {
  const { instance, view } = await mounted();
  await act(async () => options().onState('ready'));

  const first = { id: 'a', lng: 5.04, lat: 47.32 };
  view.rerender(<DijonMap {...props} focus={first} />);
  view.rerender(<DijonMap {...props} focus={{ ...first }} />);

  expect(instance.flyTo).toHaveBeenCalledOnce();
});

it('amène la carte de nouveau à un établissement déjà amené, après qu’il a été relâché', async () => {
  const { instance, view } = await mounted();
  await act(async () => options().onState('ready'));

  const first = { id: 'a', lng: 5.04, lat: 47.32 };
  view.rerender(<DijonMap {...props} focus={first} />);
  view.rerender(<DijonMap {...props} focus={null} />);
  view.rerender(<DijonMap {...props} focus={first} />);

  expect(instance.flyTo).toHaveBeenCalledTimes(2);
});

it('transmet à la page le clic sur un point et sur un regroupement', async () => {
  factory.mockResolvedValue(engine());
  render(<DijonMap {...props} />);
  await waitFor(() => expect(factory).toHaveBeenCalledOnce());

  options().onPick('a');
  options().onGroup(['a', 'b']);

  expect(props.onPick).toHaveBeenCalledWith('a');
  expect(props.onGroup).toHaveBeenCalledWith(['a', 'b']);
});

const readyMap = async (more = {}) => {
  const instance = engine();
  factory.mockResolvedValue(instance);
  const view = render(<DijonMap {...props} points={[point]} {...more} />);
  await waitFor(() => expect(instance.setPoints).toHaveBeenCalled());
  await act(async () => options().onState('ready'));
  return { instance, view };
};

it('cadre les points une fois par nouvelle recherche, quand ceux de cette recherche sont arrivés', async () => {
  const { instance, view } = await readyMap();
  expect(instance.fitPoints).not.toHaveBeenCalled();

  view.rerender(
    <DijonMap
      {...props}
      points={[point]}
      searchKey="second"
      pointsSettled={false}
    />,
  );
  expect(instance.fitPoints).not.toHaveBeenCalled();
  view.rerender(<DijonMap {...props} points={[point]} searchKey="second" />);
  view.rerender(
    <DijonMap
      {...props}
      points={[point, { ...point, siret: '2' }]}
      searchKey="second"
    />,
  );

  expect(instance.fitPoints).toHaveBeenCalledOnce();
  expect(instance.fitPoints).toHaveBeenCalledWith([point]);
});

it('ne cadre pas les points quand un lieu ou un établissement est choisi', async () => {
  const { instance, view } = await readyMap();
  const circle = { lng: 5, lat: 47.3, km: 2 };

  view.rerender(
    <DijonMap {...props} points={[point]} searchKey="place" circle={circle} />,
  );
  view.rerender(
    <DijonMap {...props} points={[point]} searchKey="company" selectedId="a" />,
  );

  expect(instance.fitPoints).not.toHaveBeenCalled();
});
