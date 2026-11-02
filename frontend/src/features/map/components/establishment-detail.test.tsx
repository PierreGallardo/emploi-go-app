import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { establishmentDetails } from '@/test/handlers/map';
import { sectorStyle } from '../sectors';
import type { EstablishmentDetail } from '../schemas';
import { EstablishmentDetailView } from './establishment-detail';

// A complete fiche made of fictitious values.
const complete: EstablishmentDetail = {
  ...establishmentDetails[0]!,
  name: 'Agence Exemple',
  companyName: 'Exemple Mutuelle',
  activity: 'Autres assurances',
  sector: 'FINANCE_REAL_ESTATE',
  address: '1 rue d’Exemple, Dijon',
  description: 'Une agence fictive qui conseille ses clients.',
  ess: true,
  headcountRange: '11',
  companyCreationYear: 2001,
  companyCategory: 'GE',
  siret: '00000000000000',
  nearestStop: { name: 'Arrêt Exemple', mode: 'TRAM', distanceMeters: 230 },
  jobsUrl: 'https://example.org/emplois',
  jobs: ['Conseiller·ère', 'Chargé·e de relation'],
};
const view = (
  props: Partial<React.ComponentProps<typeof EstablishmentDetailView>> = {},
) =>
  render(
    <EstablishmentDetailView
      detail={complete}
      loading={false}
      failed={false}
      onBack={vi.fn()}
      onRetry={vi.fn()}
      {...props}
    />,
  );

it('montre tous les champs d’une fiche complète', () => {
  view();

  expect(
    screen.getByRole('heading', { name: 'Agence Exemple', level: 2 }),
  ).toBeInTheDocument();
  expect(screen.getAllByText('Autres assurances')[0]).toBeInTheDocument();
  expect(
    screen.getByText(sectorStyle['FINANCE_REAL_ESTATE'].label),
  ).toBeInTheDocument();
  expect(screen.getByText('1 rue d’Exemple, Dijon')).toBeInTheDocument();
  expect(
    screen.getByText(/Arrêt Divia Arrêt Exemple, à 230 m/),
  ).toBeInTheDocument();
  expect(screen.getByText('Conseiller·ère')).toBeInTheDocument();
  const company = screen.getByRole('region', { name: 'Entreprise' });
  const rows = within(company);
  expect(rows.getByText('Exemple Mutuelle')).toBeInTheDocument();
  expect(rows.getByText('Autres assurances')).toBeInTheDocument();
  expect(rows.getByText('Grande entreprise (GE)')).toBeInTheDocument();
  expect(rows.getByText('10 à 19 salariés')).toBeInTheDocument();
  expect(rows.getByText('2001')).toBeInTheDocument();
  expect(rows.getByText('ESS')).toBeInTheDocument();
  expect(rows.getByText('Oui')).toBeInTheDocument();
  expect(rows.getByText('00000000000000')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Offres d’emploi/ })).toHaveAttribute(
    'href',
    'https://example.org/emplois',
  );
});

it('ne répète pas la société quand elle porte le même nom que l’établissement', () => {
  view({ detail: { ...complete, companyName: complete.name } });

  expect(screen.queryByText('Société')).not.toBeInTheDocument();
});

it('ne montre ni ligne vide ni offres quand ces informations manquent', () => {
  view({
    detail: {
      ...complete,
      companyCategory: null,
      headcountRange: null,
      companyCreationYear: null,
      ess: false,
      description: null,
      jobsUrl: null,
      nearestStop: null,
      jobs: [],
    },
  });

  expect(screen.queryByText('Catégorie')).not.toBeInTheDocument();
  expect(screen.queryByText('Effectif')).not.toBeInTheDocument();
  expect(screen.queryByText('Créée en')).not.toBeInTheDocument();
  expect(screen.queryByText('ESS')).not.toBeInTheDocument();
  expect(
    screen.queryByRole('link', { name: /Offres d’emploi/ }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText(/Arrêt Divia/)).not.toBeInTheDocument();
  expect(
    screen.queryByRole('heading', { name: 'Métiers de cette activité' }),
  ).not.toBeInTheDocument();
});

it('annonce « Enregistrer » comme bientôt disponible, sans effet au clic', async () => {
  const user = userEvent.setup();
  view();
  const action = screen.getByRole('button', { name: 'Enregistrer' });

  await user.click(action);

  expect(action).toHaveAttribute('aria-disabled', 'true');
  expect(action).toHaveAccessibleDescription('Bientôt disponible');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('annonce le chargement de la fiche à la place de son contenu', () => {
  view({ detail: undefined, loading: true });

  expect(screen.getByRole('status')).toHaveTextContent(
    'Chargement de la fiche…',
  );
  expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
});

it('dit que la fiche est indisponible et relance son chargement à « Réessayer »', async () => {
  const user = userEvent.setup();
  const onRetry = vi.fn();
  view({ detail: undefined, failed: true, onRetry });

  expect(
    screen.getByText('Cet établissement est indisponible pour le moment.'),
  ).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Réessayer' }));

  expect(onRetry).toHaveBeenCalledOnce();
});

it('revient à la liste au clic sur « Retour à la liste »', async () => {
  const user = userEvent.setup();
  const onBack = vi.fn();
  view({ onBack });

  await user.click(screen.getByRole('button', { name: 'Retour à la liste' }));

  expect(onBack).toHaveBeenCalledOnce();
});
