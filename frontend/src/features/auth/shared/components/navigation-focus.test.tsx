import { act, screen } from '@testing-library/react';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { anonymousResponse, sessionResponse } from '@/test/handlers/auth';
import { readsSessionAs } from '@/test/auth';
import {
  authScreens,
  codeScreenRoute,
  navigateFromOutside,
  termsCheckbox,
} from '@/test/auth-screens';
import { renderWithProviders } from '@/test/render';
import { server } from '@/test/server';
import { sessionKey } from '@/features/auth/queries';
import { AuthenticatedScreen } from '../../account/components/authenticated-screen';

beforeEach(() => {
  server.use(
    http.get('/api/auth/google/status', () =>
      HttpResponse.json({
        pending: false,
        flowId: null,
        stage: null,
        errorCode: null,
      }),
    ),
  );
});
afterEach(() => {
  window.history.replaceState(null, '', '/');
});

function anonymousUntilSignedIn() {
  let account: string | null | undefined;
  const signIn = (username: string | null) => () => {
    account = username;
    return sessionResponse(username);
  };
  server.use(
    http.get('/api/auth/session', () =>
      account === undefined ? anonymousResponse() : sessionResponse(account),
    ),
    http.post('/api/auth/login', signIn('jeune_fictif')),
    // The account of a confirmed code chooses its username next.
    http.post('/api/auth/signup/confirm', signIn(null)),
    http.get('/api/auth/google/status', () =>
      HttpResponse.json({
        pending: true,
        flowId: 'f496c1d5-03e2-4a40-a887-115580f9113e',
        stage: 'CHOOSE_USERNAME',
        errorCode: null,
      }),
    ),
    http.post('/api/auth/google/signup', signIn('Chosen')),
  );
}

it.each([
  {
    from: '/connexion',
    route: '/connexion',
    action: 'Mot de passe oublié ?',
    to: 'Mot de passe oublié',
  },
  {
    from: '/connexion',
    route: '/connexion',
    action: 'Pas encore de compte ? S’inscrire',
    to: 'Créer un compte',
  },
  {
    from: '/inscription',
    route: '/inscription',
    action: 'Déjà un compte ? Se connecter',
    to: 'Se connecter',
  },
  {
    from: '/mot-de-passe-oublie',
    route: '/mot-de-passe-oublie',
    action: 'Revenir à la connexion',
    to: 'Se connecter',
  },
  {
    from: '/reinitialiser-mot-de-passe',
    route: '/reinitialiser-mot-de-passe',
    action: 'Se connecter',
    to: 'Se connecter',
  },
  {
    from: '/reinitialiser-mot-de-passe',
    route: '/reinitialiser-mot-de-passe',
    action: 'Demander un nouveau lien',
    to: 'Mot de passe oublié',
  },
  {
    from: '/inscription/code',
    route: codeScreenRoute(),
    action: 'Modifier l’adresse email',
    to: 'Créer un compte',
  },
  {
    from: '/confirmation-google',
    route: '/confirmation-google',
    action: 'Revenir à Google',
    to: 'Continuer avec Google',
  },
  {
    from: '/connexion-google',
    route: '/connexion-google',
    action: 'Revenir à la connexion',
    to: 'Se connecter',
  },
])(
  'met le focus sur le titre « $to » quand « $action » mène de $from à cet écran',
  async ({ route, action, to }) => {
    readsSessionAs(anonymousResponse);
    renderWithProviders(authScreens, {
      route,
    });

    await userEvent
      .setup()
      .click(await screen.findByRole('link', { name: action }));

    expect(await screen.findByRole('heading', { name: to })).toHaveFocus();
  },
);

it('met le focus sur le titre de l’écran précédent au retour arrière', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(authScreens, { route: '/connexion' });
  await userEvent.setup().click(
    await screen.findByRole('link', {
      name: 'Pas encore de compte ? S’inscrire',
    }),
  );
  expect(
    await screen.findByRole('heading', { name: 'Créer un compte' }),
  ).toHaveFocus();

  await act(() => navigateFromOutside()(-1));

  expect(
    await screen.findByRole('heading', { name: 'Se connecter' }),
  ).toHaveFocus();
});

it('met le focus sur le contenu quand la connexion mène à la carte', async () => {
  anonymousUntilSignedIn();
  renderWithProviders(authScreens, { route: '/connexion' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Email ou pseudo'), 'jeune');
  await user.type(screen.getByLabelText('Mot de passe'), 'phrase fictive');
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  await screen.findByRole('heading', { name: 'Carte' });
  expect(screen.getByRole('main')).toHaveFocus();
});

it('met le focus sur le titre du choix du pseudo quand la confirmation de l’inscription y mène', async () => {
  anonymousUntilSignedIn();
  renderWithProviders(authScreens, { route: codeScreenRoute() });
  const user = userEvent.setup();
  await user.type(
    await screen.findByRole('textbox', {
      name: 'Code à 6 chiffres reçu par email',
    }),
    '123456',
  );

  expect(
    await screen.findByRole('heading', { name: 'Choisis ton pseudo' }),
  ).toHaveFocus();
});

it('met le focus sur le contenu quand le choix du pseudo Google mène à la carte', async () => {
  anonymousUntilSignedIn();
  renderWithProviders(authScreens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Pseudo'), 'Chosen');
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  await screen.findByRole('heading', { name: 'Carte' });
  expect(screen.getByRole('main')).toHaveFocus();
});

it('laisse le focus sur un élément qui reste dans la page quand l’écran change', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(
    <>
      <button>Hors des écrans</button>
      {authScreens}
    </>,
    { route: '/connexion' },
  );
  await screen.findByLabelText('Email ou pseudo');
  const outside = screen.getByRole('button', { name: 'Hors des écrans' });
  outside.focus();

  await act(() => navigateFromOutside()('/inscription'));

  await screen.findByRole('heading', { name: 'Créer un compte' });
  expect(outside).toHaveFocus();
});

it.each(['/connexion', '/inscription'])(
  'laisse le focus au navigateur quand %s est visitée directement avec une session active',
  async (route) => {
    renderWithProviders(authScreens, { route });

    await screen.findByRole('heading', { name: 'Carte' });
    expect(document.body).toHaveFocus();
  },
);

function SelfRemovingButton() {
  const [present, setPresent] = useState(true);
  return present ? (
    <button onClick={() => setPresent(false)}>Effacer</button>
  ) : (
    <p>Effacé</p>
  );
}

it('ne prend pas le focus quand un bandeau apparaît après une perte de focus sans rapport', async () => {
  const { client } = renderWithProviders(
    <AuthenticatedScreen title="Carte">
      <SelfRemovingButton />
    </AuthenticatedScreen>,
  );
  await userEvent
    .setup()
    .click(await screen.findByRole('button', { name: 'Effacer' }));
  await screen.findByText('Effacé');
  expect(document.body).toHaveFocus();

  readsSessionAs(() => HttpResponse.error());
  await act(() => client.invalidateQueries({ queryKey: sessionKey }));

  expect(await screen.findByRole('alert')).toBeVisible();
  expect(document.body).toHaveFocus();
});
