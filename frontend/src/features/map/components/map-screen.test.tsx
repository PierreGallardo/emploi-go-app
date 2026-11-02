import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { formatDistance } from '../format';
import { sectorCodes } from '../sectors';
import {
  distanceMeters,
  establishmentDetails,
  fictitiousEstablishments,
} from '@/test/handlers/map';
import { renderWithProviders } from '@/test/render';
import { recordRequests } from '@/test/requests';
import { server } from '@/test/server';
import type { EngineOptions, MapEngine } from '../map-engine';
import { pageSize } from '../schemas';
import { MapScreen } from './map-screen';
import { ficheId } from './establishment-detail';
import { searchId } from './results-panel';

vi.mock('../suggestions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../suggestions')>()),
  suggestionDelay: 0,
}));
const factory = vi.hoisted(() => vi.fn());
vi.mock('../map-engine', () => ({ createDijonMap: factory }));

const engine = (): MapEngine => ({
  setPoints: vi.fn(),
  setDatasets: vi.fn(),
  setSelected: vi.fn(),
  setHighlighted: vi.fn(),
  setCircle: vi.fn(),
  setPlace: vi.fn(),
  fitCircle: vi.fn(),
  fitPoints: vi.fn(),
  setInsets: vi.fn(),
  flyTo: vi.fn(),
  recenter: vi.fn(),
  setTheme: vi.fn(),
  destroy: vi.fn(),
});
const viewport = (desktop: boolean) =>
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query) =>
      ({
        matches: desktop && query.includes('min-width'),
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }) as unknown as MediaQueryList,
  );
function Location() {
  return <output aria-label="adresse">{useLocation().search}</output>;
}
async function open(route = '/') {
  viewport(true);
  const instance = engine();
  factory.mockResolvedValue(instance);
  const view = renderWithProviders(
    <>
      <MapScreen scope="jeune_fictif" />
      <Location />
    </>,
    { route },
  );
  await screen.findByRole('combobox', { name: 'Quoi ?' });
  const options = () => factory.mock.calls[0]![1] as EngineOptions;
  return { instance, options, user: userEvent.setup(), ...view };
}
// What the first page shows, in the order of the default sort.
const firstPage = [...establishmentDetails]
  .sort((a, b) => a.name.localeCompare(b.name, 'fr'))
  .slice(0, pageSize);
const total = `${establishmentDetails.length} établissements`;
const adresse = () => screen.getByLabelText('adresse').textContent;
const list = () => screen.getByRole('list', { name: / établissements?$/ });
afterEach(() => {
  factory.mockReset();
  vi.restoreAllMocks();
});

it('affiche la première page d’établissements, et leur nombre total', async () => {
  await open();

  expect(await screen.findByText(total)).toBeInTheDocument();
  expect(within(list()).getAllByRole('listitem')).toHaveLength(pageSize);
});

it('ajoute une page d’établissements à la demande, « Afficher plus »', async () => {
  const { user } = await open();
  await screen.findByText(total);

  await user.click(screen.getByRole('button', { name: 'Afficher plus' }));

  await waitFor(() =>
    expect(within(list()).getAllByRole('listitem')).toHaveLength(2 * pageSize),
  );
});

it('n’offre plus d’autres établissements à la dernière page', async () => {
  // A sector of two pages, as the fictitious data give them.
  const sector = sectorCodes.find((code) => {
    const count = establishmentDetails.filter((e) => e.sector === code).length;
    return count > pageSize && count <= 2 * pageSize;
  })!;
  const { user } = await open(`/?secteur=${sector}`);
  await user.click(
    await screen.findByRole('button', { name: 'Afficher plus' }),
  );

  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: 'Afficher plus' }),
    ).not.toBeInTheDocument(),
  );
});

it('dit que les établissements manquent sur la carte quand leurs points ne se lisent pas', async () => {
  server.use(
    http.get(
      '/api/establishments/points',
      () => new HttpResponse(null, { status: 503 }),
    ),
  );
  const { options } = await open();

  act(() => options().onState('ready'));

  expect(
    await screen.findByText(
      'Établissements indisponibles sur la carte. La liste reste consultable.',
    ),
  ).toBeInTheDocument();
  expect(await screen.findByText(total)).toBeInTheDocument();
});

it('donne à la carte les points légers des établissements, sans les cartes complètes', async () => {
  const { instance } = await open();

  await waitFor(() => expect(instance.setPoints).toHaveBeenCalled());
  await waitFor(() => {
    const points = vi.mocked(instance.setPoints).mock.calls.at(-1)![0];
    expect(points.length).toBeGreaterThan(establishmentDetails.length / 2);
    expect(Object.keys(points[0]!).sort()).toEqual([
      'latitude',
      'longitude',
      'sector',
      'siret',
    ]);
  });
});

