import { act, screen, waitFor } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  rateLimitedResponse,
  sessionResponse,
  testSession,
} from '@/test/handlers/auth';
import {
  holdAuthenticationLock,
  holdNextRead,
  readsSessionAs,
  reconnectable,
  signedInUntilLogout,
} from '@/test/auth';
import { renderWithProviders } from '@/test/render';
import { postTo, recordRequests } from '@/test/requests';
import { server, settled } from '@/test/server';
import { LoginScreen } from './login-screen';
import { AuthenticatedScreen } from '../../account/components/authenticated-screen';
import { authenticationDeadline } from '../../session/lock';
import { sessionKey } from '../../queries';

afterEach(() => {
  vi.useRealTimers();
});

const screens = (
  <Routes>
    <Route path="/connexion" element={<LoginScreen />} />
    <Route
      path="/"
      element={
        <AuthenticatedScreen title="Carte">
          <h1>Carte</h1>
        </AuthenticatedScreen>
      }
    />
  </Routes>
);

it('affiche l’erreur d’un envoi sans identifiant et n’appelle pas la connexion', async () => {
  readsSessionAs(anonymousResponse);
  const requests = recordRequests();
  renderWithProviders(screens, { route: '/connexion' });
  const user = userEvent.setup();
  // The heading is also in the skeleton: the form is ready when its fields are.
  await screen.findByLabelText('Email ou pseudo');
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  expect(
    await screen.findByText('Indique ton email ou ton pseudo.'),
  ).toBeVisible();
  await settled();
  expect(requests.of('POST', '/api/auth/login')).toHaveLength(0);
});

it('garde l’identifiant et le mot de passe saisis après un refus de la connexion', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/connexion' });
  const user = userEvent.setup();
  await screen.findByLabelText('Email ou pseudo');
  await user.type(screen.getByLabelText('Email ou pseudo'), 'jeune_demo');
  await user.type(screen.getByLabelText('Mot de passe'), 'phrase fictive 🔑');
  server.use(
    http.post('/api/auth/login', () =>
      problemResponse(401, 'INVALID_CREDENTIALS'),
    ),
  );
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  expect(
    await screen.findByText('Identifiant ou mot de passe incorrect.'),
  ).toBeVisible();
  expect(screen.getByLabelText('Email ou pseudo')).toHaveValue('jeune_demo');
  expect(screen.getByLabelText('Mot de passe')).toHaveValue(
    'phrase fictive 🔑',
  );
});

it('ne montre pas le détail technique du serveur après un refus de la connexion', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/connexion' });
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('Email ou pseudo'),
    'jeune_demo',
  );
  await user.type(screen.getByLabelText('Mot de passe'), 'phrase fictive 🔑');
  server.use(
    http.post('/api/auth/login', () =>
      problemResponse(401, 'INVALID_CREDENTIALS'),
    ),
  );
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  await screen.findByText('Identifiant ou mot de passe incorrect.');
  expect(
    screen.queryByText('Détail privé du serveur.'),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole('heading', { name: 'Carte' }),
  ).not.toBeInTheDocument();
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
  'envoie la case « Rester connecté » $label avec la connexion',
  async ({ choose, rememberMe }) => {
    readsSessionAs(anonymousResponse);
    const bodies = postTo('/api/auth/login', () =>
      problemResponse(401, 'INVALID_CREDENTIALS'),
    );
    renderWithProviders(screens, { route: '/connexion' });
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Email ou pseudo'), 'jeune');
    await user.type(screen.getByLabelText('Mot de passe'), 'phrase fictive');
    const box = screen.getByRole('checkbox', { name: 'Rester connecté' });
    expect(box).not.toBeChecked();
    await choose(user, box);

    await user.click(screen.getByRole('button', { name: 'Se connecter' }));

    await screen.findByText('Identifiant ou mot de passe incorrect.');
    expect(bodies).toEqual([
      { identifier: 'jeune', password: 'phrase fictive', rememberMe },
    ]);
  },
);

it('affiche la carte de connexion avec des squelettes au premier chargement, sans texte de vérification', async () => {
  const read = holdNextRead();
  renderWithProviders(screens, { route: '/connexion' });

  expect(await screen.findByRole('status')).toHaveTextContent('Chargement…');
  expect(screen.getAllByRole('status')).toHaveLength(1);
  expect(screen.getByRole('heading', { name: 'Se connecter' })).toBeVisible();
  expect(screen.queryByLabelText('Email ou pseudo')).toBeNull();
  expect(screen.queryByText(/Vérification de la connexion/)).toBeNull();
  expect(screen.queryByText(/Connexion en cours/)).toBeNull();

  await act(async () => {
    read(anonymousResponse());
  });
  expect(await screen.findByLabelText('Email ou pseudo')).toBeVisible();
  expect(screen.getByRole('status')).toHaveTextContent('Chargement terminé.');
});

