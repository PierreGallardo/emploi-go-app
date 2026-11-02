import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { writeActions } from '../follow-up-store';
import type { FollowUpAction, FollowUpBody } from '../follow-up-schemas';
import { FollowUpDialog } from './follow-up-dialog';

type User = ReturnType<typeof userEvent.setup>;

const siret = '00000000000000';
const name = 'Agence Exemple';
const note = null;
const action = (body: FollowUpBody): FollowUpAction => ({
  ...body,
  id: crypto.randomUUID(),
});
const seed = (...journal: FollowUpAction[]) => writeActions(siret, journal);
function show(onClose = vi.fn(), establishment = siret) {
  const view = render(
    <FollowUpDialog siret={establishment} name={name} onClose={onClose} />,
  );
  return { user: userEvent.setup(), onClose, ...view };
}
const field = (label: string) => screen.findByLabelText(label);
const addAction = async (user: User) =>
  user.click(await screen.findByRole('button', { name: 'Ajouter une action' }));
const chooseType = async (user: User, type: string) =>
  user.click(await screen.findByRole('radio', { name: type }));
async function setDate(user: User, date: string) {
  const input = await field('Date');
  await user.clear(input);
  await user.type(input, date);
}
const typeIn = async (user: User, label: string, text: string) =>
  user.type(await field(label), text);
const save = (user: User) =>
  user.click(screen.getByRole('button', { name: 'Enregistrer' }));
const history = () => screen.getByRole('list', { name: 'Historique' });
// The entries are the direct items of the history: the details are lists of their own.
const entries = () => Array.from(history().children) as HTMLElement[];
const entry = (index: number) => entries()[index]!;

it('dit que la démarche est un test, gardé seulement sur cet appareil', () => {
  show();

  expect(
    screen.getByRole('dialog', { name: `Démarche avec ${name}` }),
  ).toHaveAccessibleDescription(
    'Données de test : ta démarche est gardée seulement sur cet appareil, dans ce navigateur.',
  );
});

it('propose d’ajouter une première action quand le journal est vide', () => {
  show();

  expect(
    screen.getByText(`Tu n’as encore rien fait avec ${name}.`),
  ).toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Ajouter une action' }),
  ).toBeInTheDocument();
});

it('montre la plus récente action en haut de la frise', () => {
  seed(
    action({
      type: 'APPLICATION_SENT',
      date: '2020-03-02',
      note,
      channel: 'EMAIL',
      position: 'Vendeur',
    }),
    action({
      type: 'ON_SITE_VISIT',
      date: '2020-03-10',
      note: 'Accueil aimable.',
      result: 'CV_LEFT',
    }),
    action({
      type: 'INTERVIEW',
      date: '2020-03-20',
      note,
      time: '14:30',
      format: 'VIDEO',
      position: null,
    }),
  );
  show();

  const interview = entry(0);
  const visit = entry(1);
  const application = entry(2);
  expect(within(interview).getByText('Entretien')).toBeInTheDocument();
  expect(
    within(interview).getByText('20 mars 2020 à 14 h 30'),
  ).toBeInTheDocument();
  expect(within(visit).getByText('Passage sur place')).toBeInTheDocument();
  expect(within(visit).getByText('10 mars 2020')).toBeInTheDocument();
  expect(
    within(application).getByText('Candidature envoyée'),
  ).toBeInTheDocument();
  expect(within(application).getByText('2 mars 2020')).toBeInTheDocument();
});

it('marque « À venir » une action datée dans le futur, et pas une action passée', () => {
  seed(
    action({
      type: 'INTERVIEW',
      date: '2999-01-15',
      note,
      time: '09:00',
      format: null,
      position: null,
    }),
    action({ type: 'PHONE_CALL', date: '2020-03-02', note, direction: null }),
  );
  show();

  expect(within(entry(0)).getByText('À venir')).toBeInTheDocument();
  expect(within(entry(1)).queryByText('À venir')).not.toBeInTheDocument();
});

async function addApplication(user: User) {
  await addAction(user);
  await chooseType(user, 'Candidature envoyée');
  await setDate(user, '2020-03-12');
  await user.selectOptions(await field('Comment ?'), 'Email');
  await typeIn(user, 'Poste visé', 'Vendeur');
  await save(user);
}

it('affiche la candidature avec son canal et son poste', async () => {
  const { user } = show();

  await addApplication(user);

  expect(within(entry(0)).getByText('Candidature envoyée')).toBeInTheDocument();
  expect(within(entry(0)).getByText('12 mars 2020')).toBeInTheDocument();
  expect(within(entry(0)).getByText('Par email')).toBeInTheDocument();
  expect(within(entry(0)).getByText('Poste : Vendeur')).toBeInTheDocument();
});

it('garde la candidature pour la prochaine ouverture du journal', async () => {
  const { user, unmount } = show();
  await addApplication(user);

  unmount();
  show();

  expect(within(entry(0)).getByText('Poste : Vendeur')).toBeInTheDocument();
});

it('rend le focus au titre après l’enregistrement', async () => {
  const { user } = show();

  await addApplication(user);

  expect(
    screen.getByRole('heading', { name: `Démarche avec ${name}` }),
  ).toHaveFocus();
});

it('n’enregistre qu’une action quand on clique deux fois sur Enregistrer', async () => {
  const { user } = show();
  await addAction(user);
  await chooseType(user, 'Relance');
  await setDate(user, '2020-03-12');

  await user.dblClick(screen.getByRole('button', { name: 'Enregistrer' }));

  expect(entries()).toHaveLength(1);
});