it('cherche à la touche Entrée, sans bouton Rechercher, et garde la recherche dans l’adresse', async () => {
  const requests = recordRequests();
  const { user } = await open();

  expect(
    screen.queryByRole('button', { name: 'Rechercher' }),
  ).not.toBeInTheDocument();
  await user.type(
    screen.getByRole('combobox', { name: 'Quoi ?' }),
    'boulanger{Enter}',
  );

  await waitFor(() => expect(adresse()).toBe('?q=boulanger'));
  await waitFor(() =>
    expect(
      requests
        .of('GET', '/api/establishments')
        .map(({ search }) => search.get('q')),
    ).toContain('boulanger'),
  );
  expect(
    requests.of('GET', '/api/establishments').at(-1)!.search.get('sort'),
  ).toBe('RELEVANCE');
});

it('ne cherche pas à chaque frappe', async () => {
  const requests = recordRequests();
  const { user } = await open();
  await screen.findByText(total);
  const before = requests.of('GET', '/api/establishments').length;

  await user.type(screen.getByRole('combobox', { name: 'Quoi ?' }), 'boul');

  expect(requests.of('GET', '/api/establishments')).toHaveLength(before);
  expect(adresse()).toBe('');
});

const showing = (count: number) =>
  `Afficher ${count} entreprise${count > 1 ? 's' : ''}`;
const dialog = () => screen.findByRole('dialog', { name: 'Filtres' });

it('filtre par secteur dans la sous-page des secteurs et envoie ce filtre à la recherche', async () => {
  const requests = recordRequests();
  const { user } = await open();

  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  const modal = within(await dialog());
  await user.click(modal.getByRole('button', { name: 'Secteurs d’activité' }));
  expect(
    modal.getByRole('heading', { name: 'Secteurs d’activité', level: 2 }),
  ).toHaveFocus();
  await user.click(
    modal.getByRole('checkbox', { name: 'Commerce · Distribution' }),
  );
  await user.click(modal.getByRole('button', { name: 'Retour aux filtres' }));
  const expected = establishmentDetails.filter(
    ({ sector }) => sector === 'RETAIL',
  ).length;
  await user.click(
    await modal.findByRole('button', { name: showing(expected) }),
  );

  expect(adresse()).toBe('?secteur=RETAIL');
  await waitFor(() =>
    expect(
      requests.of('GET', '/api/establishments').at(-1)!.search.getAll('sector'),
    ).toEqual(['RETAIL']),
  );
  expect(
    screen.queryByRole('button', { name: 'Tout effacer' }),
  ).not.toBeInTheDocument();
});

it('annonce le nombre de filtres actifs sur le bouton « Filtres »', async () => {
  await open('/?secteur=RETAIL');

  expect(
    await screen.findByRole('button', { name: 'Filtres, 1 actif' }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: /Retirer le filtre/ }),
  ).not.toBeInTheDocument();
});

it('combine le type, la taille et les transports, sans rien envoyer avant de valider', async () => {
  const requests = recordRequests();
  const { user } = await open();
  await screen.findByText(total);
  const before = requests
    .of('GET', '/api/establishments')
    .filter(({ search }) => search.get('pageSize') === String(pageSize)).length;

  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  const modal = within(await dialog());
  await user.click(modal.getByRole('checkbox', { name: 'Entreprises' }));
  await user.click(
    modal.getByRole('checkbox', { name: 'Moins de 10 salariés' }),
  );
  await user.click(
    modal.getByRole('checkbox', { name: /Accessible en transports/ }),
  );

  expect(adresse()).toBe('');
  expect(
    requests
      .of('GET', '/api/establishments')
      .filter(({ search }) => search.get('pageSize') === String(pageSize)),
  ).toHaveLength(before);
  await user.click(await modal.findByRole('button', { name: /^Afficher/ }));
  await waitFor(() =>
    expect(adresse()).toBe('?type=COMPANY&taille=SMALL&transport=1'),
  );
  const last = requests
    .of('GET', '/api/establishments')
    .filter(({ search }) => search.get('pageSize') === String(pageSize))
    .at(-1)!.search;
  expect(last.getAll('companyType')).toEqual(['COMPANY']);
  expect(last.getAll('size')).toEqual(['SMALL']);
  expect(last.get('transit')).toBe('true');
});

