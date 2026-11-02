import { screen, waitFor } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router';
import { expect, it, onTestFinished } from 'vitest';
import { problemResponse, sessionResponse } from '@/test/handlers/auth';
import { renderWithProviders } from '@/test/render';
import { postTo, recordRequests } from '@/test/requests';
import { server, settled } from '@/test/server';
import { GoogleScreen } from './google-screen';
import { GoogleConfirmationScreen } from './google-confirmation-screen';
import { termsCheckbox } from '@/test/auth-screens';

const flowId = 'f496c1d5-03e2-4a40-a887-115580f9113e';
const fragmentUrl = '/#00000000-0000-0000-0000-000000000000.' + 'a'.repeat(43);

const screens = (
  <Routes>
    <Route path="/connexion-google" element={<GoogleScreen />} />
    <Route path="/confirmation-google" element={<GoogleConfirmationScreen />} />
    <Route path="/" element={<h1>Carte</h1>} />
  </Routes>
);

type GoogleStage =
  | 'CHOOSE_USERNAME'
  | 'ACCOUNT_ACCESS_REQUIRED'
  | 'CONFIRM_LINK'
  | 'EMAIL_CONFIRMATION_REQUIRED';

/** The first status read finds the flow; the reads after it find nothing pending. */
function stage(value: GoogleStage) {
  let reads = 0;
  server.use(
    http.get('/api/auth/google/status', () => {
      reads += 1;
      return HttpResponse.json(
        reads === 1
          ? { pending: true, flowId, stage: value, errorCode: null }
          : { pending: false, flowId: null, stage: null, errorCode: null },
      );
    }),
  );
}

function returnedWithError(errorCode: string) {
  server.use(
    http.get('/api/auth/google/status', () =>
      HttpResponse.json({
        pending: false,
        flowId: null,
        stage: null,
        errorCode,
      }),
    ),
  );
}

async function verifyLocalAccount(user: UserEvent) {
  await user.type(await screen.findByLabelText('Email ou pseudo'), 'Local');
  await user.type(screen.getByLabelText('Mot de passe'), 'phrase fictive');
  await user.click(screen.getByRole('button', { name: 'Vérifier mon compte' }));
}

it('affiche la collision de pseudo Google et conserve la saisie', async () => {
  stage('CHOOSE_USERNAME');
  const bodies = postTo('/api/auth/google/signup', () =>
    problemResponse(409, 'USERNAME_UNAVAILABLE'),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Pseudo'), 'Chosen');
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Ce pseudo est déjà pris.',
  );
  expect(screen.getByLabelText('Pseudo')).toHaveValue('Chosen');
  expect(
    screen.queryByText('Détail privé du serveur.'),
  ).not.toBeInTheDocument();
  expect(bodies).toEqual([{ username: 'Chosen', flowId, acceptedTerms: true }]);
});

it('ne propose pas « Rester connecté » sur l’écran du pseudo Google', async () => {
  stage('CHOOSE_USERNAME');
  renderWithProviders(screens, { route: '/connexion-google' });

  expect(await screen.findByLabelText('Pseudo')).toBeVisible();
  expect(
    screen.queryByRole('checkbox', { name: 'Rester connecté' }),
  ).not.toBeInTheDocument();
});

