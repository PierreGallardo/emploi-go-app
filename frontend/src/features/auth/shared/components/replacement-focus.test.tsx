import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, it } from 'vitest';
import {
  anonymousResponse,
  sessionResponse,
  signupAcceptedResponse,
} from '@/test/handlers/auth';
import { googleStage, readsSessionAs, recoveryLink } from '@/test/auth';
import { authScreens, termsCheckbox } from '@/test/auth-screens';
import { renderWithProviders } from '@/test/render';
import { server } from '@/test/server';

const password = 'Une phrase fictive 1 avec accents 🔑';

beforeEach(() => {
  readsSessionAs(anonymousResponse);
});
afterEach(() => {
  window.history.replaceState(null, '', '/');
});

it('met le focus sur le titre quand l’inscription envoyée laisse la place à l’écran du code', async () => {
  server.use(http.post('/api/auth/signup', () => signupAcceptedResponse()));
  renderWithProviders(authScreens, { route: '/inscription' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Email'), 'jeune@exemple.test');
  await user.type(screen.getByLabelText('Mot de passe'), password);
  await user.type(screen.getByLabelText('Confirmer le mot de passe'), password);
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));

  expect(
    await screen.findByRole('heading', {
      name: 'Vérifie tes emails',
    }),
  ).toHaveFocus();
});

it('met le focus sur le titre quand le mot de passe est changé', async () => {
  server.use(
    http.post('/api/auth/password/reset', () =>
      HttpResponse.json({ changed: true }),
    ),
  );
  window.history.replaceState(
    null,
    '',
    `/reinitialiser-mot-de-passe#${recoveryLink}`,
  );
  renderWithProviders(authScreens, { route: '/reinitialiser-mot-de-passe' });
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('Nouveau mot de passe'),
    password,
  );
  await user.type(
    screen.getByLabelText('Confirmer le nouveau mot de passe'),
    password,
  );
  await user.click(
    screen.getByRole('button', { name: 'Changer mon mot de passe' }),
  );

  expect(
    await screen.findByRole('heading', { name: 'Mot de passe changé' }),
  ).toHaveFocus();
});

it('met le focus sur le titre quand l’adresse Google est confirmée', async () => {
  googleStage(null);
  server.use(
    http.post(
      '/api/auth/google/email/confirm',
      () => new HttpResponse(null, { status: 204 }),
    ),
  );
  window.history.replaceState(null, '', `/confirmation-google#${recoveryLink}`);
  renderWithProviders(authScreens, { route: '/confirmation-google' });
  await userEvent
    .setup()
    .click(await screen.findByRole('button', { name: 'Confirmer mon email' }));

  expect(
    await screen.findByRole('heading', { name: 'Adresse confirmée' }),
  ).toHaveFocus();
});

it('met le focus sur le titre quand une étape Google en remplace une autre', async () => {
  googleStage('ACCOUNT_ACCESS_REQUIRED');
  server.use(http.post('/api/auth/google/access', () => sessionResponse()));
  renderWithProviders(authScreens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Email ou pseudo'), 'jeune');
  await user.type(screen.getByLabelText('Mot de passe'), password);
  await user.click(screen.getByRole('button', { name: 'Vérifier mon compte' }));

  expect(
    await screen.findByRole('heading', {
      name: 'Associer Google à ton compte',
    }),
  ).toHaveFocus();
});

it('ne déplace pas le focus au premier chargement d’une étape Google', async () => {
  googleStage('CHOOSE_USERNAME');
  renderWithProviders(authScreens, { route: '/connexion-google' });

  await screen.findByLabelText('Pseudo');
  expect(document.body).toHaveFocus();
});