it('met à jour dans le pied de la modale le nombre d’entreprises, et « Tout effacer » le remet à zéro filtre', async () => {
  const { user } = await open();
  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  const modal = within(await dialog());
  const agencies = fictitiousEstablishments.filter(
    ({ companyType }) => companyType === 'TEMP_AGENCY',
  ).length;

  await user.click(modal.getByRole('checkbox', { name: 'Travail temporaire' }));
  expect(
    await modal.findByRole('button', { name: showing(agencies) }),
  ).toBeInTheDocument();
  await user.click(modal.getByRole('button', { name: 'Tout effacer' }));

  expect(
    await modal.findByRole('button', {
      name: showing(establishmentDetails.length),
    }),
  ).toBeInTheDocument();
  expect(
    modal.getByRole('checkbox', { name: 'Travail temporaire' }),
  ).not.toBeChecked();
});

it('garde le focus dans la modale quand « Tout effacer » devient inactif', async () => {
  const { user } = await open();
  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  const modal = within(await dialog());
  await user.click(modal.getByRole('checkbox', { name: 'Travail temporaire' }));

  await user.click(modal.getByRole('button', { name: 'Tout effacer' }));

  expect(modal.getByRole('button', { name: 'Tout effacer' })).toBeDisabled();
  expect(modal.getByRole('heading', { name: 'Filtres' })).toHaveFocus();
});

it('abandonne les filtres choisis quand la modale se ferme sans valider', async () => {
  const { user } = await open();
  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  const modal = within(await dialog());
  await user.click(modal.getByRole('checkbox', { name: 'ESN' }));

  await user.click(modal.getByRole('button', { name: 'Fermer' }));

  await waitFor(() =>
    expect(
      screen.queryByRole('dialog', { name: 'Filtres' }),
    ).not.toBeInTheDocument(),
  );
  expect(adresse()).toBe('');
  expect(screen.getByRole('button', { name: 'Filtres' })).toBeInTheDocument();
});

it('désactive le rayon tant qu’aucun lieu n’est choisi, avec une explication', async () => {
  const { user } = await open();

  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  const modal = within(await dialog());

  expect(
    modal.getByRole('slider', { name: 'Rayon autour du lieu' }),
  ).toHaveAttribute('data-disabled');
  expect(modal.getByText('5 km')).toBeInTheDocument();
  expect(modal.getByText(/Choisis d’abord un lieu/)).toBeInTheDocument();
});

it('permet de choisir le rayon une fois un lieu choisi', async () => {
  const { user } = await open();
  await user.type(screen.getByRole('combobox', { name: 'Où ?' }), 'Place Dar');
  await user.click(
    await screen.findByRole('option', { name: 'Place Darcy, Dijon' }),
  );

  await user.click(await screen.findByRole('button', { name: 'Filtres' }));
  const modal = within(await dialog());
  expect(modal.queryByText(/Choisis d’abord un lieu/)).not.toBeInTheDocument();
  const slider = modal.getByRole('slider', { name: 'Rayon autour du lieu' });
  slider.focus();
  await user.keyboard('{ArrowLeft}{ArrowLeft}{ArrowLeft}');
  expect(modal.getByText('2 km')).toBeInTheDocument();
  await user.click(await modal.findByRole('button', { name: /^Afficher/ }));

  await waitFor(() => expect(adresse()).toBe('?rayon=2'));
});

it('trie par pertinence à la demande du visiteur', async () => {
  const requests = recordRequests();
  const { user } = await open();

  await user.click(screen.getByRole('button', { name: /Trier par/ }));
  await user.click(
    await screen.findByRole('menuitemradio', { name: 'Pertinence' }),
  );

  await waitFor(() => expect(adresse()).toBe('?tri=relevance'));
  await waitFor(() =>
    expect(
      requests.of('GET', '/api/establishments').at(-1)!.search.get('sort'),
    ).toBe('RELEVANCE'),
  );
});

const openFirstFiche = async () => {
  const session = await open();
  const first = firstPage.find(
    ({ jobsUrl, jobs }) => jobsUrl && jobs.length > 0,
  )!;
  await session.user.click(
    await screen.findByRole('button', { name: new RegExp(first.name) }),
  );
  await screen.findByRole('heading', { name: first.name, level: 2 });
  return { ...session, first };
};

it('ouvre la fiche d’un établissement dans le panneau, le focus sur son nom', async () => {
  const { first } = await openFirstFiche();

  expect(
    screen.getByRole('heading', { name: first.name, level: 2 }),
  ).toHaveFocus();
  expect(adresse()).toBe(`?etablissement=${first.siret}`);
});

