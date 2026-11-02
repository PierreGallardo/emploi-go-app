import type {
  EstablishmentSize,
  SectorCode,
  TransitMode,
} from '@/features/map/schemas';

// The fictitious world of the tests: places of the metropole, streets, stops, and the kinds of establishments of each sector.

export const bounds = {
  west: 4.8665,
  south: 47.1896,
  east: 5.2358,
  north: 47.4278,
};

export const places: ReadonlyArray<{
  name: string;
  lng: number;
  lat: number;
  spread: number;
  weight: number;
}> = [
  { name: 'Dijon', lng: 5.0415, lat: 47.322, spread: 0.02, weight: 10 },
  { name: 'Talant', lng: 5.0, lat: 47.337, spread: 0.009, weight: 2 },
  { name: 'Chenôve', lng: 5.0, lat: 47.288, spread: 0.01, weight: 2 },
  { name: 'Longvic', lng: 5.067, lat: 47.292, spread: 0.01, weight: 2 },
  { name: 'Quetigny', lng: 5.1, lat: 47.312, spread: 0.01, weight: 2 },
  {
    name: 'Fontaine-lès-Dijon',
    lng: 5.015,
    lat: 47.358,
    spread: 0.008,
    weight: 1,
  },
  {
    name: 'Saint-Apollinaire',
    lng: 5.073,
    lat: 47.335,
    spread: 0.008,
    weight: 2,
  },
  {
    name: 'Marsannay-la-Côte',
    lng: 4.989,
    lat: 47.27,
    spread: 0.01,
    weight: 1,
  },
  {
    name: 'Chevigny-Saint-Sauveur',
    lng: 5.123,
    lat: 47.3,
    spread: 0.01,
    weight: 1,
  },
  { name: 'Ahuy', lng: 5.06, lat: 47.375, spread: 0.006, weight: 1 },
  {
    name: 'Plombières-lès-Dijon',
    lng: 4.95,
    lat: 47.335,
    spread: 0.006,
    weight: 1,
  },
  { name: 'Daix', lng: 5.005, lat: 47.365, spread: 0.005, weight: 1 },
  {
    name: 'Neuilly-lès-Dijon',
    lng: 5.078,
    lat: 47.27,
    spread: 0.006,
    weight: 1,
  },
  { name: 'Ouges', lng: 5.1, lat: 47.265, spread: 0.006, weight: 1 },
];

/** The streets the "Où ?" field of the tests knows. */
export const streets: ReadonlyArray<{
  name: string;
  commune: string;
  lng: number;
  lat: number;
}> = [
  { name: 'Place Darcy', commune: 'Dijon', lng: 5.0345, lat: 47.3236 },
  {
    name: 'Place de la République',
    commune: 'Dijon',
    lng: 5.0447,
    lat: 47.3265,
  },
  { name: 'Place Wilson', commune: 'Dijon', lng: 5.0407, lat: 47.3299 },
  {
    name: 'Place de la Libération',
    commune: 'Dijon',
    lng: 5.0414,
    lat: 47.3225,
  },
  { name: 'Avenue Victor Hugo', commune: 'Dijon', lng: 5.0395, lat: 47.3225 },
  { name: 'Rue de la Liberté', commune: 'Dijon', lng: 5.0407, lat: 47.3225 },
  { name: 'Rue Monge', commune: 'Dijon', lng: 5.0505, lat: 47.3195 },
  {
    name: 'Rue Jean-Jacques Rousseau',
    commune: 'Dijon',
    lng: 5.0351,
    lat: 47.3235,
  },
  { name: 'Rue de Mulhouse', commune: 'Dijon', lng: 5.0302, lat: 47.3186 },
  { name: 'Avenue du Drapeau', commune: 'Dijon', lng: 5.0378, lat: 47.3195 },
  {
    name: 'Boulevard de Strasbourg',
    commune: 'Dijon',
    lng: 5.0518,
    lat: 47.3253,
  },
  { name: 'Rue des Godrans', commune: 'Dijon', lng: 5.0421, lat: 47.3223 },
  { name: 'Avenue de Langres', commune: 'Dijon', lng: 5.0587, lat: 47.3338 },
  { name: 'Avenue Jean Jaurès', commune: 'Dijon', lng: 5.0332, lat: 47.3187 },
  { name: 'Rue de Talant', commune: 'Dijon', lng: 5.0201, lat: 47.3312 },
  {
    name: 'Avenue Roland Carraz',
    commune: 'Chenôve',
    lng: 5.0025,
    lat: 47.2905,
  },
  { name: 'Rue du Mail', commune: 'Chenôve', lng: 5.001, lat: 47.2895 },
  {
    name: 'Avenue de Bourgogne',
    commune: 'Quetigny',
    lng: 5.0995,
    lat: 47.3125,
  },
  { name: 'Rue de la Mairie', commune: 'Talant', lng: 5.0, lat: 47.3375 },
  {
    name: 'Avenue de Stalingrad',
    commune: 'Longvic',
    lng: 5.0685,
    lat: 47.2925,
  },
];

