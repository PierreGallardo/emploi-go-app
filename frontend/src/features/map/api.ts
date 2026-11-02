import { api } from '@/api/client';
import { withDeadline } from '@/lib/deadline';
import { EstablishmentError } from './errors';
import {
  effectiveSort,
  emptySearch,
  pageSize,
  type EstablishmentDetail,
  type EstablishmentSummary,
  type MapPoint,
  type Datasets,
  type PlaceSuggestion,
  type SearchPage,
  type SearchRequest,
  type Sort,
  type WhatSuggestions,
} from './schemas';

/** Covers the reconnection a 401 starts and the replay of the read (`api/unauthorized.ts`): the reconnection goes on without this read, and the replay finds it done. */
export const readDeadline = 15_000;

type Outcome<D> = { data?: D; response: Response };

const apiSorts = {
  relevance: 'RELEVANCE',
  distance: 'DISTANCE',
  name: 'NAME',
} as const satisfies Record<Sort, string>;

/** A read of the map; a refusal or an answer without body stays an error. */
async function read<D>(
  signal: AbortSignal | undefined,
  send: (signal: AbortSignal) => Promise<Outcome<D>>,
): Promise<D> {
  return withDeadline(readDeadline, signal, async (signal) => {
    const { data, response } = await send(signal);
    if (!response.ok || data === undefined)
      throw new EstablishmentError(response.status);
    return data;
  });
}

/** The filters of a search in the query of the URL, and the place in a header: a position never travels in a URL. */
function searchParameters({ filters, place }: SearchRequest) {
  return {
    query: {
      q: filters.q || undefined,
      job: filters.job ?? undefined,
      sector: filters.sectors.length ? filters.sectors : undefined,
      companyType: filters.types.length ? filters.types : undefined,
      size: filters.sizes.length ? filters.sizes : undefined,
      transit: filters.transit || undefined,
    },
    header: {
      // Six decimals place a point within 10 cm: the API reads no more.
      'Search-Area': place
        ? `${place.lng.toFixed(6)},${place.lat.toFixed(6)},${filters.radiusKm}`
        : undefined,
    },
  };
}

async function searchPage(
  request: SearchRequest,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<SearchPage> {
  const { query, header } = searchParameters(request);
  return read(signal, (signal) =>
    api.GET('/api/establishments', {
      params: {
        query: {
          ...query,
          siret: request.sirets ?? undefined,
          sort: apiSorts[effectiveSort(request.filters, request.place)],
          page,
          pageSize: size,
        },
        header,
      },
      signal,
      cache: 'no-store',
    }),
  );
}

export const searchEstablishments = (
  request: SearchRequest,
  page: number,
  signal?: AbortSignal,
) => searchPage(request, page, pageSize, signal);

/** The establishments a search would list, for the button of the filters. */
export async function countEstablishments(
  request: SearchRequest,
  signal?: AbortSignal,
): Promise<number> {
  return (await searchPage({ ...request, sirets: null }, 0, 1, signal)).total;
}

/** The summaries of a few establishments, for the tooltip of the map. */
export async function readSummaries(
  sirets: string[],
  signal?: AbortSignal,
): Promise<EstablishmentSummary[]> {
  const request = { filters: emptySearch, place: null, sirets };
  return (await searchPage(request, 0, sirets.length, signal)).items;
}

export async function readMapPoints(
  request: SearchRequest,
  signal?: AbortSignal,
): Promise<MapPoint[]> {
  const { query, header } = searchParameters(request);
  const points = await read(signal, (signal) =>
    api.GET('/api/establishments/points', {
      params: { query, header },
      signal,
      cache: 'no-store',
    }),
  );
  return points.items;
}

export async function readEstablishmentDetail(
  siret: string,
  signal?: AbortSignal,
): Promise<EstablishmentDetail> {
  return read(signal, (signal) =>
    api.GET('/api/establishments/{siret}', {
      params: { path: { siret } },
      signal,
      cache: 'no-store',
    }),
  );
}

export async function suggestWhat(
  text: string,
  signal?: AbortSignal,
): Promise<WhatSuggestions> {
  return read(signal, (signal) =>
    api.GET('/api/establishments/suggestions', {
      params: { query: { q: text } },
      signal,
      cache: 'no-store',
    }),
  );
}

/** What is typed in « Où ? » may be where the visitor lives: it travels in a header, never in the URL. */
export async function suggestWhere(
  text: string,
  signal?: AbortSignal,
): Promise<PlaceSuggestion[]> {
  const suggestions = await read(signal, (signal) =>
    api.GET('/api/places/suggestions', {
      params: { header: { 'Place-Query': encodeURIComponent(text) } },
      signal,
      cache: 'no-store',
    }),
  );
  return suggestions.places;
}

/** The Divia stops and the dates of the open data shown on the map. */
export async function readDatasets(signal?: AbortSignal): Promise<Datasets> {
  return read(signal, (signal) =>
    api.GET('/api/datasets', { signal, cache: 'no-store' }),
  );
}
