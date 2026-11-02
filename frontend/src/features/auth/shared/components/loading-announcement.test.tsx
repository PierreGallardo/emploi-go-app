import { act, screen } from '@testing-library/react';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { endOfLoadingDuration } from '@/components/loading-status';
import { anonymousResponse, sessionResponse } from '@/test/handlers/auth';
import {
  holdNextRead,
  readsSessionAs,
  recoveryLink,
  signedInAs,
} from '@/test/auth';
import { authScreens, codeScreenRoute } from '@/test/auth-screens';
import { sessionKey } from '../../queries';
import { AuthenticatedScreen } from '../../account/components/authenticated-screen';
import { renderWithProviders } from '@/test/render';
import { holdResponse } from '@/test/requests';
import { server } from '@/test/server';

// The clock keeps running for the screen, which waits on the server; the 5 s are crossed with a wide margin.
beforeEach(() => {
  vi.useFakeTimers({
    shouldAdvanceTime: true,
    toFake: ['setTimeout', 'clearTimeout'],
  });
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
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

it.each([
  { screen: 'la connexion', route: '/connexion', field: 'Email ou pseudo' },
  { screen: 'l’inscription', route: '/inscription', field: 'Email' },
])(
  'annonce la fin du premier chargement de $screen dans le statut du chargement, sans déplacer le focus',
  async ({ route, field }) => {
    const read = holdNextRead();
    renderWithProviders(authScreens, { route });
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Chargement…');

    await act(async () => read(anonymousResponse()));
    await screen.findByLabelText(field);

    expect(loadingStatus()).toBe(status);
    expect(status).toHaveTextContent('Chargement terminé.');
    expect(document.body).toHaveFocus();
    await act(() => vi.advanceTimersByTimeAsync(endOfLoadingDuration + 1_000));
    expect(status).toHaveTextContent(/^$/);
  },
);

// The loading status comes first: a password rule is an empty status too, after it in a form.
const loadingStatus = () => screen.getAllByRole('status').at(0);

it('annonce la fin du premier chargement de la saisie du code dans le statut du chargement, sans déplacer le focus', async () => {
  const read = holdNextRead();
  renderWithProviders(authScreens, { route: codeScreenRoute() });
  const status = await screen.findByRole('status');
  expect(status).toHaveTextContent('Chargement…');

  await act(async () => read(anonymousResponse()));
  await screen.findByRole('textbox', {
    name: 'Code à 6 chiffres reçu par email',
  });

  expect(screen.getAllByRole('status')[0]).toBe(status);
  expect(status).toHaveTextContent('Chargement terminé.');
  expect(document.body).toHaveFocus();
  await act(() => vi.advanceTimersByTimeAsync(endOfLoadingDuration + 1_000));
  expect(status).toHaveTextContent(/^$/);
});

it('annonce la fin du premier chargement de la page protégée, sans déplacer le focus', async () => {
  const read = holdNextRead();
  renderWithProviders(authScreens, { route: '/' });
  const status = await screen.findByRole('status');
  expect(status).toHaveTextContent('Chargement…');

  await act(async () => read(sessionResponse()));
  await screen.findByRole('heading', { name: 'Carte' });

  expect(loadingStatus()).toBe(status);
  expect(status).toHaveTextContent('Chargement terminé.');
  expect(document.body).toHaveFocus();
  await act(() => vi.advanceTimersByTimeAsync(endOfLoadingDuration + 1_000));
  expect(status).toHaveTextContent(/^$/);
});

it.each([
  { screen: 'la connexion', route: '/connexion' },
  { screen: 'l’inscription', route: '/inscription' },
  { screen: 'la saisie du code', route: codeScreenRoute() },
  { screen: 'la page protégée', route: '/' },
])(
  'ne dit pas que le chargement est terminé quand la lecture de la session de $screen échoue',
  async ({ route }) => {
    readsSessionAs(() => HttpResponse.error());
    renderWithProviders(authScreens, { route });

    expect(await screen.findByRole('alert')).toBeVisible();
    await act(async () => {});
    expect(screen.queryByText('Chargement terminé.')).not.toBeInTheDocument();
  },
);

it('laisse le titre focalisé annoncer l’écran, sans « Chargement terminé. », quand la connexion remplace un écran dont un lien avait le focus', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(authScreens, {
    route: '/mot-de-passe-oublie',
    seed: signedInAs(),
  });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const read = holdNextRead();
  await user.click(
    await screen.findByRole('link', { name: 'Revenir à la connexion' }),
  );
  expect(await screen.findByRole('status')).toHaveTextContent('Chargement…');

  await act(async () => read(anonymousResponse()));

  expect(
    await screen.findByRole('heading', { name: 'Se connecter' }),
  ).toHaveFocus();
  expect(screen.queryByText('Chargement terminé.')).not.toBeInTheDocument();
});

const linkScreens = [
  { screen: 'le nouveau mot de passe', route: '/reinitialiser-mot-de-passe' },
  {
    screen: 'la confirmation de l’email Google',
    route: '/confirmation-google',
  },
];

it.each(linkScreens)(
  'annonce la fin de la lecture du lien de $screen',
  async ({ route }) => {
    window.history.replaceState(null, '', `${route}#${recoveryLink}`);
    renderWithProviders(authScreens, { route });

    await screen.findByText('Chargement terminé.');
    const status = loadingStatus();

    expect(document.body).toHaveFocus();
    await act(() => vi.advanceTimersByTimeAsync(endOfLoadingDuration + 1_000));
    expect(status).toHaveTextContent(/^$/);
  },
);

it.each(linkScreens)(
  'laisse l’alerte du lien invalide de $screen parler seule',
  async ({ route }) => {
    renderWithProviders(authScreens, { route });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Ce lien est invalide.',
    );
    await act(async () => {});
    expect(screen.queryByText('Chargement terminé.')).not.toBeInTheDocument();
  },
);

