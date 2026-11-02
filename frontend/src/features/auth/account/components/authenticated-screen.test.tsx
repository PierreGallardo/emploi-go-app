import { useQuery } from '@tanstack/react-query';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
  resumesAs,
  signedInUntilLogout,
} from '@/test/auth';
import {
  MapResource,
  anotherTabChangesTheSession,
  fakeTimers,
  resetAccountScreen,
  returnToTab,
  revalidate,
  sessionExpiresAfterFirstRead,
  show,
  whileReading,
} from '@/test/account-screen';
import { renderWithProviders } from '@/test/render';
import { recordRequests, stallRequests } from '@/test/requests';
import { server, settled } from '@/test/server';
import { api } from '@/api/client';
import { AuthenticatedScreen } from './authenticated-screen';
import { authenticationDeadline } from '../../session/lock';
import { sessionKey, sessionStaleTime } from '../../queries';

afterEach(resetAccountScreen);

it('garde la carte, son instance et son focus pendant une relecture qui confirme le même compte', async () => {
  const mounted = vi.fn(),
    destroyed = vi.fn();
  const { client } = show(
    <MapResource mounted={mounted} destroyed={destroyed} />,
  );
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  original.focus();
  await whileReading(client, () => {
    expect(screen.getByRole('button', { name: 'Ressource carte' })).toBe(
      original,
    );
    expect(original).toBeVisible();
    expect(original).toHaveFocus();
    expect(screen.queryByText('Chargement…')).not.toBeInTheDocument();
    expect(screen.queryByText(/Vérification de la connexion/)).toBeNull();
  });
  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBe(
    original,
  );
  expect(original).toHaveFocus();
  expect(mounted).toHaveBeenCalledOnce();
  expect(destroyed).not.toHaveBeenCalled();
});

it('laisse le menu du compte ouvert pendant une relecture qui confirme le même compte', async () => {
  const { client } = show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Mon compte' }));
  expect(screen.getByText('jeune_fictif')).toBeVisible();
  await whileReading(client, () => {
    expect(screen.getByText('jeune_fictif')).toBeVisible();
    expect(
      screen.getByRole('menuitem', { name: 'Se déconnecter' }),
    ).toBeVisible();
  });
  expect(
    screen.getByRole('menuitem', { name: 'Se déconnecter' }),
  ).toBeVisible();
});

