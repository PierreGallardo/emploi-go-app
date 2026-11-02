import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  sessionResponse,
} from '@/test/handlers/auth';
import { readsSessionAs } from '@/test/auth';
import { authScreens } from '@/test/auth-screens';
import { renderWithProviders } from '@/test/render';
import { postTo } from '@/test/requests';

const route = '/inscription/pseudo';

/** Sends the username « Pris », which the API refuses as taken. */
async function sendTakenUsername() {
  readsSessionAs(() => sessionResponse(null));
  const bodies = postTo('/api/auth/username', () =>
    problemResponse(409, 'USERNAME_UNAVAILABLE'),
  );
  renderWithProviders(authScreens, { route });
  const user = userEvent.setup();
  await user.type(
    await screen.findByRole('textbox', { name: 'Pseudo' }),
    'Pris',
  );
  expect(screen.getByText('Étape 3 sur 3')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Continuer' }));
  await screen.findByRole('alert');
  return { bodies };
}

it('garde le pseudo pris dans le champ et le dit déjà pris', async () => {
  await sendTakenUsername();

  expect(screen.getByRole('alert')).toHaveTextContent(
    'Ce pseudo est déjà pris.',
  );
  const field = screen.getByRole('textbox', { name: 'Pseudo' });
  expect(field).toHaveValue('Pris');
  expect(field).toBeInvalid();
});

it('envoie le pseudo saisi à la route du pseudo', async () => {
  const { bodies } = await sendTakenUsername();

  expect(bodies).toEqual([{ username: 'Pris' }]);
});

it('rend le focus au champ du pseudo refusé', async () => {
  await sendTakenUsername();

  expect(screen.getByRole('textbox', { name: 'Pseudo' })).toHaveFocus();
});

it('dit la règle du pseudo quand l’API le refuse et rend le focus au champ', async () => {
  readsSessionAs(() => sessionResponse(null));
  postTo('/api/auth/username', () => problemResponse(422, 'INVALID_USERNAME'));
  renderWithProviders(authScreens, { route });
  const user = userEvent.setup();
  await user.type(
    await screen.findByRole('textbox', { name: 'Pseudo' }),
    'Refuse',
  );

  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '2 à 32 caractères : lettres sans accent, chiffres, _ et .',
  );
  const field = screen.getByRole('textbox', { name: 'Pseudo' });
  expect(field).toBeInvalid();
  expect(field).toHaveFocus();
});

it('ne décrit plus le pseudo comme pris dès que la saisie change', async () => {
  readsSessionAs(() => sessionResponse(null));
  postTo('/api/auth/username', () =>
    problemResponse(409, 'USERNAME_UNAVAILABLE'),
  );
  renderWithProviders(authScreens, { route });
  const user = userEvent.setup();
  const field = await screen.findByRole('textbox', { name: 'Pseudo' });
  await user.type(field, 'Pris');
  await user.click(screen.getByRole('button', { name: 'Continuer' }));
  await screen.findByRole('alert');

  await user.type(field, '2');

  expect(field).toBeValid();
});

it('garde la saisie et dit la panne quand le serveur ne répond pas', async () => {
  readsSessionAs(() => sessionResponse(null));
  postTo('/api/auth/username', () => problemResponse(503));
  renderWithProviders(authScreens, { route });
  const user = userEvent.setup();
  await user.type(
    await screen.findByRole('textbox', { name: 'Pseudo' }),
    'Choisi',
  );

  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'temporairement indisponible',
  );
  expect(screen.getByRole('textbox', { name: 'Pseudo' })).toHaveValue('Choisi');
});

it('mène à la carte quand le pseudo a déjà été choisi dans un autre onglet', async () => {
  let account: string | null = null;
  readsSessionAs(() => sessionResponse(account));
  postTo('/api/auth/username', () => {
    account = 'Ailleurs';
    return problemResponse(409, 'USERNAME_ALREADY_CHOSEN');
  });
  renderWithProviders(authScreens, { route });
  const user = userEvent.setup();
  await user.type(
    await screen.findByRole('textbox', { name: 'Pseudo' }),
    'Choisi',
  );

  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
});

it('n’affiche pas la case « Rester connecté » pour le choix du pseudo', async () => {
  readsSessionAs(() => sessionResponse(null));
  renderWithProviders(authScreens, { route });
  await screen.findByRole('textbox', { name: 'Pseudo' });

  expect(
    screen.queryByRole('checkbox', { name: 'Rester connecté' }),
  ).not.toBeInTheDocument();
});

it('envoie le seul pseudo puis mène à la carte', async () => {
  let account: string | null = null;
  readsSessionAs(() => sessionResponse(account));
  const bodies = postTo('/api/auth/username', () => {
    account = 'Choisi';
    return sessionResponse(account);
  });
  renderWithProviders(authScreens, { route });
  const user = userEvent.setup();
  await user.type(
    await screen.findByRole('textbox', { name: 'Pseudo' }),
    'Choisi',
  );

  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  expect(bodies).toEqual([{ username: 'Choisi' }]);
});

it.each([
  { cas: 'un visiteur anonyme', answer: anonymousResponse, to: 'Se connecter' },
  {
    cas: 'un compte qui a son pseudo',
    answer: () => sessionResponse(),
    to: 'Carte',
  },
])('renvoie ailleurs $cas', async ({ answer, to }) => {
  readsSessionAs(answer);
  renderWithProviders(authScreens, { route });

  expect(await screen.findByRole('heading', { name: to })).toBeVisible();
});