it('annonce la fin de la vérification de Google, sans déplacer le focus', async () => {
  const held = holdResponse();
  server.use(http.get('/api/auth/google/status', held.resolver));
  renderWithProviders(authScreens, { route: '/connexion-google' });
  const status = await screen.findByRole('status');
  expect(status).toHaveTextContent('Chargement…');
  expect(screen.getByText('Vérification de Google…')).toBeVisible();

  await act(async () =>
    held.release(
      HttpResponse.json({
        pending: true,
        flowId: 'f496c1d5-03e2-4a40-a887-115580f9113e',
        stage: 'CHOOSE_USERNAME',
        errorCode: null,
      }),
    ),
  );
  await screen.findByLabelText('Pseudo');

  expect(loadingStatus()).toBe(status);
  expect(status).toHaveTextContent('Chargement terminé.');
  expect(document.body).toHaveFocus();
});

it('laisse l’alerte de Google parler seule quand la vérification échoue', async () => {
  server.use(http.get('/api/auth/google/status', () => HttpResponse.error()));
  renderWithProviders(authScreens, { route: '/connexion-google' });

  expect(await screen.findByRole('alert')).toBeVisible();
  await act(async () => {});
  expect(screen.queryByText('Chargement terminé.')).not.toBeInTheDocument();
});

function LeavingButton() {
  const [present, setPresent] = useState(true);
  return present ? (
    <button onClick={() => setPresent(false)}>Quitter</button>
  ) : null;
}

it('annonce la fin du chargement de la page protégée quand elle prend le focus sur <main>, qui n’a pas de nom', async () => {
  const read = holdNextRead();
  renderWithProviders(
    <>
      <LeavingButton />
      <AuthenticatedScreen title="Carte">
        <p>Contenu</p>
      </AuthenticatedScreen>
    </>,
  );
  await userEvent
    .setup({ advanceTimers: vi.advanceTimersByTime })
    .click(await screen.findByRole('button', { name: 'Quitter' }));
  expect(document.body).toHaveFocus();

  await act(async () => read(sessionResponse()));

  expect(await screen.findByText('Contenu')).toBeVisible();
  expect(screen.getByRole('main')).toHaveFocus();
  expect(loadingStatus()).toHaveTextContent('Chargement terminé.');
});

it('ne dit rien quand une relecture confirme le même compte, une fois le message du premier chargement retiré', async () => {
  const read = holdNextRead();
  const { client } = renderWithProviders(authScreens, { route: '/' });
  await act(async () => read(sessionResponse()));
  await screen.findByRole('heading', { name: 'Carte' });
  await act(() => vi.advanceTimersByTimeAsync(endOfLoadingDuration + 1_000));
  expect(loadingStatus()).toHaveTextContent(/^$/);

  const again = holdNextRead();
  await act(async () => {
    void client.invalidateQueries({ queryKey: sessionKey });
  });
  await act(async () => again(sessionResponse()));
  await act(async () => {});

  expect(loadingStatus()).toHaveTextContent(/^$/);
});
