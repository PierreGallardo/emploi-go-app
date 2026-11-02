import { act, screen } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/queries';
import { holdNextRead, readsSessionAs, signedInAs } from '@/test/auth';
import {
  anonymousResponse,
  problemResponse,
  sessionResponse,
  signupAcceptedResponse,
} from '@/test/handlers/auth';
import { renderWithProviders } from '@/test/render';
import { postTo, recordRequests } from '@/test/requests';
import { settled } from '@/test/server';
import { SignupScreen } from './signup-screen';
import { RegistrationCodeScreen } from './registration-code-screen';
import { termsCheckbox } from '@/test/auth-screens';

const screens = (
  <Routes>
    <Route path="/inscription" element={<SignupScreen />} />
    <Route path="/inscription/code" element={<RegistrationCodeScreen />} />
    <Route path="/" element={<h1>Carte</h1>} />
  </Routes>
);

const signupPath = '/api/auth/signup';
const email = 'fictif@example.invalid';
const password = 'Une phrase fictive 1 avec accents 🔑';

const signupAccepted = () => signupAcceptedResponse();

async function fillSignup(user: UserEvent, confirmation = password) {
  await user.type(screen.getByLabelText('Email', { exact: true }), email);
  await user.type(
    screen.getByLabelText('Mot de passe', { exact: true }),
    password,
  );
  await user.type(
    screen.getByLabelText('Confirmer le mot de passe'),
    confirmation,
  );
  await user.click(termsCheckbox());
}

it('affiche des squelettes pendant la lecture de la session, sans texte de vérification', async () => {
  holdNextRead();
  renderWithProviders(screens, { route: '/inscription' });

  expect(await screen.findByRole('status')).toHaveTextContent('Chargement…');
  expect(screen.getAllByRole('status')).toHaveLength(1);
  expect(
    screen.getByRole('heading', { name: 'Créer un compte' }),
  ).toBeVisible();
  expect(screen.queryByLabelText('Confirmer le mot de passe')).toBeNull();
  expect(screen.queryByText(/Vérification de la connexion/)).toBeNull();
});

it('affiche le formulaire quand la session lue est anonyme', async () => {
  const release = holdNextRead();
  renderWithProviders(screens, { route: '/inscription' });

  await act(async () => {
    release(anonymousResponse());
  });
  expect(
    await screen.findByLabelText('Confirmer le mot de passe'),
  ).toBeVisible();
  expect(screen.queryByLabelText('Pseudo')).toBeNull();
  expect(screen.getAllByRole('status').at(0)).toHaveTextContent(
    'Chargement terminé.',
  );
});

it('relit la session gardée en cache à l’arrivée sur l’inscription et montre le formulaire quand elle n’existe plus', async () => {
  const release = holdNextRead();
  renderWithProviders(screens, {
    route: '/inscription',
    seed: signedInAs('jeune_demo'),
  });

  expect(await screen.findByRole('status')).toHaveTextContent('Chargement…');
  expect(screen.queryByRole('heading', { name: 'Carte' })).toBeNull();
  expect(screen.queryByLabelText('Confirmer le mot de passe')).toBeNull();
  await act(async () => {
    release(anonymousResponse());
  });

  expect(
    await screen.findByLabelText('Confirmer le mot de passe'),
  ).toBeVisible();
  expect(screen.queryByRole('heading', { name: 'Carte' })).toBeNull();
});

it('garde le bouton Créer mon compte actif pendant une relecture de la session anonyme', async () => {
  readsSessionAs(anonymousResponse);
  const { client } = renderWithProviders(screens, { route: '/inscription' });
  await screen.findByRole('button', { name: 'Créer mon compte' });
  const release = holdNextRead();

  let reading!: Promise<void>;
  await act(async () => {
    reading = client.invalidateQueries({ queryKey: sessionKey });
  });

  expect(client.isFetching({ queryKey: sessionKey })).toBe(1);
  expect(
    screen.getByRole('button', { name: 'Créer mon compte' }),
  ).toBeEnabled();
  expect(
    screen.getByRole('button', { name: 'Continuer avec Google' }),
  ).toBeEnabled();
  await act(async () => {
    release(anonymousResponse());
    await reading;
  });
});

it('ne envoie pas une inscription incomplète ni une inscription aux mots de passe différents', async () => {
  readsSessionAs(anonymousResponse);
  const signups = postTo(signupPath, signupAccepted);
  renderWithProviders(screens, { route: '/inscription' });
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole('button', { name: 'Créer mon compte' }),
  );
  await settled();
  expect(signups).toEqual([]);

  await fillSignup(user, 'Autre phrase fictive');
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));
  expect(
    await screen.findByText('Les mots de passe doivent être identiques.'),
  ).toBeVisible();
  await settled();
  expect(signups).toEqual([]);
});