it('relit la session gardée en cache à l’arrivée sur la connexion et montre le formulaire quand elle n’existe plus', async () => {
  const read = holdNextRead();
  renderWithProviders(screens, {
    route: '/connexion',
    seed: (client) =>
      client.setQueryData(sessionKey, testSession('jeune_demo')),
  });

  // The cache says « logged in », but the page asks again and shows the skeleton meanwhile: no redirect.
  expect(await screen.findByRole('status')).toHaveTextContent('Chargement…');
  expect(screen.queryByRole('heading', { name: 'Carte' })).toBeNull();
  expect(screen.queryByLabelText('Email ou pseudo')).toBeNull();
  await act(async () => {
    read(anonymousResponse());
  });

  expect(await screen.findByLabelText('Email ou pseudo')).toBeVisible();
  expect(screen.queryByRole('heading', { name: 'Carte' })).toBeNull();
});

it('redirige depuis la connexion quand la relecture confirme la session gardée en cache', async () => {
  readsSessionAs(() => sessionResponse('jeune_demo'));
  renderWithProviders(screens, {
    route: '/connexion',
    seed: (client) =>
      client.setQueryData(sessionKey, testSession('jeune_demo')),
  });

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
});

it('garde les boutons de connexion actifs pendant une relecture de la session d’un visiteur anonyme', async () => {
  readsSessionAs(anonymousResponse);
  const { client } = renderWithProviders(screens, { route: '/connexion' });
  await screen.findByLabelText('Email ou pseudo');
  const read = holdNextRead();
  let reading!: Promise<void>;
  await act(async () => {
    reading = client.invalidateQueries({ queryKey: sessionKey });
  });

  expect(client.isFetching({ queryKey: sessionKey })).toBe(1);
  expect(screen.getByRole('button', { name: 'Se connecter' })).toBeEnabled();
  expect(
    screen.getByRole('button', { name: 'Continuer avec Google' }),
  ).toBeEnabled();
  await act(async () => {
    read(anonymousResponse());
    await reading;
  });
});

it('masque la page protégée en cas de panne et permet une reprise', async () => {
  server.use(
    http.get('/api/auth/session', () => problemResponse(503), { once: true }),
  );
  renderWithProviders(screens, { route: '/' });
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Connexion temporairement indisponible',
  );
  expect(
    screen.queryByRole('heading', { name: 'Carte' }),
  ).not.toBeInTheDocument();
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Réessayer' }));
  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
});

it('ne relance pas la reprise limitée en passant à la connexion', async () => {
  reconnectable();
  readsSessionAs(anonymousResponse);
  server.use(http.post('/api/auth/resume', () => rateLimitedResponse(600)));
  const requests = recordRequests();
  renderWithProviders(screens, { route: '/' });
  await userEvent.setup().click(
    await screen.findByRole('link', {
      name: 'Connecte-toi avec ton mot de passe',
    }),
  );

  expect(
    await screen.findByRole('heading', { name: 'Se connecter' }),
  ).toBeVisible();
  await settled();
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
  expect(requests.of('GET', '/api/auth/session').length).toBeGreaterThan(0);
});

it('met le focus sur le titre de la connexion quand la déconnexion renvoie vers elle', async () => {
  signedInUntilLogout('jeune_demo');
  renderWithProviders(screens, { route: '/' });
  await screen.findByRole('heading', { name: 'Carte' });
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Mon compte' }));
  await user.click(screen.getByRole('menuitem', { name: 'Se déconnecter' }));

  await screen.findByLabelText('Email ou pseudo');
  expect(screen.getByRole('heading', { name: 'Se connecter' })).toHaveFocus();
});

it('met le focus sur le titre de la connexion quand la relecture ne trouve plus de session', async () => {
  const { client } = renderWithProviders(screens, { route: '/' });
  await screen.findByRole('heading', { name: 'Carte' });
  readsSessionAs(anonymousResponse);
  await act(() => client.invalidateQueries({ queryKey: sessionKey }));

  await screen.findByLabelText('Email ou pseudo');
  expect(screen.getByRole('heading', { name: 'Se connecter' })).toHaveFocus();
});

it('laisse le focus au navigateur quand un visiteur anonyme est renvoyé vers la connexion', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/' });

  await screen.findByLabelText('Email ou pseudo');
  expect(document.body).toHaveFocus();
});

it('laisse le focus au navigateur quand la connexion est visitée directement', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/connexion' });

  await screen.findByLabelText('Email ou pseudo');
  expect(document.body).toHaveFocus();
});

it('purge le cache de la session quittée et annule les lectures en cours', async () => {
  signedInUntilLogout('jeune_demo');
  const requests = recordRequests();
  const { client } = renderWithProviders(screens, { route: '/' });
  await screen.findByRole('heading', { name: 'Carte' });
  let observedSignal!: AbortSignal;
  const pending = client
    .fetchQuery({
      queryKey: ['private', 'previous-account'],
      queryFn: ({ signal }) => {
        observedSignal = signal;
        return new Promise<string>(() => {});
      },
    })
    .catch(() => undefined);
  await waitFor(() => expect(observedSignal).toBeDefined());
  client.setQueryData(['private', 'saved'], { content: 'ancienne donnée' });
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Mon compte' }));
  await user.click(screen.getByRole('menuitem', { name: 'Se déconnecter' }));
  await waitFor(() =>
    expect(requests.of('POST', '/api/auth/logout')).toHaveLength(1),
  );
  await waitFor(() =>
    expect(client.getQueryData(['private', 'saved'])).toBeUndefined(),
  );
  expect(observedSignal.aborted).toBe(true);
  await pending;
  expect(
    screen.queryByRole('heading', { name: 'Carte' }),
  ).not.toBeInTheDocument();
});

