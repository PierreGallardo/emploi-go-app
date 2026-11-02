import { http, HttpResponse } from 'msw';
import { afterEach, expect, it, onTestFinished, vi } from 'vitest';
import { api } from '@/api/client';
import { onUnauthorized } from '@/api/unauthorized';
import {
  distanceMeters,
  establishmentDetails,
  located,
} from '@/test/handlers/map';
import { recordRequests, stallRequests } from '@/test/requests';
import { server } from '@/test/server';
import {
  countEstablishments,
  readDeadline,
  readEstablishmentDetail,
  readMapPoints,
  readDatasets,
  readSummaries,
  searchEstablishments,
  suggestWhat,
  suggestWhere,
} from './api';
import { EstablishmentError } from './errors';
import { emptySearch, pageSize, type SearchRequest } from './schemas';

const everywhere: SearchRequest = {
  filters: emptySearch,
  place: null,
  sirets: null,
};
const around: SearchRequest = {
  filters: { ...emptySearch, radiusKm: 3 },
  place: { label: 'Place fictive', lng: 5.0415, lat: 47.322 },
  sirets: null,
};

afterEach(() => {
  vi.useRealTimers();
});

it('envoie les filtres dans l’adresse de la recherche, avec le tri et la page', async () => {
  const requests = recordRequests();

  await searchEstablishments(
    {
      filters: {
        ...emptySearch,
        q: 'boulanger',
        job: 'Pilote fictif',
        sectors: ['FOOD', 'RETAIL'],
        types: ['TRAINING'],
        sizes: ['LARGE'],
        transit: true,
        sort: 'name',
      },
      place: null,
      sirets: null,
    },
    2,
  );

  const [sent] = requests.of('GET', '/api/establishments');
  expect(Object.fromEntries(sent!.search)).toEqual({
    q: 'boulanger',
    job: 'Pilote fictif',
    sector: 'RETAIL',
    companyType: 'TRAINING',
    size: 'LARGE',
    transit: 'true',
    sort: 'NAME',
    page: '2',
    pageSize: String(pageSize),
  });
  expect(sent!.search.getAll('sector')).toEqual(['FOOD', 'RETAIL']);
});

it('envoie le lieu cherché et le rayon dans un header, jamais dans l’adresse', async () => {
  const requests = recordRequests();

  await searchEstablishments(around, 0);
  await readMapPoints(around);

  const sent = [
    ...requests.of('GET', '/api/establishments'),
    ...requests.of('GET', '/api/establishments/points'),
  ];
  expect(sent.map(({ headers }) => headers.get('Search-Area'))).toEqual([
    '5.041500,47.322000,3',
    '5.041500,47.322000,3',
  ]);
  expect(sent.map(({ search }) => search.toString())).not.toContainEqual(
    expect.stringMatching(/47\.322|5\.0415/),
  );
  expect(sent[0]!.search.get('sort')).toBe('DISTANCE');
});

it('n’envoie jamais le libellé du lieu cherché', async () => {
  const requests = recordRequests();

  await searchEstablishments(
    { ...around, place: { ...around.place!, label: '12 rue Secrète, Dijon' } },
    0,
  );

  const [sent] = requests.of('GET', '/api/establishments');
  expect([
    sent!.search.toString(),
    ...sent!.headers.values(),
  ]).not.toContainEqual(expect.stringContaining('Secr'));
});

it('ne reçoit que les points du cercle autour du lieu cherché', async () => {
  const points = await readMapPoints(around);

  expect(
    points.filter(
      ({ longitude, latitude }) =>
        distanceMeters(around.place!, { lng: longitude, lat: latitude }) >
        around.filters.radiusKm * 1000,
    ),
  ).toEqual([]);
  expect(points).not.toHaveLength(0);
});

it('n’envoie aucun lieu quand aucun n’est choisi', async () => {
  const requests = recordRequests();

  await readMapPoints(everywhere);

  expect(
    requests
      .of('GET', '/api/establishments/points')[0]!
      .headers.has('Search-Area'),
  ).toBe(false);
});

it('envoie le texte de « Où ? » dans un header encodé, jamais dans l’adresse', async () => {
  const requests = recordRequests();

  await suggestWhere('Rue de la Liberté');

  const [sent] = requests.of('GET', '/api/places/suggestions');
  expect(sent!.headers.get('Place-Query')).toBe('Rue%20de%20la%20Libert%C3%A9');
  expect(sent!.search.toString()).toBe('');
});

it('compte une recherche par une page d’un seul établissement, sans le regroupement', async () => {
  const requests = recordRequests();

  const total = await countEstablishments({
    ...everywhere,
    sirets: [located.siret],
  });

  expect(total).toBe(establishmentDetails.length);
  const [sent] = requests.of('GET', '/api/establishments');
  expect([sent!.search.get('pageSize'), sent!.search.has('siret')]).toEqual([
    '1',
    false,
  ]);
});

it('lit les résumés d’un regroupement de points par leur SIRET, sans les filtres', async () => {
  const sirets = establishmentDetails.slice(0, 3).map(({ siret }) => siret);

  const summaries = await readSummaries(sirets);

  expect(summaries.map(({ siret }) => siret).sort()).toEqual(
    [...sirets].sort(),
  );
});

it('lit la fiche d’un établissement par son SIRET', async () => {
  expect(await readEstablishmentDetail(located.siret)).toEqual(located);
});