export const fakeStops: ReadonlyArray<{
  name: string;
  mode: TransitMode;
  lines: string[];
  lng: number;
  lat: number;
}> = [
  {
    name: 'Darcy',
    mode: 'TRAM',
    lines: ['T1', 'T2'],
    lng: 5.0345,
    lat: 47.3236,
  },
  {
    name: 'République',
    mode: 'TRAM',
    lines: ['T1', 'T2'],
    lng: 5.0447,
    lat: 47.3265,
  },
  { name: 'Monge', mode: 'BUS', lines: ['L5'], lng: 5.0505, lat: 47.3195 },
  {
    name: 'Gare',
    mode: 'TRAM',
    lines: ['T1', 'T2'],
    lng: 5.0275,
    lat: 47.3228,
  },
  { name: 'Grésilles', mode: 'TRAM', lines: ['T1'], lng: 5.0585, lat: 47.3365 },
  { name: 'Valmy', mode: 'TRAM', lines: ['T1'], lng: 5.0585, lat: 47.3335 },
  { name: 'Toison d’Or', mode: 'TRAM', lines: ['T2'], lng: 5.0, lat: 47.3365 },
  {
    name: 'Auditorium',
    mode: 'TRAM',
    lines: ['T1'],
    lng: 5.0375,
    lat: 47.3188,
  },
  {
    name: 'Chenôve Centre',
    mode: 'TRAM',
    lines: ['T1'],
    lng: 5.0,
    lat: 47.2905,
  },
  {
    name: 'Quetigny Centre',
    mode: 'TRAM',
    lines: ['T2'],
    lng: 5.0995,
    lat: 47.3125,
  },
  {
    name: 'Longvic Mairie',
    mode: 'BUS',
    lines: ['L4'],
    lng: 5.0685,
    lat: 47.2925,
  },
  { name: 'Zénith', mode: 'BUS', lines: ['L6'], lng: 5.0845, lat: 47.3395 },
];

type Kind = {
  nouns: string[];
  activities: string[];
  professions: string[];
};