it('redirige vers la connexion quand la relecture ne trouve plus de session', async () => {
  const destroyed = vi.fn();
  const { client } = show(<MapResource destroyed={destroyed} />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  readsSessionAs(anonymousResponse);
  await revalidate(client);
  await screen.findByRole('heading', { name: 'Connexion normale' });
  expect(
    screen.queryByRole('button', { name: 'Ressource carte' }),
  ).not.toBeInTheDocument();
  expect(destroyed).toHaveBeenCalledOnce();
});

it('démonte les ressources, annule les lectures et ferme le menu de l’ancien compte quand le principal change', async () => {
  const mounted = vi.fn(),
    destroyed = vi.fn();
  const signals: AbortSignal[] = [];
  function PendingResource() {
    useQuery({
      queryKey: ['map', 'pending'],
      queryFn: ({ signal }) => {
        signals.push(signal);
        return new Promise(() => {});
      },
      gcTime: 0,
    });
    return <MapResource mounted={mounted} destroyed={destroyed} />;
  }
  const { client } = show(<PendingResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Mon compte' }));
  expect(screen.getByText('jeune_fictif')).toBeVisible();
  readsSessionAs(() => sessionResponse('autre-jeune_fictif'));
  await revalidate(client);
  await waitFor(() => expect(destroyed).toHaveBeenCalledOnce());
  expect(mounted).toHaveBeenCalledTimes(2);
  expect(signals[0]!.aborted).toBe(true);
  expect(screen.queryByText('jeune_fictif')).not.toBeInTheDocument();
  expect(screen.queryByText('autre-jeune_fictif')).not.toBeInTheDocument();
  expect(screen.queryByRole('menuitem')).not.toBeInTheDocument();
});

it('redirige vers la connexion quand un autre onglet se déconnecte, sans autre action de l’utilisateur', async () => {
  const destroyed = vi.fn();
  show(<MapResource destroyed={destroyed} />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  readsSessionAs(anonymousResponse);

  await anotherTabChangesTheSession();

  await screen.findByRole('heading', { name: 'Connexion normale' });
  expect(destroyed).toHaveBeenCalledOnce();
});

it('démonte les ressources, annule les lectures et ferme le menu quand un autre onglet connecte un autre compte', async () => {
  const mounted = vi.fn(),
    destroyed = vi.fn();
  const signals: AbortSignal[] = [];
  function PendingResource() {
    useQuery({
      queryKey: ['map', 'pending'],
      queryFn: ({ signal }) => {
        signals.push(signal);
        return new Promise(() => {});
      },
      gcTime: 0,
    });
    return <MapResource mounted={mounted} destroyed={destroyed} />;
  }
  show(<PendingResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Mon compte' }));
  expect(screen.getByText('jeune_fictif')).toBeVisible();
  readsSessionAs(() => sessionResponse('autre-jeune_fictif'));

  await anotherTabChangesTheSession();

  await waitFor(() => expect(destroyed).toHaveBeenCalledOnce());
  expect(mounted).toHaveBeenCalledTimes(2);
  expect(signals[0]!.aborted).toBe(true);
  expect(screen.queryByText('jeune_fictif')).not.toBeInTheDocument();
  expect(screen.queryByRole('menuitem')).not.toBeInTheDocument();
});

it('garde la carte et son focus quand un autre onglet annonce un changement qui laisse le même compte', async () => {
  const mounted = vi.fn(),
    destroyed = vi.fn();
  show(<MapResource mounted={mounted} destroyed={destroyed} />);
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  original.focus();
  const requests = recordRequests();

  await anotherTabChangesTheSession();

  // The read waits for the lock before it is sent, so wait for the request itself, then for its answer.
  await vi.waitFor(() =>
    expect(requests.routes()).toContain('GET /api/auth/session'),
  );
  await act(() => settled());

  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBe(
    original,
  );
  expect(original).toHaveFocus();
  expect(mounted).toHaveBeenCalledOnce();
  expect(destroyed).not.toHaveBeenCalled();
});

it('ne relit pas la session au retour du focus avant la fin de son délai de fraîcheur', async () => {
  // Only the clock moves: the freshness delay is a date comparison.
  vi.useFakeTimers({ toFake: ['Date'] });
  const requests = recordRequests();
  show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(1);

  await returnToTab();
  await returnToTab();
  await settled();
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(1);

  vi.setSystemTime(Date.now() + sessionStaleTime);
  await returnToTab();
  await waitFor(() =>
    expect(requests.of('GET', '/api/auth/session')).toHaveLength(2),
  );
  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBeVisible();
});

it('garde la carte et signale une panne passagère de la relecture sans masquer le contenu', async () => {
  const destroyed = vi.fn();
  const { client } = show(<MapResource destroyed={destroyed} />);
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  readsSessionAs(() => HttpResponse.error());
  await revalidate(client);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Connexion impossible',
  );
  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBe(
    original,
  );
  expect(original).toBeVisible();
  expect(
    screen.queryByRole('heading', { name: 'Connexion indisponible' }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole('link', { name: /Connecte-toi/ }),
  ).not.toBeInTheDocument();
  expect(destroyed).not.toHaveBeenCalled();

  readsSessionAs(() => sessionResponse());
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Réessayer' }));
  await waitFor(() =>
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
  );
  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBe(
    original,
  );
});

it('garde la carte et propose « Réessayer » quand la relecture dépasse son délai sous le verrou', async () => {
  vi.useFakeTimers(fakeTimers);
  const { client } = show(<MapResource />);
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  const release = holdAuthenticationLock();
  const requests = recordRequests();
  await act(async () => {
    void client.invalidateQueries({ queryKey: sessionKey });
  });

  await act(() => vi.advanceTimersByTimeAsync(authenticationDeadline));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Cette action a pris trop de temps. Vérifie ta connexion puis réessaie.',
  );
  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBe(
    original,
  );
  expect(
    screen.queryByRole('link', { name: /Connecte-toi/ }),
  ).not.toBeInTheDocument();
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(0);
  release();
  await userEvent
    .setup({ advanceTimers: vi.advanceTimersByTime })
    .click(screen.getByRole('button', { name: 'Réessayer' }));
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: 'Réessayer' }),
    ).not.toBeInTheDocument(),
  );
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(1);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBe(
    original,
  );
});