it('n’envoie pas l’inscription tant que la case des conditions n’est pas cochée', async () => {
  readsSessionAs(anonymousResponse);
  const signups = postTo(signupPath, signupAccepted);
  renderWithProviders(screens, { route: '/inscription' });
  const user = userEvent.setup();
  await screen.findByRole('button', { name: 'Créer mon compte' });
  await fillSignup(user);
  await user.click(termsCheckbox());
  expect(termsCheckbox()).not.toBeChecked();

  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));

  expect(
    await screen.findByText(
      'Accepte les conditions d’utilisation pour créer ton compte.',
    ),
  ).toHaveAttribute('role', 'alert');
  expect(termsCheckbox()).toBeInvalid();
  expect(termsCheckbox()).toHaveFocus();
  expect(termsCheckbox()).toHaveAccessibleDescription(
    'Accepte les conditions d’utilisation pour créer ton compte.',
  );
  await settled();
  expect(signups).toEqual([]);
});

it('conserve les saisies après une panne du serveur et n’ouvre pas la saisie du code', async () => {
  readsSessionAs(anonymousResponse);
  const signups = postTo(signupPath, () => problemResponse(503));
  renderWithProviders(screens, { route: '/inscription' });
  const user = userEvent.setup();
  await screen.findByRole('heading', { name: 'Créer un compte' });
  await fillSignup(user);
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'temporairement indisponible',
  );
  expect(screen.getByLabelText('Email', { exact: true })).toHaveValue(email);
  expect(screen.getByLabelText('Mot de passe', { exact: true })).toHaveValue(
    password,
  );
  expect(screen.getByLabelText('Confirmer le mot de passe')).toHaveValue(
    password,
  );
  expect(
    screen.queryByRole('heading', { name: 'Vérifie tes emails' }),
  ).not.toBeInTheDocument();
  expect(signups).toHaveLength(1);
});

it('reprend la même demande après une panne, sans la rejouer toute seule, et ouvre la saisie du code', async () => {
  readsSessionAs(anonymousResponse);
  const signups = postTo(
    signupPath,
    () => problemResponse(503),
    signupAccepted,
  );
  renderWithProviders(screens, { route: '/inscription' });
  const user = userEvent.setup();
  await screen.findByRole('heading', { name: 'Créer un compte' });
  await fillSignup(user);
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));
  await screen.findByRole('alert');
  await settled();
  expect(signups).toHaveLength(1);

  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));
  expect(
    await screen.findByRole('heading', {
      name: 'Vérifie tes emails',
    }),
  ).toBeVisible();
  expect(signups).toHaveLength(2);
  expect(signups[1]).toEqual(signups[0]);
  expect(screen.getByText(/Email envoyé à/)).toHaveTextContent(email);
});

it('une inscription dont le code a été demandé il y a moins d’une minute reste sur le formulaire et dit où saisir le code', async () => {
  readsSessionAs(anonymousResponse);
  const signups = postTo(signupPath, () =>
    problemResponse(429, 'MAIL_RECENTLY_SENT', { 'Retry-After': '45' }),
  );
  renderWithProviders(screens, { route: '/inscription' });
  const user = userEvent.setup();
  await screen.findByRole('heading', { name: 'Créer un compte' });
  await fillSignup(user);
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Un code a déjà été demandé pour cette adresse il y a moins d’une minute. Saisis-le sur la page qui l’a demandé, ou recommence après l’attente.',
  );
  expect(
    screen.getByRole('heading', { name: 'Créer un compte' }),
  ).toBeVisible();
  expect(screen.getByLabelText('Email', { exact: true })).toHaveValue(email);
  expect(signups).toHaveLength(1);
});

it('rejoint l’accueil depuis l’inscription lorsque la session est déjà établie', async () => {
  readsSessionAs(() => sessionResponse('jeune_fictif'));
  const requests = recordRequests();
  renderWithProviders(screens, { route: '/inscription' });

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  await settled();
  expect(requests.routes().filter((route) => route.startsWith('POST'))).toEqual(
    [],
  );
});

it.each([
  {
    label: 'laissée décochée',
    choose: async () => {},
    rememberMe: false,
  },
  {
    label: 'cochée',
    choose: (user: UserEvent, box: HTMLElement) => user.click(box),
    rememberMe: true,
  },
])(
  'envoie la case « Rester connecté » $label avec l’inscription',
  async ({ choose, rememberMe }) => {
    readsSessionAs(anonymousResponse);
    const signups = postTo(signupPath, () => problemResponse(503));
    renderWithProviders(screens, { route: '/inscription' });
    const user = userEvent.setup();
    const box = await screen.findByRole('checkbox', {
      name: 'Rester connecté',
    });
    expect(box).not.toBeChecked();
    await choose(user, box);
    await fillSignup(user);

    await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));

    await screen.findByRole('alert');
    expect(signups).toEqual([
      {
        email,
        password,
        confirmation: password,
        acceptedTerms: true,
        rememberMe,
      },
    ]);
  },
);