export const catalogue: Record<SectorCode, Kind> = {
  AGRICULTURE: {
    nouns: ['Ferme', 'Domaine', 'Exploitation', 'Maraîchage', 'Vignoble'],
    activities: [
      'Culture de céréales',
      'Maraîchage',
      'Viticulture',
      'Élevage de bovins',
    ],
    professions: ['Ouvrier agricole', 'Viticulteur', 'Maraîcher', 'Éleveur'],
  },
  FOOD: {
    nouns: [
      'Boulangerie',
      'Boucherie',
      'Pâtisserie',
      'Chocolaterie',
      'Primeur',
    ],
    activities: [
      'Boulangerie-pâtisserie',
      'Pâtisserie',
      'Charcuterie',
      'Boucherie',
      'Chocolaterie',
      'Fruits et légumes',
    ],
    professions: [
      'Boulanger',
      'Pâtissier',
      'Boucher',
      'Vendeur',
      'Chocolatier',
    ],
  },
  INDUSTRY: {
    nouns: [
      'Manufacture',
      'Fabrique',
      'Usine',
      'Atelier industriel',
      'Mécanique',
    ],
    activities: [
      'Mécanique industrielle',
      'Pièces en matière plastique',
      'Machines pour l’industrie',
      'Fabrication de fromage',
    ],
    professions: [
      'Opérateur de production',
      'Soudeur',
      'Technicien de maintenance',
      'Magasinier',
    ],
  },
  ENERGY_ENVIRONMENT: {
    nouns: ['Énergies', 'Recyclage', 'Eaux', 'Environnement', 'Tri'],
    activities: [
      'Collecte des déchets',
      'Récupération de déchets triés',
      'Dépollution',
      'Distribution d’eau',
    ],
    professions: [
      'Agent de tri',
      'Technicien de traitement des eaux',
      'Conducteur de benne',
      'Technicien environnement',
    ],
  },
  CONSTRUCTION: {
    nouns: ['Bâtiment', 'Rénovation', 'Travaux', 'Construction', 'Plomberie'],
    activities: [
      'Électricité du bâtiment',
      'Plomberie-chauffage',
      'Plâtrerie',
      'Peinture',
      'Construction de maisons',
    ],
    professions: [
      'Maçon',
      'Électricien',
      'Plombier',
      'Peintre',
      'Couvreur',
      'Chef de chantier',
    ],
  },
  AUTOMOTIVE: {
    nouns: ['Garage', 'Auto', 'Carrosserie', 'Automobiles', 'Pneus'],
    activities: [
      'Entretien de véhicules',
      'Carrosserie',
      'Vente de voitures',
      'Équipements automobiles',
    ],
    professions: ['Mécanicien automobile', 'Carrossier', 'Vendeur automobile'],
  },
  RETAIL: {
    nouns: ['Boutique', 'Maison', 'Magasin', 'Comptoir', 'Épicerie'],
    activities: [
      'Alimentation générale',
      'Vêtements',
      'Fleuriste',
      'Librairie',
      'Bricolage',
    ],
    professions: [
      'Vendeur',
      'Caissier',
      'Employé libre-service',
      'Fleuriste',
      'Libraire',
    ],
  },
  TRANSPORT_LOGISTICS: {
    nouns: ['Transports', 'Logistique', 'Messagerie', 'Entrepôt', 'Livraison'],
    activities: [
      'Transport routier',
      'Entreposage',
      'Transport urbain',
      'Services de courrier',
    ],
    professions: [
      'Chauffeur-livreur',
      'Cariste',
      'Préparateur de commandes',
      'Manutentionnaire',
    ],
  },
  HOSPITALITY_TOURISM: {
    nouns: ['Brasserie', 'Restaurant', 'Hôtel', 'Auberge', 'Café'],
    activities: [
      'Restauration traditionnelle',
      'Restauration rapide',
      'Hôtellerie',
      'Traiteur',
      'Débit de boissons',
    ],
    professions: [
      'Cuisinier',
      'Serveur',
      'Réceptionniste',
      'Commis de cuisine',
      'Barman',
    ],
  },
  IT_DIGITAL: {
    nouns: ['Numérique', 'Logiciels', 'Systèmes', 'Digital', 'Données'],
    activities: [
      'Programmation informatique',
      'Conseil en systèmes informatiques',
      'Gestion d’installations informatiques',
      'Hébergement de données',
    ],
    professions: [
      'Développeur',
      'Technicien support',
      'Administrateur systèmes',
      'Intégrateur web',
    ],
  },
  MEDIA_COMMUNICATION: {
    nouns: ['Communication', 'Studio', 'Média', 'Agence', 'Imprimerie'],
    activities: [
      'Agence de publicité',
      'Édition de journaux',
      'Production audiovisuelle',
      'Radio',
    ],
    professions: ['Graphiste', 'Community manager', 'Assistant de production'],
  },
  FINANCE_REAL_ESTATE: {
    nouns: ['Agence', 'Assurances', 'Mutuelle', 'Immobilier', 'Banque'],
    activities: [
      'Banque',
      'Assurance',
      'Courtage d’assurance',
      'Agence immobilière',
    ],
    professions: [
      'Conseiller clientèle',
      'Gestionnaire de contrats',
      'Négociateur',
    ],
  },
  BUSINESS_SERVICES: {
    nouns: ['Services', 'Conseil', 'Nettoyage', 'Sécurité', 'Intérim'],
    activities: [
      'Nettoyage de bâtiments',
      'Sécurité privée',
      'Travail temporaire',
      'Recrutement et placement',
      'Conseil de gestion',
      'Ingénierie',
    ],
    professions: [
      'Agent d’entretien',
      'Agent de sécurité',
      'Chargé de recrutement',
      'Assistant administratif',
    ],
  },
  EDUCATION_TRAINING: {
    nouns: ['Formation', 'Centre', 'École', 'Institut', 'Campus'],
    activities: [
      'Formation continue d’adultes',
      'Enseignement secondaire technique',
      'Enseignement sportif',
      'Enseignement secondaire général',
    ],
    professions: [
      'Formateur',
      'Assistant d’éducation',
      'Conseiller en insertion',
    ],
  },
  HEALTH: {
    nouns: [
      'Cabinet',
      'Clinique',
      'Centre de santé',
      'Résidence',
      'Laboratoire',
    ],
    activities: [
      'Activité de médecins généralistes',
      'Activités hospitalières',
      'Soins dentaires',
      'Ambulances',
      'Vétérinaires',
    ],
    professions: [
      'Aide-soignant',
      'Secrétaire médical',
      'Ambulancier',
      'Infirmier',
    ],
  },
  SOCIAL_PERSONAL_SERVICES: {
    nouns: [
      'Aide à domicile',
      'Crèche',
      'Résidence seniors',
      'Association',
      'Services',
    ],
    activities: [
      'Aide à domicile',
      'Hébergement pour personnes âgées',
      'Crèche',
      'Pompes funèbres',
    ],
    professions: [
      'Aide à domicile',
      'Auxiliaire de puériculture',
      'Accompagnant éducatif',
    ],
  },
  BEAUTY_WELLNESS: {
    nouns: ['Salon', 'Institut', 'Spa', 'Coiffure', 'Salle de sport'],
    activities: [
      'Coiffure',
      'Soins de beauté',
      'Entretien corporel',
      'Salle de remise en forme',
    ],
    professions: ['Coiffeur', 'Esthéticien', 'Coach sportif'],
  },
  CULTURE_SPORT_LEISURE: {
    nouns: ['Théâtre', 'Cinéma', 'Bibliothèque', 'Centre de loisirs', 'Club'],
    activities: [
      'Arts du spectacle',
      'Bibliothèque',
      'Installations sportives',
      'Activités récréatives',
    ],
    professions: [
      'Animateur',
      'Agent d’accueil',
      'Régisseur',
      'Éducateur sportif',
    ],
  },
  PUBLIC_SERVICE: {
    nouns: ['Mairie', 'Service', 'Centre technique', 'Antenne', 'Maison'],
    activities: [
      'Administration publique générale',
      'Administration de la santé et du social',
      'Services du feu et de secours',
    ],
    professions: ['Agent administratif', 'Agent technique', 'Agent d’accueil'],
  },
};

export const adjectives = [
  'des Vignes',
  'du Marché',
  'de la Gare',
  'du Parc',
  'des Lilas',
  'du Port',
  'Moderne',
  'Bourguignon',
  'du Faubourg',
  'des Halles',
  'de la Tille',
  'du Centre',
  'des Arts',
  'du Canal',
  'Express',
  'du Levant',
];

/** `[INSEE range, size]` of the establishments of the tests. */
export const headcounts: ReadonlyArray<readonly [string, EstablishmentSize]> = [
  ['02', 'SMALL'],
  ['03', 'SMALL'],
  ['11', 'MEDIUM'],
  ['12', 'MEDIUM'],
  ['21', 'MEDIUM'],
  ['31', 'MEDIUM'],
  ['32', 'LARGE'],
  ['41', 'LARGE'],
];