it('montre dans la fiche ses liens d’itinéraire et d’offres, sans les actions à venir', async () => {
  const { first } = await openFirstFiche();

  expect(screen.getByRole('link', { name: /Itinéraire/ })).toHaveAttribute(
    'href',
    expect.stringContaining('google.com/maps/dir'),
  );
  expect(screen.getByRole('link', { name: /Offres d’emploi/ })).toHaveAttribute(
    'href',
    first.jobsUrl!,
  );
  expect(
    screen.queryByRole('button', { name: 'Partager' }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText(/site web/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/recrute pas/i)).not.toBeInTheDocument();
  const sections = screen
    .getAllByRole('heading', { level: 3 })
    .map((section) => section.textContent);
  expect(sections).toEqual(['Métiers de cette activité', 'Entreprise']);
});

it('revient à la liste depuis la fiche et rend le focus à la ligne ouverte', async () => {
  const { user, first } = await openFirstFiche();

  await user.click(screen.getByRole('button', { name: 'Retour à la liste' }));

  await waitFor(() => expect(adresse()).toBe(''));
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: new RegExp(first.name) }),
    ).toHaveFocus(),
  );
});

it('amène la carte à l’établissement choisi dans la liste et le marque sélectionné', async () => {
  const { user, instance } = await open();
  const first = firstPage.find(({ longitude }) => longitude !== null)!;
  await user.click(
    await screen.findByRole('button', { name: new RegExp(first.name) }),
  );

  await waitFor(() =>
    expect(vi.mocked(instance.setSelected)).toHaveBeenLastCalledWith(
      first.siret,
    ),
  );
  expect(instance.flyTo).toHaveBeenCalledWith({
    id: first.siret,
    lng: first.longitude,
    lat: first.latitude,
  });
});

it('cadre la carte sur les points d’une nouvelle recherche, pas à son ouverture', async () => {
  const { user, instance, options } = await open();
  await screen.findByText(total);
  await act(async () => options().onState('ready'));
  expect(instance.fitPoints).not.toHaveBeenCalled();

  await user.type(
    screen.getByRole('combobox', { name: 'Quoi ?' }),
    `${establishmentDetails[0]!.name}{Enter}`,
  );

  await waitFor(() => expect(instance.fitPoints).toHaveBeenCalledOnce());
  const [framed] = vi.mocked(instance.fitPoints).mock.calls[0]!;
  expect(framed.map((point) => point.siret)).toContain(
    establishmentDetails[0]!.siret,
  );
});

it('ouvre directement la fiche d’un lien partagé', async () => {
  const target = establishmentDetails[7]!;
  await open(`/?etablissement=${target.siret}`);

  expect(
    await screen.findByRole('heading', { name: target.name, level: 2 }),
  ).toBeInTheDocument();
});

it('rend le focus à la première ligne au retour d’une fiche absente de la liste', async () => {
  const away = establishmentDetails.find(
    (detail) => !firstPage.includes(detail),
  )!;
  const { user } = await open(`/?etablissement=${away.siret}`);
  await screen.findByRole('heading', { name: away.name, level: 2 });

  await user.click(screen.getByRole('button', { name: 'Retour à la liste' }));

  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: new RegExp(firstPage[0]!.name) }),
    ).toHaveFocus(),
  );
});

it('rend le focus à la recherche au retour d’une fiche quand la liste est vide', async () => {
  const target = establishmentDetails[0]!;
  const { user } = await open(`/?q=introuvable&etablissement=${target.siret}`);
  await screen.findByRole('heading', { name: target.name, level: 2 });

  await user.click(screen.getByRole('button', { name: 'Retour à la liste' }));

  // The focusable zone wraps the search form: it is reached through the form's parent.
  await waitFor(() =>
    expect(screen.getByRole('search').parentElement).toHaveFocus(),
  );
});

it('mène le clavier à la recherche sans traverser la carte', async () => {
  await open();

  expect(
    screen.getByRole('link', { name: 'Aller à la recherche' }),
  ).toHaveAttribute('href', `#${searchId}`);
  // The focusable zone wraps the search form: it is reached through the form's parent.
  expect(screen.getByRole('search').parentElement).toContainElement(
    screen.getByRole('combobox', { name: 'Quoi ?' }),
  );
});

it('mène le clavier à la fiche ouverte, la recherche étant cachée', async () => {
  const target = establishmentDetails[7]!;
  await open(`/?etablissement=${target.siret}`);
  await screen.findByRole('heading', { name: target.name, level: 2 });

  const link = screen.getByRole('link', { name: 'Aller à la fiche' });
  expect(link).toHaveAttribute('href', `#${ficheId}`);
  // The browser follows the link to this id: the fiche has no role of its own to find it by.
  expect(document.getElementById(ficheId)).toContainElement(
    screen.getByRole('button', { name: 'Retour à la liste' }),
  );
});

it('survoler une carte met son point en évidence sur la carte', async () => {
  const { user, instance } = await open();
  const card = (await screen.findAllByRole('button', { name: /n°/ }))[0]!;

  await user.hover(card);
  expect(instance.setHighlighted).toHaveBeenLastCalledWith(
    expect.stringMatching(/^\d{14}$/),
  );
  await user.unhover(card);

  await waitFor(() =>
    expect(instance.setHighlighted).toHaveBeenLastCalledWith(null),
  );
});