it('un échec de déconnexion conserve le refus visible et ne simule pas un succès', async () => {
  server.use(http.post('/api/auth/logout', () => problemResponse(503)));
  renderWithProviders(screens, { route: '/' });
  await screen.findByRole('heading', { name: 'Carte' });
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Mon compte' }));
  await user.click(screen.getByRole('menuitem', { name: 'Se déconnecter' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Connexion temporairement indisponible',
  );
  expect(screen.getByRole('heading', { name: 'Carte' })).toBeVisible();
});

it('demande de réessayer après un jeton CSRF refusé, en gardant la saisie', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/connexion' });
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('Email ou pseudo'),
    'jeune_demo',
  );
  await user.type(screen.getByLabelText('Mot de passe'), 'phrase fictive 🔑');
  server.use(
    http.post('/api/auth/login', () => problemResponse(403, 'CSRF_INVALID'), {
      once: true,
    }),
  );
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Ta session a changé. Réessaie pour continuer.',
  );
  expect(screen.getByLabelText('Email ou pseudo')).toHaveValue('jeune_demo');
  expect(screen.getByLabelText('Mot de passe')).toHaveValue(
    'phrase fictive 🔑',
  );
});

it('garde la saisie après une perte de réseau à la connexion et laisse réessayer', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/connexion' });
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('Email ou pseudo'),
    'jeune_demo',
  );
  await user.type(screen.getByLabelText('Mot de passe'), 'phrase fictive 🔑');
  server.use(
    http.post('/api/auth/login', () => HttpResponse.error(), { once: true }),
  );
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Connexion impossible pour le moment. Vérifie ta connexion puis réessaie.',
  );
  expect(screen.getByLabelText('Email ou pseudo')).toHaveValue('jeune_demo');
  expect(screen.getByLabelText('Mot de passe')).toHaveValue(
    'phrase fictive 🔑',
  );
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
});

async function submitWhileAnotherTabHoldsTheLock() {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/connexion' });
  await screen.findByLabelText('Email ou pseudo');
  const typing = userEvent.setup();
  await typing.type(screen.getByLabelText('Email ou pseudo'), 'jeune_demo');
  await typing.type(screen.getByLabelText('Mot de passe'), 'phrase fictive 🔑');
  // The deadline is a timer; the clock keeps running for the screen and `user-event`, which wait on their own.
  vi.useFakeTimers({
    shouldAdvanceTime: true,
    toFake: ['setTimeout', 'clearTimeout'],
  });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const release = holdAuthenticationLock();
  const requests = recordRequests();
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));
  await act(() => vi.advanceTimersByTimeAsync(authenticationDeadline));
  return { user, release, requests };
}

it('annonce le délai dépassé de la connexion et garde la saisie', async () => {
  const { requests } = await submitWhileAnotherTabHoldsTheLock();

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Cette action a pris trop de temps. Vérifie ta connexion puis réessaie.',
  );
  expect(screen.getByLabelText('Email ou pseudo')).toHaveValue('jeune_demo');
  expect(screen.getByLabelText('Mot de passe')).toHaveValue(
    'phrase fictive 🔑',
  );
  await settled();
  expect(requests.of('POST', '/api/auth/login')).toHaveLength(0);
});

it('renvoie la connexion avec le même bouton une fois le verrou libre après un délai dépassé', async () => {
  const { user, release, requests } = await submitWhileAnotherTabHoldsTheLock();
  await screen.findByRole('alert');
  const submit = screen.getByRole('button', { name: 'Se connecter' });
  expect(submit).toBeEnabled();

  release();
  await user.click(submit);

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  expect(requests.of('POST', '/api/auth/login')).toHaveLength(1);
});

it('mène au formulaire de connexion quand la reprise est limitée alors que la session était connue', async () => {
  reconnectable();
  const requests = recordRequests();
  const { client } = renderWithProviders(screens, { route: '/' });
  await screen.findByRole('heading', { name: 'Carte' });
  readsSessionAs(anonymousResponse);
  server.use(
    http.post('/api/auth/resume', () => rateLimitedResponse(600), {
      once: true,
    }),
  );
  await act(() => client.invalidateQueries({ queryKey: sessionKey }));
  await userEvent.setup().click(
    await screen.findByRole('link', {
      name: 'Connecte-toi avec ton mot de passe',
    }),
  );

  expect(
    await screen.findByRole('button', { name: 'Se connecter' }),
  ).toBeEnabled();
  expect(screen.queryByRole('heading', { name: 'Carte' })).toBeNull();
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
});
