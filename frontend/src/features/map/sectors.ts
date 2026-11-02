import {
  Briefcase,
  Building2,
  Car,
  Croissant,
  Drama,
  Factory,
  GraduationCap,
  HardHat,
  HeartHandshake,
  Landmark,
  Laptop,
  Leaf,
  Megaphone,
  Scissors,
  ShoppingBag,
  Stethoscope,
  Truck,
  UtensilsCrossed,
  Wheat,
  type LucideIcon,
} from 'lucide-react';

// The proposal of 19 sectors (note 02 of the map redesign): provisional, to be adjusted with the Mission Locale.
export const sectorCodes = [
  'AGRICULTURE',
  'FOOD',
  'INDUSTRY',
  'ENERGY_ENVIRONMENT',
  'CONSTRUCTION',
  'AUTOMOTIVE',
  'RETAIL',
  'TRANSPORT_LOGISTICS',
  'HOSPITALITY_TOURISM',
  'IT_DIGITAL',
  'MEDIA_COMMUNICATION',
  'FINANCE_REAL_ESTATE',
  'BUSINESS_SERVICES',
  'EDUCATION_TRAINING',
  'HEALTH',
  'SOCIAL_PERSONAL_SERVICES',
  'BEAUTY_WELLNESS',
  'CULTURE_SPORT_LEISURE',
  'PUBLIC_SERVICE',
] as const;
export type SectorCode = (typeof sectorCodes)[number];

// Each sector has one colour, for its chip, its visual and its points on the map: 3:1 at least on white;
// a dark background lightens it (`sector-ink`).
export const sectorStyle: Record<
  SectorCode,
  { label: string; color: string; icon: LucideIcon }
> = {
  AGRICULTURE: { label: 'Agriculture · Pêche', color: '#4d7c0f', icon: Wheat },
  FOOD: {
    label: 'Alimentation · Métiers de bouche',
    color: '#c2410c',
    icon: Croissant,
  },
  INDUSTRY: { label: 'Industrie', color: '#57534e', icon: Factory },
  ENERGY_ENVIRONMENT: {
    label: 'Énergie · Environnement',
    color: '#15803d',
    icon: Leaf,
  },
  CONSTRUCTION: { label: 'BTP', color: '#a16207', icon: HardHat },
  AUTOMOTIVE: { label: 'Automobile · Réparation', color: '#b91c1c', icon: Car },
  RETAIL: {
    label: 'Commerce · Distribution',
    color: '#be185d',
    icon: ShoppingBag,
  },
  TRANSPORT_LOGISTICS: {
    label: 'Transport · Logistique',
    color: '#0e7490',
    icon: Truck,
  },
  HOSPITALITY_TOURISM: {
    label: 'Hôtellerie · Restauration · Tourisme',
    color: '#dc2626',
    icon: UtensilsCrossed,
  },
  IT_DIGITAL: {
    label: 'Informatique · Numérique',
    color: '#1e40af',
    icon: Laptop,
  },
  MEDIA_COMMUNICATION: {
    label: 'Média · Communication',
    color: '#c026d3',
    icon: Megaphone,
  },
  FINANCE_REAL_ESTATE: {
    label: 'Banque · Assurance · Immobilier',
    color: '#78350f',
    icon: Landmark,
  },
  BUSINESS_SERVICES: {
    label: 'Services aux entreprises',
    color: '#64748b',
    icon: Briefcase,
  },
  EDUCATION_TRAINING: {
    label: 'Enseignement · Formation',
    color: '#6d28d9',
    icon: GraduationCap,
  },
  HEALTH: { label: 'Santé', color: '#0f766e', icon: Stethoscope },
  SOCIAL_PERSONAL_SERVICES: {
    label: 'Social · Services à la personne',
    color: '#9d174d',
    icon: HeartHandshake,
  },
  BEAUTY_WELLNESS: {
    label: 'Beauté · Bien-être',
    color: '#db2777',
    icon: Scissors,
  },
  CULTURE_SPORT_LEISURE: {
    label: 'Culture · Sport · Loisirs',
    color: '#ea580c',
    icon: Drama,
  },
  PUBLIC_SERVICE: {
    label: 'Service public',
    color: '#334155',
    icon: Building2,
  },
};

export const sectorColors = Object.fromEntries(
  sectorCodes.map((code) => [code, sectorStyle[code].color]),
) as Record<SectorCode, string>;