it('un clic sur un point de la carte ouvre la fiche', async () => {
  const { options } = await open();
  const target = establishmentDetails[3]!;

  act(() => options().onPick(target.siret));

  expect(
    await screen.findByRole('heading', { name: target.name, level: 2 }),
  ).toBeInTheDocument();
});

it('un clic sur un regroupement montre ses établissements dans le panneau, avec une pastille pour en sortir', async () => {
  const requests = recordRequests();
  const { options, user } = await open();
  const sirets = establishmentDetails.slice(0, 3).map(({ siret }) => siret);

  act(() => options().onGroup(sirets));

  expect(await screen.findByText('3 établissements')).toBeInTheDocument();
  expect(
    requests.of('GET', '/api/establishments').at(-1)!.search.getAll('siret'),
  ).toEqual(sirets);
  await user.click(
    screen.getByRole('button', {
      name: 'Retirer le filtre Dans ce regroupement',
    }),
  );
  expect(await screen.findByText(total)).toBeInTheDocument();
});

it('ne propose pas de chercher dans la zone affichée quand la carte bouge', async () => {
  const requests = recordRequests();
  await open();
  await screen.findByText(total);

  expect(
    screen.queryByRole('button', { name: /Rechercher dans cette zone/ }),
  ).not.toBeInTheDocument();
  expect(
    requests
      .of('GET', '/api/establishments')
      .flatMap(({ search }) => [...search.keys()]),
  ).not.toContain('bbox');
});

it('cherche autour du lieu choisi dans les suggestions : carte centrée, distances et tri par distance, sans mettre la position dans l’adresse', async () => {
  const requests = recordRequests();
  const { user, instance } = await open();

  await user.type(screen.getByRole('combobox', { name: 'Où ?' }), 'Place Dar');
  await user.click(
    await screen.findByRole('option', { name: 'Place Darcy, Dijon' }),
  );

  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: /Trier par : Distance/ }),
    ).toBeInTheDocument(),
  );
  expect(screen.getByRole('combobox', { name: 'Où ?' })).toHaveValue(
    'Place Darcy',
  );
  const last = requests.of('GET', '/api/establishments').at(-1)!;
  expect(last.headers.get('Search-Area')).toBe('5.034500,47.323600,5');
  expect(last.search.get('sort')).toBe('DISTANCE');
  expect(adresse()).not.toMatch(/lat|lng|47\.3|Darcy/);
  await waitFor(() =>
    expect(instance.setCircle).toHaveBeenLastCalledWith({
      lng: 5.0345,
      lat: 47.3236,
      km: 5,
    }),
  );
  expect(instance.fitCircle).toHaveBeenLastCalledWith({
    lng: 5.0345,
    lat: 47.3236,
    km: 5,
  });
});

it('affiche la distance la plus courte sur la première ligne, depuis le lieu choisi', async () => {
  const { user } = await open();
  const place = { lng: 5.0345, lat: 47.3236 };
  const radiusMeters = 5_000;
  const nearest = Math.min(
    ...establishmentDetails
      .filter(({ longitude }) => longitude !== null)
      .map(({ longitude, latitude }) =>
        Math.round(distanceMeters(place, { lng: longitude!, lat: latitude! })),
      )
      .filter((meters) => meters <= radiusMeters),
  );

  await user.type(screen.getByRole('combobox', { name: 'Où ?' }), 'Place Dar');
  await user.click(
    await screen.findByRole('option', { name: 'Place Darcy, Dijon' }),
  );

  await waitFor(() => {
    const [closest] = within(list()).getAllByRole('listitem');
    expect(
      within(closest!).getByText(formatDistance(nearest)),
    ).toBeInTheDocument();
  });
});

it('propose des communes, des adresses et des arrêts, regroupés, sous le champ « Où ? »', async () => {
  const { user } = await open();

  await user.type(screen.getByRole('combobox', { name: 'Où ?' }), 'dar');

  const list = await screen.findByRole('listbox', { name: 'Où ?' });
  await waitFor(() =>
    expect(
      within(list)
        .getAllByRole('option')
        .map((option) => option.getAttribute('aria-label')),
    ).toEqual(['Place Darcy, Dijon', 'Darcy, lignes T1, T2']),
  );
  expect(
    within(list).getByRole('group', { name: 'Adresses' }),
  ).toBeInTheDocument();
  expect(
    within(list).getByRole('group', { name: 'Arrêts de tram et de bus' }),
  ).toBeInTheDocument();
});

