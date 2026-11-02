import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, it } from 'vitest';
import {
  anonymousResponse,
  sessionResponse,
  signupAcceptedResponse,
} from '@/test/handlers/auth';
import { googleStage, holdNextRead, readsSessionAs } from '@/test/auth';
import {
  authScreens,
  codeScreenRoute,
  termsCheckbox,
} from '@/test/auth-screens';
import { renderWithProviders } from '@/test/render';
import { server } from '@/test/server';

const password = 'Une phrase fictive 1 avec accents 🔑';

const noStage = () => googleStage(null);

beforeEach(() => {
  readsSessionAs(anonymousResponse);
  noStage();
});
afterEach(() => {
  window.history.replaceState(null, '', '/');
});

function expectTitle(heading: string) {
  expect(document.title).toBe(`${heading} · Emploi Go`);
  // One source: a second title in the head would stay next to this one.
  // `<title>` has no ARIA role: this is the one CSS query the Vitest tests allow (see docs/frontend/qualite.md).
  expect(document.querySelectorAll('title')).toHaveLength(1);
}

it.each([
  { screen: 'la connexion', route: '/connexion', heading: 'Se connecter' },
  {
    screen: 'l’inscription',
    route: '/inscription',
    heading: 'Créer un compte',
  },
  {
    screen: 'le mot de passe oublié',
    route: '/mot-de-passe-oublie',
    heading: 'Mot de passe oublié',
  },
  {
    screen: 'la saisie du code',
    route: codeScreenRoute(),
    heading: 'Vérifie tes emails',
  },
  {
    screen: 'le nouveau mot de passe',
    route: '/reinitialiser-mot-de-passe',
    heading: 'Nouveau mot de passe',
  },
  {
    screen: 'la confirmation de l’email Google',
    route: '/confirmation-google',
    heading: 'Confirmer ton email',
  },
  {
    screen: 'Google sans étape en cours',
    route: '/connexion-google',
    heading: 'Continuer avec Google',
  },
  {
    screen: 'Google, choix du pseudo',
    route: '/connexion-google',
    heading: 'Choisis ton pseudo',
    stage: 'CHOOSE_USERNAME',
  },
  {
    screen: 'Google, accès au compte',
    route: '/connexion-google',
    heading: 'Retrouve ton compte',
    stage: 'ACCOUNT_ACCESS_REQUIRED',
  },
  {
    screen: 'Google, confirmation de l’email',
    route: '/connexion-google',
    heading: 'Confirme ton email',
    stage: 'EMAIL_CONFIRMATION_REQUIRED',
  },
  {
    screen: 'Google, association',
    route: '/connexion-google',
    heading: 'Associer Google à ton compte',
    stage: 'CONFIRM_LINK',
  },
])(
  'donne à $screen le titre de son écran pour titre du document',
  async ({ route, heading, stage }) => {
    googleStage(stage ?? null);
    renderWithProviders(authScreens, { route });

    await screen.findByRole('heading', { name: heading });
    expectTitle(heading);
  },
);

it('titre la page protégée dès la coquille, avant que la session soit connue', async () => {
  const answer = holdNextRead();
  renderWithProviders(authScreens, { route: '/' });

  await screen.findByRole('status');
  expectTitle('Carte');

  await answer(sessionResponse());
  await screen.findByRole('heading', { name: 'Carte' });
  expectTitle('Carte');
});

it('titre l’erreur plein écran de la session', async () => {
  readsSessionAs(() => HttpResponse.error());
  renderWithProviders(authScreens, { route: '/' });

  await screen.findByRole('heading', { name: 'Connexion indisponible' });
  expectTitle('Connexion indisponible');
});

it('change le titre du document quand l’inscription envoyée laisse la place à la saisie du code', async () => {
  server.use(http.post('/api/auth/signup', () => signupAcceptedResponse()));
  renderWithProviders(authScreens, { route: '/inscription' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Email'), 'jeune@exemple.test');
  await user.type(screen.getByLabelText('Mot de passe'), password);
  await user.type(screen.getByLabelText('Confirmer le mot de passe'), password);
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));

  await screen.findByRole('heading', { name: 'Vérifie tes emails' });
  expectTitle('Vérifie tes emails');
});

it('remplace le titre du document par celui de l’écran où mène un lien', async () => {
  renderWithProviders(authScreens, { route: '/connexion' });
  await userEvent
    .setup()
    .click(await screen.findByRole('link', { name: 'Mot de passe oublié ?' }));

  await screen.findByRole('heading', { name: 'Mot de passe oublié' });
  expectTitle('Mot de passe oublié');
});