it('relit la session au retour du focus après une reprise abandonnée par son délai, et envoie de nouveau la reprise', async () => {
  vi.useFakeTimers(fakeTimers);
  const { client } = show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  reconnectable();
  readsSessionAs(anonymousResponse);
  // MSW cannot stall a request and then see it abandoned: see `stallRequests`.
  const stalled = stallRequests(api, 'POST', '/api/auth/resume', { times: 1 });
  const requests = recordRequests();
  await act(async () => {
    void client.invalidateQueries({ queryKey: sessionKey });
  });
  await vi.waitFor(() => expect(stalled.started()).toBe(1));

  await act(() => vi.advanceTimersByTimeAsync(authenticationDeadline));

  expect(
    await screen.findByRole('heading', { name: 'Connexion indisponible' }),
  ).toBeVisible();
  expect(screen.getByRole('alert')).toHaveTextContent('pris trop de temps');
  // Nothing blocks the next read from sending the reconnection again, with the secret the cookie still holds.
  // If the API had already rotated it, the API serves this replay a new secret within its tolerance.
  resumesAs(() => sessionResponse());
  await returnToTab();
  await screen.findByRole('button', { name: 'Ressource carte' });
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
});

it('remplace l’application par une erreur quand la première vérification échoue', async () => {
  readsSessionAs(() => HttpResponse.error());
  show(<MapResource />);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Connexion impossible',
  );
  expect(
    screen.getByRole('heading', { name: 'Connexion indisponible' }),
  ).toHaveFocus();
  expect(
    screen.queryByRole('button', { name: 'Ressource carte' }),
  ).not.toBeInTheDocument();
});

it('oublie les données de l’ancien compte quand le retour du focus découvre un autre compte', async () => {
  vi.useFakeTimers(fakeTimers);
  const { client } = show(<MapResource />);
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  const formerData = ['map', 'jeune_fictif', 'establishments'];
  client.setQueryData(formerData, ['établissement fictif']);
  readsSessionAs(() => sessionResponse('autre-jeune_fictif'));
  await act(() => vi.advanceTimersByTimeAsync(sessionStaleTime + 1_000));

  await returnToTab();

  await waitFor(() => expect(original).not.toBeInTheDocument());
  expect(client.getQueryData(formerData)).toBeUndefined();
  expect(client.getQueryData(sessionKey)).toEqual(
    testSession('autre-jeune_fictif'),
  );
});

it('remonte le contenu du nouveau compte, sans rien garder de l’ancien, quand la reconnexion d’une lecture protégée le découvre', async () => {
  reconnectable();
  readsSessionAs(() => sessionResponse('autre-jeune_fictif'));
  server.use(
    http.get('/api/auth/session', () => sessionResponse('jeune_fictif'), {
      once: true,
    }),
  );
  server.use(
    http.get(
      '/api/establishments',
      () => new HttpResponse(null, { status: 401 }),
    ),
  );
  function EstablishmentsOfScope({ scope }: { scope: string }) {
    useQuery({
      queryKey: ['map', scope, 'establishments'],
      queryFn: ({ signal }) => api.GET('/api/establishments', { signal }),
      retry: false,
      gcTime: 0,
    });
    return <p>Compte {scope}</p>;
  }
  const { client } = show((scope) => <EstablishmentsOfScope scope={scope} />);

  expect(await screen.findByText('Compte autre-jeune_fictif')).toBeVisible();
  expect(screen.queryByText('Compte jeune_fictif')).not.toBeInTheDocument();
  expect(
    client
      .getQueryCache()
      .find({ queryKey: ['map', 'jeune_fictif', 'establishments'] }),
  ).toBeUndefined();
});