it('cherche le premier lieu trouvé à la touche Entrée quand aucune suggestion n’est choisie', async () => {
  const requests = recordRequests();
  const { user } = await open();

  await user.type(
    screen.getByRole('combobox', { name: 'Où ?' }),
    'Place Darcy{Enter}',
  );

  await waitFor(() =>
    expect(
      requests
        .of('GET', '/api/establishments')
        .at(-1)!
        .headers.get('Search-Area'),
    ).toBe('5.034500,47.323600,5'),
  );
});

it('signale un lieu introuvable sans changer la recherche', async () => {
  const { user } = await open();

  await user.type(
    screen.getByRole('combobox', { name: 'Où ?' }),
    'zzzz{Enter}',
  );

  expect(await screen.findByText(/Lieu introuvable/)).toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: /Trier par : Ordre alphabétique/ }),
  ).toBeInTheDocument();
});

it('ne propose plus de se localiser', async () => {
  await open();

  expect(
    screen.queryByRole('button', { name: 'Me localiser' }),
  ).not.toBeInTheDocument();
});

it('propose les métiers d’un secteur, et choisit au clavier celui que la flèche désigne', async () => {
  const requests = recordRequests();
  const { user } = await open();
  const field = screen.getByRole('combobox', { name: 'Quoi ?' });

  await user.type(field, 'viticul');

  const list = await screen.findByRole('listbox', { name: 'Quoi ?' });
  const options = within(list).getAllByRole('option');
  expect(options[0]).toHaveAccessibleName('Rechercher « viticul »');
  expect(options[1]).toHaveAccessibleName(
    expect.stringContaining('Viticulteur'),
  );
  expect(field).toHaveAttribute('aria-expanded', 'true');
  await user.keyboard('{ArrowDown}{ArrowDown}');
  expect(field).toHaveAttribute('aria-activedescendant', options[1]!.id);
  await user.keyboard('{Enter}');

  await waitFor(() =>
    expect(adresse()).toBe(
      `?metier=${encodeURIComponent('Viticulteur').replaceAll('%20', '+')}`,
    ),
  );
  expect(field).toHaveValue('Viticulteur');
  expect(
    screen.getByText(/Établissements dont l’activité correspond au métier/),
  ).toBeInTheDocument();
  await waitFor(() =>
    expect(
      requests.of('GET', '/api/establishments').at(-1)!.search.get('job'),
    ).toBe('Viticulteur'),
  );
  expect(
    requests.of('GET', '/api/establishments').at(-1)!.search.has('q'),
  ).toBe(false);
});

it('va à la dernière suggestion avec Fin, une fois dans la liste', async () => {
  const { user } = await open();
  const field = screen.getByRole('combobox', { name: 'Quoi ?' });
  await user.type(field, 'viticul');
  const options = within(
    await screen.findByRole('listbox', { name: 'Quoi ?' }),
  ).getAllByRole('option');
  await user.keyboard('{ArrowDown}');

  await user.keyboard('{End}');

  expect(field).toHaveAttribute('aria-activedescendant', options.at(-1)!.id);
});

it('va à la première suggestion avec Début, une fois dans la liste', async () => {
  const { user } = await open();
  const field = screen.getByRole('combobox', { name: 'Quoi ?' });
  await user.type(field, 'viticul');
  const options = within(
    await screen.findByRole('listbox', { name: 'Quoi ?' }),
  ).getAllByRole('option');
  await user.keyboard('{ArrowDown}{ArrowDown}');

  await user.keyboard('{Home}');

  expect(field).toHaveAttribute('aria-activedescendant', options[0]!.id);
});

it('lance la recherche de ce qui est tapé quand on choisit la première ligne', async () => {
  const { user } = await open();

  await user.type(screen.getByRole('combobox', { name: 'Quoi ?' }), 'viticul');
  await user.click(
    await screen.findByRole('option', { name: 'Rechercher « viticul »' }),
  );

  await waitFor(() => expect(adresse()).toBe('?q=viticul'));
});

it('choisir une entreprise dans les suggestions met son nom et son établissement dans l’adresse', async () => {
  const { user } = await open();
  const { name, siret } = establishmentDetails[0]!;

  await user.type(screen.getByRole('combobox', { name: 'Quoi ?' }), name);
  await user.click(
    await screen.findByRole('option', { name: new RegExp(`^${name}$`) }),
  );

  await waitFor(() =>
    expect(adresse()).toBe(
      `?q=${encodeURIComponent(name).replaceAll('%20', '+')}&etablissement=${siret}`,
    ),
  );
});

it('ferme les suggestions avec Échap sans effacer la saisie', async () => {
  const { user } = await open();
  const field = screen.getByRole('combobox', { name: 'Quoi ?' });
  await user.type(field, 'viticul');
  await screen.findByRole('listbox', { name: 'Quoi ?' });

  await user.keyboard('{Escape}');

  expect(
    screen.queryByRole('listbox', { name: 'Quoi ?' }),
  ).not.toBeInTheDocument();
  expect(field).toHaveValue('viticul');
});

