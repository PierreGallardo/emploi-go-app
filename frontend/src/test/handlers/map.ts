import { http, HttpResponse } from 'msw';
import { sectorCodes } from '@/features/map/sectors';
import {
  maxGroupSize,
  pageSize as defaultPageSize,
  transitMaxMeters,
  type CompanyType,
  type EstablishmentDetail,
  type EstablishmentSize,
  type EstablishmentSummary,
  type Datasets,
  type PlaceSuggestion,
  type WhatSuggestions,
} from '@/features/map/schemas';
import {
  adjectives,
  bounds,
  catalogue,
  fakeStops,
  headcounts,
  places,
  streets,
} from './map/catalogue';

// Fictitious establishments in the shape of the API of the map, for the tests of the screens: no real
// company, person or identifier is used. The search answers like the API does, roughly: the precise
// rules (accents, typing errors, jobs of a sector) are the backend's, tested there.

/** A small seeded generator: the same fictitious set on every run. */
function random(seed: number) {
  let state = seed;
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function distanceMeters(
  a: { lng: number; lat: number },
  b: { lng: number; lat: number },
) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(h));
}

/** What the API knows of an establishment, beyond its page: what the filters read. */
export type Fictitious = {
  detail: EstablishmentDetail;
  companyType: CompanyType;
  size: EstablishmentSize;
};

const count = 400;
const next = random(321);
const pick = <T>(items: readonly T[]) =>
  items[Math.floor(next() * items.length)]!;
const clamp = (value: number, min: number, max: number) =>
  Math.min(max, Math.max(min, value));
const weighted = places.flatMap((place) =>
  Array<typeof place>(place.weight).fill(place),
);
const companyTypeOf = (number: number): CompanyType =>
  number % 10 === 0
    ? 'TEMP_AGENCY'
    : number % 13 === 0
      ? 'TRAINING'
      : number % 17 === 0
        ? 'RECRUITMENT'
        : number % 19 === 0
          ? 'IT_SERVICES'
          : 'COMPANY';

function nearestStop(point: { lng: number; lat: number }) {
  const [best] = fakeStops
    .map((stop) => ({
      name: stop.name,
      mode: stop.mode,
      distanceMeters: Math.round(distanceMeters(point, stop)),
    }))
    .sort((a, b) => a.distanceMeters - b.distanceMeters);
  return best!;
}

export const fictitiousEstablishments: Fictitious[] = Array.from(
  { length: count },
  (_, index) => {
    const number = index + 1;
    const sector = sectorCodes[Math.floor(next() * sectorCodes.length)]!;
    const kind = catalogue[sector];
    const place = pick(weighted);
    const located = number % 50 !== 0;
    const lng = clamp(
      place.lng + (next() - 0.5) * 2 * place.spread * 1.4,
      bounds.west + 0.01,
      bounds.east - 0.01,
    );
    const lat = clamp(
      place.lat + (next() - 0.5) * 2 * place.spread,
      bounds.south + 0.01,
      bounds.north - 0.01,
    );
    const jobs = [
      ...new Set(
        Array.from({ length: 2 + Math.floor(next() * 4) }, () =>
          pick(kind.professions),
        ),
      ),
    ];
    const noun = pick(kind.nouns);
    const name = `${noun} ${pick(adjectives)} n°${number}`;
    const [headcountRange, size] = pick(headcounts);
    return {
      companyType: companyTypeOf(number),
      size,
      detail: {
        siret: String(number).padStart(9, '0') + '00012',
        name,
        companyName: number % 4 === 0 ? `${name} Exemple SAS` : name,
        sector,
        activity: pick(kind.activities),
        address: `${1 + Math.floor(next() * 80)} rue d’Exemple, ${place.name}`,
        commune: place.name,
        longitude: located ? lng : null,
        latitude: located ? lat : null,
        description:
          number % 7 === 0
            ? null
            : `Établissement fictif de démonstration (${noun.toLowerCase()}). ${jobs.join(', ')} : des métiers à découvrir près de chez vous.`,
        imageUrl: null,
        jobsUrl: next() < 0.6 ? `https://example.org/emplois/${number}` : null,
        jobs,
        headcountRange,
        companyCategory: 'PME',
        companyCreationYear: 1965 + Math.floor(next() * 60),
        ess: next() < 0.12,
        nearestStop: located ? nearestStop({ lng, lat }) : null,
      },
    };
  },
);