it('tient un journal par établissement', () => {
  seed(
    action({ type: 'PHONE_CALL', date: '2020-03-02', note, direction: null }),
  );
  show(vi.fn(), '11111111111111');

  expect(
    screen.getByText(`Tu n’as encore rien fait avec ${name}.`),
  ).toBeInTheDocument();
});

it('demande l’heure d’un entretien, et seulement la sienne', async () => {
  const { user } = show();
  await addAction(user);

  expect(screen.queryByLabelText('Heure')).not.toBeInTheDocument();
  await chooseType(user, 'Entretien');
  await setDate(user, '2020-03-12');
  await typeIn(user, 'Heure', '14:30');
  await user.selectOptions(await field('Format'), 'Visio');
  await save(user);

  expect(
    within(entry(0)).getByText('12 mars 2020 à 14 h 30'),
  ).toBeInTheDocument();
});

it.each([
  { type: 'Candidature envoyée', label: 'Comment ?' },
  { type: 'Candidature envoyée', label: 'Poste visé' },
  { type: 'Relance', label: 'Comment ?' },
  { type: 'Appel', label: 'Qui a appelé ?' },
  { type: 'Passage sur place', label: 'Résultat' },
  { type: 'Entretien', label: 'Heure' },
  { type: 'Entretien', label: 'Format' },
  { type: 'Entretien', label: 'Poste visé' },
  { type: 'Réponse positive', label: 'Type de contrat' },
  { type: 'Réponse positive', label: 'Date de début' },
  { type: 'Réponse négative', label: 'Raison' },
  { type: 'Autre', label: 'De quoi s’agit-il ?' },
])('propose pour « $type » le champ « $label »', async ({ type, label }) => {
  const { user } = show();
  await addAction(user);

  await chooseType(user, type);

  expect(await field(label)).toBeInTheDocument();
});

it('ne garde pas les détails d’un autre type quand on change de type', async () => {
  const { user } = show();
  await addAction(user);
  await chooseType(user, 'Candidature envoyée');
  await setDate(user, '2020-03-12');
  await typeIn(user, 'Poste visé', 'Vendeur');

  await chooseType(user, 'Appel');
  await save(user);

  expect(within(entry(0)).getByText('Appel')).toBeInTheDocument();
  expect(
    within(entry(0)).queryByText('Poste : Vendeur'),
  ).not.toBeInTheDocument();
});

it('modifie une action existante', async () => {
  seed(
    action({
      type: 'APPLICATION_SENT',
      date: '2020-03-02',
      note,
      channel: null,
      position: null,
    }),
  );
  const { user } = show();
  await user.click(
    screen.getByRole('button', {
      name: 'Modifier : Candidature envoyée, 2 mars 2020',
    }),
  );
  expect(
    screen.getByRole('radio', { name: 'Candidature envoyée' }),
  ).toBeChecked();

  await chooseType(user, 'Réponse négative');
  await user.selectOptions(await field('Raison'), 'Poste déjà pourvu');
  await save(user);

  expect(entries()).toHaveLength(1);
  expect(within(entry(0)).getByText('Réponse négative')).toBeInTheDocument();
  expect(within(entry(0)).getByText('Poste déjà pourvu')).toBeInTheDocument();
});

const deleteLabel = 'Supprimer : Appel, 2 mars 2020';

it('garde l’action quand on renonce à la suppression', async () => {
  seed(
    action({ type: 'PHONE_CALL', date: '2020-03-02', note, direction: null }),
  );
  const { user } = show();
  await user.click(screen.getByRole('button', { name: deleteLabel }));

  expect(screen.getByRole('button', { name: 'Garder' })).toHaveFocus();
  await user.click(screen.getByRole('button', { name: 'Garder' }));

  expect(entries()).toHaveLength(1);
});

it('supprime l’action après confirmation', async () => {
  seed(
    action({ type: 'PHONE_CALL', date: '2020-03-02', note, direction: null }),
  );
  const { user } = show();
  await user.click(screen.getByRole('button', { name: deleteLabel }));
  await user.click(screen.getByRole('button', { name: 'Supprimer' }));

  expect(
    screen.getByText(`Tu n’as encore rien fait avec ${name}.`),
  ).toBeInTheDocument();
});

it('affiche l’erreur d’un type absent, sans rien enregistrer quand on annule', async () => {
  const { user } = show();
  await addAction(user);

  await save(user);
  expect(await screen.findByText('Choisis un type d’action.')).toBeVisible();

  await user.click(screen.getByRole('button', { name: 'Annuler' }));
  expect(
    screen.getByText(`Tu n’as encore rien fait avec ${name}.`),
  ).toBeInTheDocument();
});

it('garde la saisie et annonce l’échec quand le navigateur refuse d’enregistrer', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('Quota dépassé', 'QuotaExceededError');
  });
  const { user } = show();
  await addAction(user);
  await chooseType(user, 'Relance');
  await typeIn(user, 'Note (facultatif)', 'À refaire.');

  await save(user);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Ce changement n’a pas pu être enregistré.',
  );
  expect(await field('Note (facultatif)')).toHaveValue('À refaire.');
});

it('revient à l’historique sans rien enregistrer avec la flèche', async () => {
  seed(
    action({ type: 'PHONE_CALL', date: '2020-03-02', note, direction: null }),
  );
  const { user } = show();
  await addAction(user);

  await user.click(
    screen.getByRole('button', { name: 'Retour à l’historique' }),
  );

  expect(entries()).toHaveLength(1);
});

it('appelle la fermeture quand on clique sur la croix', async () => {
  const { user, onClose } = show();

  await user.click(screen.getByRole('button', { name: 'Fermer' }));

  expect(onClose).toHaveBeenCalledOnce();
});