it('ne crée pas le compte Google tant que la case des conditions n’est pas cochée', async () => {
  stage('CHOOSE_USERNAME');
  const bodies = postTo('/api/auth/google/signup', () =>
    sessionResponse('Chosen'),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Pseudo'), 'Chosen');

  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(
    await screen.findByText(
      'Accepte les conditions d’utilisation pour créer ton compte.',
    ),
  ).toBeVisible();
  expect(termsCheckbox()).toHaveFocus();
  expect(termsCheckbox()).toHaveAccessibleDescription(
    'Accepte les conditions d’utilisation pour créer ton compte.',
  );
  await settled();
  expect(bodies).toEqual([]);
});

it('crée le compte après une collision, avec le même pseudo, et rejoint la carte', async () => {
  stage('CHOOSE_USERNAME');
  const bodies = postTo(
    '/api/auth/google/signup',
    () => problemResponse(409, 'USERNAME_UNAVAILABLE'),
    () => sessionResponse('Chosen'),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Pseudo'), 'Chosen');
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Continuer' }));
  await screen.findByRole('alert');
  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  expect(bodies).toEqual([
    { username: 'Chosen', flowId, acceptedTerms: true },
    { username: 'Chosen', flowId, acceptedTerms: true },
  ]);
});

it('refuse un formulaire ancien sans finaliser le nouveau parcours du navigateur', async () => {
  stage('CHOOSE_USERNAME');
  const bodies = postTo('/api/auth/google/signup', () =>
    problemResponse(400, 'INVALID_FLOW'),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Pseudo'), 'Stale');
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Recommence avec Google.',
  );
  expect(
    screen.queryByRole('heading', { name: 'Carte' }),
  ).not.toBeInTheDocument();
  expect(bodies).toEqual([{ username: 'Stale', flowId, acceptedTerms: true }]);
});

it('demande une confirmation distincte après la vérification du mot de passe local', async () => {
  stage('ACCOUNT_ACCESS_REQUIRED');
  const bodies = postTo('/api/auth/google/access', () =>
    sessionResponse('Local'),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await verifyLocalAccount(user);

  expect(
    await screen.findByRole('button', {
      name: 'Confirmer l’association de Google',
    }),
  ).toBeVisible();
  expect(bodies).toEqual([
    { identifier: 'Local', password: 'phrase fictive', flowId },
  ]);
});

it('indique que le compte reste le même après la vérification du mot de passe local', async () => {
  stage('ACCOUNT_ACCESS_REQUIRED');
  postTo('/api/auth/google/access', () => sessionResponse('Local'));
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await verifyLocalAccount(user);

  expect(await screen.findByText(/Ton compte reste le même/)).toBeVisible();
});

it('mène à la carte après une liaison confirmée, sans proposer d’envoi ni de reprise', async () => {
  stage('CONFIRM_LINK');
  const bodies = postTo('/api/auth/google/link', () =>
    HttpResponse.json({ changed: true }),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole('button', {
      name: 'Confirmer l’association de Google',
    }),
  );

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  expect(
    screen.queryByRole('button', { name: 'Renvoyer l’email' }),
  ).not.toBeInTheDocument();
  expect(bodies).toEqual([{ flowId, confirmed: true }]);
});

it('une réponse de liaison perdue laisse confirmer de nouveau, sans reçu', async () => {
  stage('CONFIRM_LINK');
  const bodies = postTo(
    '/api/auth/google/link',
    () => HttpResponse.error(),
    () => HttpResponse.json({ changed: true }),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  const confirm = await screen.findByRole('button', {
    name: 'Confirmer l’association de Google',
  });
  await user.click(confirm);
  expect(await screen.findByRole('alert')).toBeVisible();
  await user.click(confirm);

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  expect(bodies).toEqual([
    { flowId, confirmed: true },
    { flowId, confirmed: true },
  ]);
});

it('une demande de lien refusée parce qu’un email vient d’être envoyé affiche le résultat d’un envoi accepté', async () => {
  stage('EMAIL_CONFIRMATION_REQUIRED');
  const bodies = postTo('/api/auth/google/email', () =>
    problemResponse(429, 'MAIL_RECENTLY_SENT', { 'Retry-After': '45' }),
  );
  renderWithProviders(screens, { route: '/connexion-google' });
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole('button', { name: 'Envoyer le lien' }),
  );

  expect(
    await screen.findByText(
      /Consulte tes emails et les courriers indésirables/,
    ),
  ).toBeVisible();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(
    screen.queryByText('Détail privé du serveur.'),
  ).not.toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Renvoyer le lien' }),
  ).toHaveAttribute('aria-disabled', 'true');
  expect(bodies).toEqual([{ flowId }]);
});

it('retire le fragment Google de l’URL sans envoyer de requête avant l’action', async () => {
  window.history.replaceState(null, '', fragmentUrl);
  onTestFinished(() => window.history.replaceState(null, '', '/'));
  const requests = recordRequests();
  renderWithProviders(screens, { route: '/confirmation-google' });

  await waitFor(() => expect(window.location.hash).toBe(''));
  await settled();
  expect(requests.routes()).toEqual([]);
});

it('confirme l’email sur action explicite, sans ouvrir de session ni aller à la carte', async () => {
  window.history.replaceState(null, '', fragmentUrl);
  onTestFinished(() => window.history.replaceState(null, '', '/'));
  const confirms = postTo(
    '/api/auth/google/email/confirm',
    () => new HttpResponse(null, { status: 204 }),
  );
  renderWithProviders(screens, { route: '/confirmation-google' });
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole('button', { name: 'Confirmer mon email' }),
  );

  expect(
    await screen.findByRole('heading', { name: 'Adresse confirmée' }),
  ).toBeVisible();
  // « Chargement terminé. » of the reading of the link may still be there, before the message.
  expect(screen.getAllByRole('status').at(-1)).toHaveTextContent(
    'Continue avec Google',
  );
  expect(
    screen.queryByRole('heading', { name: 'Carte' }),
  ).not.toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Continuer avec Google' }),
  ).toBeVisible();
  expect(confirms).toHaveLength(1);
});

it.each([
  {
    code: 'RATE_LIMITED',
    message:
      'Trop de tentatives avec Google. Réessaie dans quelques minutes ou utilise ton mot de passe.',
  },
  {
    code: 'UNAVAILABLE',
    message:
      'Le service est momentanément indisponible. Réessaie dans quelques instants ou utilise ton mot de passe.',
  },
])(
  'au retour de Google, annonce $code par un texte neutre',
  async ({ code, message }) => {
    returnedWithError(code);
    renderWithProviders(screens, { route: '/connexion-google' });

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
  },
);