export const establishmentDetails: EstablishmentDetail[] =
  fictitiousEstablishments.map(({ detail }) => detail);
export const located = establishmentDetails[0]!;

const summary = (
  detail: EstablishmentDetail,
  distance: number | null,
): EstablishmentSummary => ({
  siret: detail.siret,
  name: detail.name,
  sector: detail.sector,
  commune: detail.commune,
  longitude: detail.longitude,
  latitude: detail.latitude,
  description: detail.description,
  imageUrl: detail.imageUrl,
  jobs: detail.jobs.slice(0, 4),
  distanceMeters: distance,
});

const fold = (text: string) =>
  [...text]
    .map((char) => char.normalize('NFD').replace(/[̀-ͯ]/g, ''))
    .join('')
    .toLowerCase();

/** `longitude,latitude,radius` of the header `Search-Area`, as the API reads it. */
function areaOf(request: Request) {
  const header = request.headers.get('Search-Area');
  if (!header) return null;
  const [lng, lat, km] = header.split(',').map(Number);
  return { lng: lng!, lat: lat!, metres: km! * 1000 };
}

/** The fictitious establishments that meet the filters of a request, in its order. */
export function searchEstablishments(request: Request) {
  const query = new URL(request.url).searchParams;
  const terms = fold(query.get('q') ?? '')
    .split(/\s+/)
    .filter(Boolean);
  const job = query.get('job');
  const sectors = query.getAll('sector');
  const types = query.getAll('companyType');
  const sizes = query.getAll('size');
  const sirets = query.getAll('siret');
  const area = areaOf(request);
  const rows = fictitiousEstablishments.flatMap(
    ({ detail, companyType, size }) => {
      const text = fold(
        `${detail.name} ${detail.companyName} ${detail.activity} ${detail.jobs.join(' ')}`,
      );
      const where =
        detail.longitude === null || detail.latitude === null
          ? null
          : { lng: detail.longitude, lat: detail.latitude };
      const distance =
        area && where ? Math.round(distanceMeters(area, where)) : null;
      const kept =
        (sirets.length === 0 || sirets.includes(detail.siret)) &&
        terms.every((term) => text.includes(term)) &&
        (job === null || detail.jobs.includes(job)) &&
        (sectors.length === 0 || sectors.includes(detail.sector)) &&
        (types.length === 0 || types.includes(companyType)) &&
        (sizes.length === 0 || sizes.includes(size)) &&
        (query.get('transit') !== 'true' ||
          (detail.nearestStop?.distanceMeters ?? Infinity) <=
            transitMaxMeters) &&
        (area === null || (distance !== null && distance <= area.metres));
      return kept ? [{ detail, distance }] : [];
    },
  );
  const sort = query.get('sort') ?? 'RELEVANCE';
  // Relevance as the API ranks it, roughly: the names that hold the text first.
  const inName = ({ detail }: (typeof rows)[number]) =>
    terms.length > 0 && terms.every((term) => fold(detail.name).includes(term))
      ? 0
      : 1;
  return rows.sort(
    (a, b) =>
      (sort === 'DISTANCE' ? (a.distance ?? 0) - (b.distance ?? 0) : 0) ||
      (sort === 'RELEVANCE' ? inName(a) - inName(b) : 0) ||
      a.detail.name.localeCompare(b.detail.name, 'fr'),
  );
}

const fakeMaxJobsPerSector = 4;
const fakeMaxSectors = 5;
const fakeMaxEstablishments = 4;

/** The jobs of the fictitious establishments that contain the words typed, each under its sector; then establishments. */
export function suggestWhat(text: string): WhatSuggestions {
  const terms = fold(text).split(/\s+/).filter(Boolean);
  if (terms.join('').length < 2) return { jobs: [], establishments: [] };
  const groups = new Map<(typeof sectorCodes)[number], string[]>();
  const seen = new Set<string>();
  for (const sector of sectorCodes)
    for (const job of catalogue[sector].professions) {
      if (seen.has(job) || !terms.every((term) => fold(job).includes(term)))
        continue;
      seen.add(job);
      const group = groups.get(sector) ?? [];
      if (group.length < fakeMaxJobsPerSector) group.push(job);
      groups.set(sector, group);
    }
  const establishments = establishmentDetails
    .filter(({ name }) => terms.every((term) => fold(name).includes(term)))
    .sort((a, b) => a.name.localeCompare(b.name, 'fr'))
    .slice(0, fakeMaxEstablishments)
    .map(({ siret, name, sector }) => ({ siret, name, sector }));
  return {
    jobs: [...groups]
      .slice(0, fakeMaxSectors)
      .map(([sector, jobs]) => ({ sector, jobs })),
    establishments,
  };
}