it('ne propose rien avant deux caractères', async () => {
  const { user } = await open();

  await user.type(screen.getByRole('combobox', { name: 'Quoi ?' }), 'p');

  expect(
    screen.queryByRole('listbox', { name: 'Quoi ?' }),
  ).not.toBeInTheDocument();
});

it('garde dans la liste, sans point, un établissement sans coordonnées', async () => {
  const lost = establishmentDetails.find(
    ({ longitude }) => longitude === null,
  )!;
  await open(`/?q=${encodeURIComponent(lost.name)}`);

  const card = await screen.findByRole('button', {
    name: new RegExp(lost.name),
  });
  expect(
    within(card).getByText('Pas de point sur la carte'),
  ).toBeInTheDocument();
});

it('propose des pistes quand rien ne correspond, et efface tout', async () => {
  const { user } = await open('/?q=zzzzzz&secteur=FOOD');

  expect(
    await screen.findByText('Aucun établissement trouvé'),
  ).toBeInTheDocument();
  expect(
    screen.getByText(/Essaie un autre mot\. Retire un filtre\./),
  ).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Tout effacer' }));

  expect(await screen.findByText(total)).toBeInTheDocument();
});

it('signale la panne de la recherche sans la présenter comme une liste vide, puis réessaie', async () => {
  let failing = true;
  server.use(
    http.get('/api/establishments', ({ request }) =>
      failing && new URL(request.url).searchParams.has('page')
        ? new HttpResponse(null, { status: 500 })
        : undefined,
    ),
  );
  viewport(true);
  factory.mockResolvedValue(engine());
  const user = userEvent.setup();
  renderWithProviders(<MapScreen scope="jeune_fictif" />);

  expect(
    await screen.findByText('Les établissements sont indisponibles'),
  ).toBeInTheDocument();
  expect(
    screen.queryByText('Aucun établissement trouvé'),
  ).not.toBeInTheDocument();
  failing = false;
  await user.click(screen.getByRole('button', { name: 'Réessayer' }));

  expect(await screen.findByText(total)).toBeInTheDocument();
});

it('replie le panneau sur ordinateur par l’onglet de son bord gauche, et le rouvre', async () => {
  // jsdom lays nothing out: a panel of 400 pixels at the right of a map of 1 000.
  vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockReturnValue(
    document.body,
  );
  vi.spyOn(HTMLElement.prototype, 'offsetLeft', 'get').mockReturnValue(600);
  vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(1000);
  const { user, instance } = await open();
  const panel = screen.getByRole('complementary', { name: 'Établissements' });
  const tab = screen.getByRole('button', { name: 'Liste des établissements' });
  expect(tab).toHaveAttribute('aria-expanded', 'true');
  expect(tab).toHaveAttribute('aria-controls', panel.id);

  await user.click(tab);

  expect(panel).toHaveAttribute('inert');
  expect(tab).toHaveAttribute('aria-expanded', 'false');
  await waitFor(() =>
    expect(instance.setInsets).toHaveBeenLastCalledWith(
      expect.objectContaining({ right: 0 }),
    ),
  );
  await user.click(tab);

  expect(panel).not.toHaveAttribute('inert');
  expect(list()).toBeVisible();
  await waitFor(() =>
    expect(instance.setInsets).toHaveBeenLastCalledWith(
      expect.objectContaining({ right: expect.any(Number) }),
    ),
  );
  expect(vi.mocked(instance.setInsets).mock.lastCall![0].right).toBeGreaterThan(
    0,
  );
});

it('garde la liste montée quand le panneau est replié', async () => {
  const { user } = await open();
  const listElement = await screen.findByRole('list', {
    name: / établissements?$/,
  });

  await user.click(
    screen.getByRole('button', { name: 'Liste des établissements' }),
  );

  expect(listElement).toBeInTheDocument();
});

