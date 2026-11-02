import {
  infiniteQueryOptions,
  keepPreviousData,
  queryOptions,
} from '@tanstack/react-query';
import {
  countEstablishments,
  readEstablishmentDetail,
  readMapPoints,
  readDatasets,
  readSummaries,
  searchEstablishments,
  suggestWhat,
  suggestWhere,
} from './api';
import type { SearchRequest } from './schemas';

export const searchQueryOptions = (scope: string, request: SearchRequest) =>
  infiniteQueryOptions({
    queryKey: ['map', scope, 'search', request] as const,
    queryFn: ({ pageParam, signal }) =>
      searchEstablishments(request, pageParam, signal),
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      pages.reduce((sum, { items }) => sum + items.length, 0) < last.total
        ? last.page + 1
        : undefined,
    retry: false,
    staleTime: 30_000,
    gcTime: 60_000,
  });
export const pointsQueryOptions = (scope: string, request: SearchRequest) =>
  queryOptions({
    queryKey: ['map', scope, 'points', { ...request, sirets: null }] as const,
    queryFn: ({ signal }) => readMapPoints(request, signal),
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 30_000,
    gcTime: 60_000,
  });
export const detailQueryOptions = (scope: string, siret: string | null) =>
  queryOptions({
    queryKey: ['map', scope, 'detail', siret] as const,
    queryFn: ({ signal }) => readEstablishmentDetail(siret!, signal),
    enabled: siret !== null,
    retry: false,
    staleTime: 60_000,
    gcTime: 60_000,
  });
export const countQueryOptions = (scope: string, request: SearchRequest) =>
  queryOptions({
    queryKey: ['map', scope, 'count', { ...request, sirets: null }] as const,
    queryFn: ({ signal }) => countEstablishments(request, signal),
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 30_000,
    gcTime: 60_000,
  });
export const suggestWhatQueryOptions = (scope: string, text: string) =>
  queryOptions({
    queryKey: ['map', scope, 'suggest-what', text] as const,
    queryFn: ({ signal }) => suggestWhat(text, signal),
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 300_000,
  });
export const suggestWhereQueryOptions = (scope: string, text: string) =>
  queryOptions({
    queryKey: ['map', scope, 'suggest-where', text] as const,
    queryFn: ({ signal }) => suggestWhere(text, signal),
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 300_000,
  });
export const summariesQueryOptions = (scope: string, sirets: string[]) =>
  queryOptions({
    queryKey: ['map', scope, 'summaries', sirets.join(',')] as const,
    queryFn: ({ signal }) => readSummaries(sirets, signal),
    enabled: sirets.length > 0,
    retry: false,
    staleTime: 60_000,
    gcTime: 60_000,
  });
/** The Divia stops and the dates of the open data: they change once a day at most. */
export const datasetsQueryOptions = (scope: string) =>
  queryOptions({
    queryKey: ['map', scope, 'datasets'] as const,
    queryFn: ({ signal }) => readDatasets(signal),
    retry: false,
    staleTime: 3_600_000,
  });