it.each([401, 403, 404, 500, 503])(
  'traduit une réponse %i en erreur d’établissement',
  async (status) => {
    server.use(
      http.get(
        '/api/establishments/:siret',
        () => new HttpResponse(null, { status }),
      ),
    );

    const error = await readEstablishmentDetail(located.siret).catch(
      (failure: unknown) => failure,
    );

    expect(error).toBeInstanceOf(EstablishmentError);
    expect(error).toMatchObject({ status });
  },
);

it('demande au navigateur de ne rien garder en cache pour les lectures', async () => {
  // The spy calls through to the real client: MSW does not see the cache mode, so the option is checked where the client receives it.
  const get = vi.spyOn(api, 'GET');

  await searchEstablishments(everywhere, 0);
  await readMapPoints(everywhere);
  await readEstablishmentDetail(located.siret);
  await suggestWhat('boulanger');
  await suggestWhere('gare');
  await readDatasets();

  expect(
    get.mock.calls.map((call) => (call[1] as { cache?: string }).cache),
  ).toEqual(Array(6).fill('no-store'));
});

const readers = [
  {
    cas: 'la recherche',
    path: '/api/establishments',
    read: (signal?: AbortSignal) => searchEstablishments(everywhere, 0, signal),
  },
  {
    cas: 'une fiche',
    path: `/api/establishments/${located.siret}`,
    read: (signal?: AbortSignal) =>
      readEstablishmentDetail(located.siret, signal),
  },
  {
    cas: 'les données ouvertes de la carte',
    path: '/api/datasets',
    read: (signal?: AbortSignal) => readDatasets(signal),
  },
  {
    cas: 'les points de la carte',
    path: '/api/establishments/points',
    read: (signal?: AbortSignal) => readMapPoints(everywhere, signal),
  },
  {
    cas: 'les suggestions de « Quoi ? »',
    path: '/api/establishments/suggestions',
    read: (signal?: AbortSignal) => suggestWhat('boulanger', signal),
  },
  {
    cas: 'les suggestions de « Où ? »',
    path: '/api/places/suggestions',
    read: (signal?: AbortSignal) => suggestWhere('gare', signal),
  },
] as const;

/** Only the timers: the deadline is a timer, and the network of the tests must keep its own clock. */
const useDeadlineClock = () =>
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });

it.each(readers)(
  'abandonne $cas après 15 s sans réponse, avec une raison de délai',
  async ({ path, read }) => {
    useDeadlineClock();
    const stalled = stallRequests(api, 'GET', path);
    const outcome = read().catch((failure: unknown) => failure);

    await vi.advanceTimersByTimeAsync(15_000 - 1);
    expect(stalled.started()).toBe(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(await outcome).toMatchObject({ name: 'TimeoutError' });
  },
);

it.each(readers)(
  'laisse l’appelant annuler $cas avant le délai, avec sa propre raison',
  async ({ path, read }) => {
    useDeadlineClock();
    const stalled = stallRequests(api, 'GET', path);
    const caller = new AbortController();
    const reason = new Error('annulée');
    const outcome = read(caller.signal).catch((failure: unknown) => failure);
    await vi.waitFor(() => expect(stalled.started()).toBe(1));

    caller.abort(reason);

    expect(await outcome).toBe(reason);
  },
);

it.each(readers)(
  'ne laisse pas courir le délai quand $cas est lu',
  async ({ read }) => {
    useDeadlineClock();
    // Not `vi.getTimerCount()`: the first connection of the file arms a timer of undici on this clock.
    const signals: AbortSignal[] = [];
    const record = {
      onRequest: ({ request }: { request: Request }) => {
        signals.push(request.signal);
      },
    };
    api.use(record);
    onTestFinished(() => api.eject(record));

    await read();
    await vi.advanceTimersByTimeAsync(readDeadline);

    expect(signals).toHaveLength(1);
    expect(signals[0]?.aborted).toBe(false);
  },
);

it('abandonne après 15 s une lecture dont la reconnexion ne répond pas, sans la renvoyer', async () => {
  useDeadlineClock();
  server.use(
    http.get(
      '/api/establishments',
      () => new HttpResponse(null, { status: 401 }),
    ),
  );
  const reconnect = vi.fn(() => new Promise<string | undefined>(() => {}));
  onTestFinished(onUnauthorized({ account: () => 'jeune_fictif', reconnect }));
  const requests = recordRequests();
  const outcome = searchEstablishments(everywhere, 0).catch(
    (failure: unknown) => failure,
  );
  await vi.waitFor(() => expect(reconnect).toHaveBeenCalledOnce());

  await vi.advanceTimersByTimeAsync(15_000);

  expect(await outcome).toMatchObject({ name: 'TimeoutError' });
  expect(requests.of('GET', '/api/establishments')).toHaveLength(1);
});

it('rejoue une recherche autour d’un lieu après la reconnexion, avec son header', async () => {
  let answers = 0;
  server.use(
    http.get('/api/establishments', ({ request }) =>
      ++answers === 1
        ? new HttpResponse(null, { status: 401 })
        : HttpResponse.json({
            items: [],
            total: request.headers.has('Search-Area') ? 0 : -1,
            page: 0,
            pageSize: 20,
          }),
    ),
  );
  onTestFinished(
    onUnauthorized({
      account: () => 'jeune_fictif',
      reconnect: async () => 'jeune_fictif',
    }),
  );

  const page = await searchEstablishments(around, 0);

  expect(page.total).toBe(0);
});