it('ne garde pas l’erreur d’une déconnexion échouée quand le compte change', async () => {
  server.use(http.post('/api/auth/logout', () => problemResponse(500)));
  const { client } = show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Mon compte' }));
  await user.click(screen.getByRole('menuitem', { name: 'Se déconnecter' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Impossible de terminer cette action',
  );

  readsSessionAs(() => sessionResponse('autre-jeune_fictif'));
  await revalidate(client);

  await waitFor(() =>
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
  );
  expect(screen.getByRole('button', { name: 'Ressource carte' })).toBeVisible();
});

it('démonte la carte après une déconnexion confirmée', async () => {
  signedInUntilLogout();
  const destroyed = vi.fn();
  const { client } = show(<MapResource destroyed={destroyed} />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Mon compte' }));
  await user.click(screen.getByRole('menuitem', { name: 'Se déconnecter' }));
  await screen.findByRole('heading', { name: 'Connexion normale' });
  await waitFor(() => expect(destroyed).toHaveBeenCalledOnce());
  expect(client.getQueryData(sessionKey)).toBeNull();
});

it('envoie au choix du pseudo un compte qui n’en a pas encore', async () => {
  readsSessionAs(() => sessionResponse(null));
  renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <AuthenticatedScreen title="Carte">
            <h1>Carte</h1>
          </AuthenticatedScreen>
        }
      />
      <Route path="/inscription/pseudo" element={<h1>Choix du pseudo</h1>} />
    </Routes>,
    { route: '/' },
  );

  expect(
    await screen.findByRole('heading', { name: 'Choix du pseudo' }),
  ).toBeVisible();
  expect(screen.queryByRole('heading', { name: 'Carte' })).toBeNull();
});

/** A protected read of the establishments, answered by the scope of the account it runs for. */
function ProtectedRead({ scope }: { scope: string }) {
  const read = useQuery({
    queryKey: ['protected', scope],
    queryFn: async ({ signal }) =>
      (await api.GET('/api/establishments', { signal })).response.status,
    retry: false,
  });
  return <p>Lecture : {read.data ?? 'en cours'}</p>;
}

it('rejoue une lecture protégée qui reçoit un 401 quand la reprise rétablit le même compte', async () => {
  server.use(
    http.get(
      '/api/establishments',
      () => new HttpResponse(null, { status: 200 }),
    ),
  );
  server.use(
    http.get(
      '/api/establishments',
      () => new HttpResponse(null, { status: 401 }),
      {
        once: true,
      },
    ),
  );
  reconnectable();
  sessionExpiresAfterFirstRead();
  const requests = recordRequests();

  show((scope) => <ProtectedRead scope={scope} />);

  expect(await screen.findByText('Lecture : 200')).toBeVisible();
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
});

it('redirige vers la connexion quand une lecture protégée reçoit un 401 et que la reprise est refusée', async () => {
  server.use(
    http.get(
      '/api/establishments',
      () => new HttpResponse(null, { status: 401 }),
    ),
  );
  reconnectable();
  sessionExpiresAfterFirstRead();
  resumesAs(anonymousResponse);
  const requests = recordRequests();

  show((scope) => <ProtectedRead scope={scope} />);

  expect(
    await screen.findByRole('heading', { name: 'Connexion normale' }),
  ).toBeVisible();
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
  expect(requests.of('GET', '/api/establishments')).toHaveLength(1);
});

it('propose la connexion par mot de passe, sans redemander de reprise, quand une lecture protégée reçoit un 401 pendant une limite', async () => {
  server.use(
    http.get(
      '/api/establishments',
      () => new HttpResponse(null, { status: 401 }),
    ),
  );
  reconnectable();
  sessionExpiresAfterFirstRead();
  resumesAs(() => rateLimitedResponse(30));
  const requests = recordRequests();

  show((scope) => <ProtectedRead scope={scope} />);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Trop de tentatives',
  );
  expect(
    screen.getByRole('link', { name: 'Connecte-toi avec ton mot de passe' }),
  ).toBeVisible();
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
});

it('demande le contenu pendant la vérification de session quand le navigateur peut se reconnecter', async () => {
  reconnectable();
  const release = holdNextRead();
  const prefetch = vi.fn();
  show(<p>Contenu</p>, prefetch);

  await vi.waitFor(() => expect(prefetch).toHaveBeenCalledOnce());
  expect(screen.queryByText('Contenu')).not.toBeInTheDocument();
  release(sessionResponse());
  expect(await screen.findByText('Contenu')).toBeVisible();
});

it('ne demande rien pour un visiteur anonyme, conduit à la connexion', async () => {
  readsSessionAs(anonymousResponse);
  const prefetch = vi.fn();
  show(<p>Contenu</p>, prefetch);

  expect(
    await screen.findByRole('heading', { name: 'Connexion normale' }),
  ).toBeVisible();
  expect(prefetch).not.toHaveBeenCalled();
});