it('présente la liste dans une feuille du bas sur téléphone, et les filtres dans une boîte de dialogue', async () => {
  viewport(false);
  factory.mockResolvedValue(engine());
  const user = userEvent.setup();
  renderWithProviders(<MapScreen scope="jeune_fictif" />);

  expect(
    await screen.findByRole('region', { name: 'Établissements' }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole('complementary', { name: 'Établissements' }),
  ).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  expect(
    await screen.findByRole('dialog', { name: 'Filtres' }),
  ).toBeInTheDocument();
  await user.click(screen.getByRole('checkbox', { name: 'Entreprises' }));
  expect(
    await screen.findByRole('button', { name: /^Afficher \d+ entreprises?$/ }),
  ).toBeInTheDocument();
});

it('efface la recherche d’un clic sur la croix du champ « Quoi ? », et redonne le focus au champ', async () => {
  const { user } = await open('/?q=boulanger');
  const field = screen.getByRole('combobox', { name: 'Quoi ?' });
  expect(field).toHaveValue('boulanger');

  await user.click(
    screen.getByRole('button', { name: 'Effacer la recherche' }),
  );

  expect(field).toHaveValue('');
  expect(field).toHaveFocus();
  await waitFor(() => expect(adresse()).toBe(''));
  expect(
    screen.queryByRole('button', { name: 'Effacer la recherche' }),
  ).not.toBeInTheDocument();
});

it('efface le métier choisi avec la croix du champ « Quoi ? »', async () => {
  const { user } = await open('/?metier=Pilote+de+drone+fictif');

  await user.click(
    screen.getByRole('button', { name: 'Effacer la recherche' }),
  );

  await waitFor(() => expect(adresse()).toBe(''));
  expect(screen.getByRole('combobox', { name: 'Quoi ?' })).toHaveValue('');
});

it('efface le lieu avec la croix du champ « Où ? » : plus d’épingle, de cercle ni de rayon', async () => {
  const { user, instance } = await open();
  await user.type(screen.getByRole('combobox', { name: 'Où ?' }), 'Place Dar');
  await user.click(
    await screen.findByRole('option', { name: 'Place Darcy, Dijon' }),
  );
  await waitFor(() =>
    expect(instance.setPlace).toHaveBeenLastCalledWith({
      lng: 5.0345,
      lat: 47.3236,
    }),
  );

  await user.click(screen.getByRole('button', { name: 'Effacer le lieu' }));

  expect(screen.getByRole('combobox', { name: 'Où ?' })).toHaveValue('');
  await waitFor(() => expect(instance.setPlace).toHaveBeenLastCalledWith(null));
  expect(instance.setCircle).toHaveBeenLastCalledWith(null);
  await user.click(screen.getByRole('button', { name: 'Filtres' }));
  expect(
    await screen.findByRole('slider', { name: 'Rayon autour du lieu' }),
  ).toHaveAttribute('data-disabled');
});

it('affiche la croix du champ « Où ? » seulement quand il contient du texte', async () => {
  const { user } = await open();
  await screen.findByText(total);
  expect(
    screen.queryByRole('button', { name: 'Effacer le lieu' }),
  ).not.toBeInTheDocument();

  await user.type(screen.getByRole('combobox', { name: 'Où ?' }), 'zz');

  expect(
    screen.getByRole('button', { name: 'Effacer le lieu' }),
  ).toBeInTheDocument();
});

it('n’envoie aucune recherche au clic sur la croix du champ « Où ? »', async () => {
  const requests = recordRequests();
  const { user } = await open();
  await screen.findByText(total);
  const before = requests.of('GET', '/api/establishments').length;
  await user.type(screen.getByRole('combobox', { name: 'Où ?' }), 'zz');

  await user.click(screen.getByRole('button', { name: 'Effacer le lieu' }));

  expect(requests.of('GET', '/api/establishments')).toHaveLength(before);
  expect(screen.queryByText(/Lieu introuvable/)).not.toBeInTheDocument();
});

it('inclut le nom entier de l’établissement dans le nom accessible de sa ligne, même coupé à l’écran', async () => {
  await open();
  const long = [...firstPage].sort((a, b) => b.name.length - a.name.length)[0]!;

  expect(
    await screen.findByRole('button', { name: new RegExp(long.name) }),
  ).toBeInTheDocument();
});

it('ouvre la fiche d’une entreprise choisie dans « Quoi ? », sans recadrer la carte', async () => {
  const { user, instance, options } = await open();
  const { name } = establishmentDetails[0]!;
  await act(async () => options().onState('ready'));

  await user.type(screen.getByRole('combobox', { name: 'Quoi ?' }), name);
  await user.click(
    await screen.findByRole('option', { name: new RegExp(`^${name}$`) }),
  );

  expect(
    await screen.findByRole('heading', { name, level: 2 }),
  ).toBeInTheDocument();
  expect(instance.fitPoints).not.toHaveBeenCalled();
});

it('ne recadre pas la carte quand le tri change', async () => {
  const { user, instance, options } = await open();
  await screen.findByText(total);
  await act(async () => options().onState('ready'));

  await user.click(screen.getByRole('button', { name: /Trier par/ }));
  await user.click(
    await screen.findByRole('menuitemradio', { name: 'Pertinence' }),
  );
  await waitFor(() => expect(adresse()).toBe('?tri=relevance'));
  await screen.findByText(total);

  expect(instance.fitPoints).not.toHaveBeenCalled();
});
