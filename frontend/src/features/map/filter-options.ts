import type { CompanyType, EstablishmentSize } from './schemas';

export const companyTypes: ReadonlyArray<readonly [CompanyType, string]> = [
  ['COMPANY', 'Entreprises'],
  ['TEMP_AGENCY', 'Travail temporaire'],
  ['RECRUITMENT', 'Cabinets de recrutement'],
  ['TRAINING', 'Centres de formation'],
  ['IT_SERVICES', 'ESN'],
];

export const establishmentSizes: ReadonlyArray<
  readonly [EstablishmentSize, string]
> = [
  ['SMALL', 'Moins de 10 salariés'],
  ['MEDIUM', '10 à 249 salariés'],
  ['LARGE', '250 salariés et plus'],
];
