import {
  defaultRadiusKm,
  emptySearch,
  maxRadiusKm,
  minRadiusKm,
  companyTypeSchema,
  establishmentSizeSchema,
  sectorCodeSchema,
  siretSchema,
  sortSchema,
  type SearchFilters,
} from './schemas';

/** The longest text a field takes, and the URL keeps. */
export const maxTextLength = 100;
/** The longest job label the URL keeps: some ROME labels run past the text of a field. */
export const maxJobLength = 255;
function listOf<T>(
  values: string[],
  schema: {
    safeParse: (
      value: string,
    ) => { success: true; data: T } | { success: false };
  },
) {
  return values
    .flatMap((value) => {
      const parsed = schema.safeParse(value);
      return parsed.success ? [parsed.data] : [];
    })
    .filter((item, index, all) => all.indexOf(item) === index);
}

const flag = (params: URLSearchParams, name: string) =>
  params.get(name) === '1';

/** Reads the shareable state of the URL; anything invalid falls back to its default. */
export function readSearch(params: URLSearchParams) {
  const sort = sortSchema.safeParse(params.get('tri'));
  const radius = Number(params.get('rayon'));
  const filters: SearchFilters = {
    q: (params.get('q') ?? '').trim().slice(0, maxTextLength),
    job: (params.get('metier') ?? '').trim().slice(0, maxJobLength) || null,
    sectors: listOf(params.getAll('secteur'), sectorCodeSchema),
    types: listOf(params.getAll('type'), companyTypeSchema),
    sizes: listOf(params.getAll('taille'), establishmentSizeSchema),
    transit: flag(params, 'transport'),
    sort: sort.success ? sort.data : null,
    radiusKm:
      Number.isInteger(radius) && radius >= minRadiusKm && radius <= maxRadiusKm
        ? radius
        : defaultRadiusKm,
  };
  const id = siretSchema.safeParse(params.get('etablissement'));
  return { filters, selectedId: id.success ? id.data : null };
}

/** The URL of a search: defaults are left out, and the visitor's position never appears. */
export function writeSearch(filters: SearchFilters, selectedId: string | null) {
  const params = new URLSearchParams();
  if (filters.q) params.set('q', filters.q);
  if (filters.job) params.set('metier', filters.job);
  filters.sectors.forEach((code) => params.append('secteur', code));
  filters.types.forEach((type) => params.append('type', type));
  filters.sizes.forEach((size) => params.append('taille', size));
  if (filters.transit) params.set('transport', '1');
  if (filters.sort) params.set('tri', filters.sort);
  if (filters.radiusKm !== emptySearch.radiusKm)
    params.set('rayon', String(filters.radiusKm));
  if (selectedId) params.set('etablissement', selectedId);
  return params;
}