/** Communes, streets and stops that contain the words typed. */
export function suggestWhere(text: string): PlaceSuggestion[] {
  const terms = fold(text).split(/\s+/).filter(Boolean);
  if (terms.join('').length < 2) return [];
  const matches = (label: string) =>
    terms.every((term) => fold(label).includes(term));
  const byName = <T extends { name: string }>(items: readonly T[]) =>
    items
      .filter(({ name }) => matches(name))
      .sort((a, b) => a.name.localeCompare(b.name, 'fr'));
  return [
    ...byName(places)
      .slice(0, 3)
      .map(({ name, lng, lat }) => ({
        kind: 'COMMUNE' as const,
        label: name,
        detail: null,
        mode: null,
        longitude: lng,
        latitude: lat,
      })),
    ...streets
      .filter(({ name, commune }) => matches(`${name} ${commune}`))
      .slice(0, 5)
      .map(({ name, commune, lng, lat }) => ({
        kind: 'STREET' as const,
        label: name,
        detail: commune,
        mode: null,
        longitude: lng,
        latitude: lat,
      })),
    ...byName(fakeStops)
      .slice(0, 5)
      .map(({ name, mode, lines, lng, lat }) => ({
        kind: 'STOP' as const,
        label: name,
        detail: lines.slice(0, 4).join(', '),
        mode,
        longitude: lng,
        latitude: lat,
      })),
  ];
}

const datasets: Datasets = {
  stops: fakeStops.map(({ name, mode, lng, lat }) => ({
    name,
    mode,
    longitude: lng,
    latitude: lat,
  })),
  transitPublishedAt: '2026-01-02T09:00:00Z',
  addressesPublishedAt: '2026-01-03T05:00:00Z',
};

const notFound = () =>
  HttpResponse.json(
    {
      code: 'ESTABLISHMENT_NOT_FOUND',
      message: 'Cet établissement n’est pas sur la carte.',
    },
    { status: 404 },
  );

export const mapHandlers = [
  http.get('/api/establishments/points', ({ request }) => {
    const rows = searchEstablishments(request)
      .filter(({ detail }) => detail.longitude !== null)
      .sort((a, b) => a.detail.siret.localeCompare(b.detail.siret));
    return HttpResponse.json({
      items: rows.map(({ detail }) => ({
        siret: detail.siret,
        longitude: detail.longitude!,
        latitude: detail.latitude!,
        sector: detail.sector,
      })),
    });
  }),
  http.get('/api/establishments/suggestions', ({ request }) =>
    HttpResponse.json(
      suggestWhat(new URL(request.url).searchParams.get('q') ?? ''),
    ),
  ),
  http.get('/api/establishments/:siret', ({ params }) => {
    const found = establishmentDetails.find(
      ({ siret }) => siret === params.siret,
    );
    return found ? HttpResponse.json(found) : notFound();
  }),
  http.get('/api/establishments', ({ request }) => {
    const query = new URL(request.url).searchParams;
    const pageSize = Number(query.get('pageSize') ?? defaultPageSize);
    // The bounds of the API: a client beyond them gets its refusal.
    if (
      pageSize > 50 ||
      query.getAll('siret').length > maxGroupSize ||
      (query.get('sort') === 'DISTANCE' && !request.headers.has('Search-Area'))
    )
      return HttpResponse.json(
        { code: 'INVALID_INPUT', message: 'Vérifie la recherche.' },
        { status: 400 },
      );
    const rows = searchEstablishments(request);
    const page = Number(query.get('page') ?? 0);
    return HttpResponse.json({
      items: rows
        .slice(page * pageSize, (page + 1) * pageSize)
        .map(({ detail, distance }) => summary(detail, distance)),
      total: rows.length,
      page,
      pageSize,
    });
  }),
  http.get('/api/places/suggestions', ({ request }) =>
    HttpResponse.json({
      places: suggestWhere(
        decodeURIComponent(request.headers.get('Place-Query') ?? ''),
      ),
    }),
  ),
  http.get('/api/datasets', () => HttpResponse.json(datasets)),
];
