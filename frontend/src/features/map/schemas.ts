import { z } from 'zod';
import type { components, paths } from '@/api/schema';
import { sectorCodes, type SectorCode } from './sectors';

type Schemas = components['schemas'];
type SearchQuery = NonNullable<
  paths['/api/establishments']['get']['parameters']['query']
>;

export type EstablishmentSummary = Schemas['EstablishmentSummaryResponse'];
export type EstablishmentDetail = Schemas['EstablishmentDetailResponse'];
export type SearchPage = Schemas['EstablishmentPageResponse'];
export type MapPoint = Schemas['EstablishmentPointResponse'];
export type WhatSuggestions = Schemas['WhatSuggestionsResponse'];
export type PlaceSuggestion = Schemas['PlaceSuggestionResponse'];
export type Datasets = Schemas['DatasetsResponse'];
export type TransitMode = Datasets['stops'][number]['mode'];

export const sectorCodeSchema = z.enum(sectorCodes);
export type { SectorCode };

/** The sorts of the URL (`tri`), in lower case; the API takes them in capitals. */
export const sortSchema = z.enum(['relevance', 'distance', 'name']);
export type Sort = z.infer<typeof sortSchema>;

export const companyTypeSchema = z.enum([
  'COMPANY',
  'TEMP_AGENCY',
  'RECRUITMENT',
  'TRAINING',
  'IT_SERVICES',
]);
export type CompanyType = z.infer<typeof companyTypeSchema>;
export const establishmentSizeSchema = z.enum(['SMALL', 'MEDIUM', 'LARGE']);
export type EstablishmentSize = z.infer<typeof establishmentSizeSchema>;

// The values of the URL are the values of the API: a change of the contract fails the build here.
type SameValues<A, B> = [A] extends [B]
  ? [B] extends [A]
    ? true
    : never
  : never;
true satisfies SameValues<
  SectorCode,
  NonNullable<SearchQuery['sector']>[number]
>;
true satisfies SameValues<
  CompanyType,
  NonNullable<SearchQuery['companyType']>[number]
>;
true satisfies SameValues<
  EstablishmentSize,
  NonNullable<SearchQuery['size']>[number]
>;

export const pageSize = 20;
/** The most points of a group the list shows: what the API takes at once. */
export const maxGroupSize = 200;
/** An establishment is "accessible en transports" when a Divia stop lies within this distance. */
export const transitMaxMeters = 300;
export const minRadiusKm = 1;
// Around 6 km a circle fills the visible map at its widest zoom-out, whatever the screen: farther is pointless.
export const maxRadiusKm = 6;
export const defaultRadiusKm = 5;

export type Coordinates = { lng: number; lat: number };

/** The position of an establishment of the API, when it has one. */
export const positionOf = ({
  longitude,
  latitude,
}: {
  longitude: number | null;
  latitude: number | null;
}): Coordinates | null =>
  longitude === null || latitude === null
    ? null
    : { lng: longitude, lat: latitude };

/** The filters of the URL: the visitor's position never is one of them. */
export type SearchFilters = {
  q: string;
  /** The label of a job of the ROME / NAF table: the establishments that do it. */
  job: string | null;
  sectors: SectorCode[];
  types: CompanyType[];
  sizes: EstablishmentSize[];
  transit: boolean;
  sort: Sort | null;
  radiusKm: number;
};

/** What « Quoi ? » searches: a text, or a job title of the ROME / NAF table. */
export type What = Pick<SearchFilters, 'q' | 'job'>;

/** A suggestion of « Quoi ? »: a company also carries its SIRET, to open its fiche. */
export type WhatPick = What & { siret?: string };

/** Where the visitor searches from. Kept in memory only: it never reaches the URL. */
export type Place = { label: string } & Coordinates;

/** The place of a suggestion of « Où ? ». */
export const placeOf = ({
  label,
  longitude,
  latitude,
}: PlaceSuggestion): Place => ({ label, lng: longitude, lat: latitude });

export type SearchRequest = {
  filters: SearchFilters;
  place: Place | null;
  /** The establishments of a group of points, by SIRET. */
  sirets: string[] | null;
};

export const emptySearch: SearchFilters = {
  q: '',
  job: null,
  sectors: [],
  types: [],
  sizes: [],
  transit: false,
  sort: null,
  radiusKm: defaultRadiusKm,
};

export function effectiveSort(
  filters: Pick<SearchFilters, 'q' | 'job' | 'sort'>,
  place: Place | null,
): Sort {
  if (filters.sort === 'distance' && !place) return 'relevance';
  if (filters.sort) return filters.sort;
  if (place) return 'distance';
  return filters.q || filters.job ? 'relevance' : 'name';
}

export function activeFilterCount(filters: SearchFilters) {
  return (
    filters.sectors.length +
    filters.types.length +
    filters.sizes.length +
    Number(filters.transit)
  );
}

export const siretSchema = z.string().regex(/^\d{14}$/);
