import {
  MutationObserver,
  QueryClient,
  QueryObserver,
  focusManager,
} from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import { StrictMode } from 'react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, it, onTestFinished, vi } from 'vitest';
import { watchAccountChange, useAccountChange } from './account-change';
import type { Session } from '../api';
import { sessionKey, sessionQueryOptions } from '../queries';
import { reconnectSession } from './reauthentication';
import { createSessionSynchronizer } from './session-sync';
import { anonymousResponse, testSession } from '@/test/handlers/auth';
import { recordRequests } from '@/test/requests';
import { server } from '@/test/server';

const sessionPath = '/api/auth/session';

beforeEach(() => {
  // Only the clock is faked: a session confirmed a minute ago must count as stale.
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  vi.useRealTimers();
  focusManager.setFocused(undefined);
});

// Same shape as the points of the map (`pointsQueryOptions` in `features/map/queries.ts`): the account change must drop it.
const mapKey = ['map', 'jeune_fictif', 'points', { sirets: null }] as const;
const formerAccount = testSession('jeune_fictif');
const otherAccount = testSession('autre-jeune');

function signedIn(session: Session = formerAccount) {
  const client = new QueryClient();
  // What `QueryClientProvider` does: the client follows the focus and the network.
  client.mount();
  onTestFinished(() => client.unmount());
  client.setQueryData(sessionKey, session);
  client.setQueryData(mapKey, ['établissement fictif']);
  onTestFinished(watchAccountChange(client));
  const screen = new QueryObserver(client, sessionQueryOptions());
  onTestFinished(screen.subscribe(() => undefined));
  return { client, screen };
}
type Screen = ReturnType<typeof signedIn>['screen'];
/** The next read of the session answers `session`, as the API does: `null` is a visitor. */
function willRead(session: Session | null) {
  server.use(
    http.get(
      sessionPath,
      () => (session ? HttpResponse.json(session) : anonymousResponse()),
      { once: true },
    ),
  );
}
/** Moves the clock past the 60 s during which a confirmed session is not read again. */
const aMinuteLater = () => vi.setSystemTime(Date.now() + 61_000);
const queryKeys = (client: QueryClient) =>
  client
    .getQueryCache()
    .getAll()
    .map((query) => query.queryKey);
function readingMap(client: QueryClient) {
  let answer!: (value: string[]) => void;
  let signal!: AbortSignal;
  const reading = client
    .fetchQuery({
      queryKey: mapKey,
      queryFn: (context) => {
        signal = context.signal;
        return new Promise<string[]>((resolve) => (answer = resolve));
      },
      staleTime: 0,
    })
    .then(
      () => undefined,
      () => undefined,
    );
  return {
    reading,
    answer: (value: string[]) => answer(value),
    aborted: () => signal.aborted,
  };
}
function sendingForm(client: QueryClient) {
  void new MutationObserver(client, {
    mutationKey: ['auth', 'resend'],
    mutationFn: () => new Promise<void>(() => {}),
  }).mutate();
}
const sessionIs = (client: QueryClient, session: Session | null) =>
  vi.waitFor(() => expect(client.getQueryData(sessionKey)).toEqual(session));

const readsOfTheSession = [
  {
    cas: 'le retour du focus',
    read: () => {
      aMinuteLater();
      focusManager.setFocused(false);
      focusManager.setFocused(true);
    },
  },
  {
    cas: '« Réessayer »',
    read: (_client: QueryClient, screen: Screen) => {
      void screen.refetch();
    },
  },
  {
    cas: 'un écran qui se remonte après 60 s',
    read: (client: QueryClient) => {
      aMinuteLater();
      onTestFinished(
        new QueryObserver(client, sessionQueryOptions()).subscribe(
          () => undefined,
        ),
      );
    },
  },
  {
    cas: 'la reconnexion d’une lecture protégée',
    read: (client: QueryClient) => {
      void reconnectSession(client);
    },
  },
  {
    cas: 'le signal d’un autre onglet',
    read: (client: QueryClient) => {
      void createSessionSynchronizer(client)();
    },
  },
  {
    cas: 'la relecture après une écriture qui a dépassé son délai',
    read: (client: QueryClient) => {
      void client.invalidateQueries({ queryKey: sessionKey });
    },
  },
] as const;

it.each(readsOfTheSession)(
  'oublie l’ancien compte quand $cas découvre un autre compte : lecture annulée, requêtes retirées, formulaire conservé',
  async ({ read }) => {
    const { client, screen } = signedIn();
    const map = readingMap(client);
    sendingForm(client);
    willRead(otherAccount);

    read(client, screen);

    await sessionIs(client, otherAccount);
    expect(queryKeys(client)).toEqual([sessionKey]);
    expect(map.aborted()).toBe(true);
    map.answer(['établissement de l’ancien compte']);
    await map.reading;
    expect(queryKeys(client)).toEqual([sessionKey]);
    expect(client.isMutating({ mutationKey: ['auth'] })).toBe(1);
  },
);

it('oublie un compte sans pseudo quand un autre compte sans pseudo le suit', async () => {
  const { client, screen } = signedIn(testSession(null, 'compte-sans-pseudo'));
  const map = readingMap(client);
  const next = testSession(null, 'autre-compte-sans-pseudo');
  willRead(next);

  void screen.refetch();

  await sessionIs(client, next);
  expect(queryKeys(client)).toEqual([sessionKey]);
  expect(map.aborted()).toBe(true);
});

it.each(readsOfTheSession)(
  'oublie l’ancien compte quand $cas apprend que la session a pris fin',
  async ({ read }) => {
    const { client, screen } = signedIn();
    const map = readingMap(client);
    willRead(null);

    read(client, screen);

    await sessionIs(client, null);
    expect(queryKeys(client)).toEqual([sessionKey]);
    expect(map.aborted()).toBe(true);
  },
);

it.each(readsOfTheSession)(
  'ne touche à rien quand $cas retrouve le même compte',
  async ({ read }) => {
    const { client, screen } = signedIn();
    const map = readingMap(client);
    const requests = recordRequests();
    willRead(formerAccount);

    read(client, screen);

    await vi.waitFor(() =>
      expect(requests.of('GET', sessionPath)).toHaveLength(1),
    );
    // The answer of the session is handled before the map answers: a removal made on that answer would show.
    await vi.waitFor(() =>
      expect(client.isFetching({ queryKey: sessionKey })).toBe(0),
    );
    expect(map.aborted()).toBe(false);
    map.answer(['établissement fictif, plus récent']);
    await map.reading;
    expect(client.getQueryData(mapKey)).toEqual([
      'établissement fictif, plus récent',
    ]);
  },
);

it('ne touche à rien quand la lecture de la session échoue : l’écran affiche son bandeau', async () => {
  const { client, screen } = signedIn();
  const map = readingMap(client);
  server.use(http.get(sessionPath, () => HttpResponse.error(), { once: true }));

  await screen.refetch();

  expect(client.getQueryData(sessionKey)).toEqual(formerAccount);
  map.answer(['établissement fictif, plus récent']);
  await map.reading;
  expect(client.getQueryData(mapKey)).toEqual([
    'établissement fictif, plus récent',
  ]);
});

it('ne touche pas à la lecture de la session qui répond : la session garde la réponse qui a fait oublier', async () => {
  const { client, screen } = signedIn();
  willRead(otherAccount);

  await screen.refetch();

  expect(client.getQueryState(sessionKey)).toMatchObject({
    status: 'success',
    fetchStatus: 'idle',
    data: otherAccount,
  });
});

it('oublie aussi quand la requête de la session a quitté le cache faute d’écran qui la lise', async () => {
  const { client } = signedIn();
  // No screen reads the session any more (`gcTime: 0`), while the data of the account stays.
  client.removeQueries({ queryKey: sessionKey });
  expect(queryKeys(client)).toEqual([mapKey]);
  willRead(otherAccount);

  await client.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });

  expect(queryKeys(client)).toEqual([sessionKey]);
});

it('oublie aussi quand une session terminée est remplacée par un compte : ce qui a été lu depuis ne reste pas', async () => {
  const { client, screen } = signedIn();
  willRead(null);
  await screen.refetch();
  const map = readingMap(client);
  willRead(otherAccount);

  await screen.refetch();

  expect(map.aborted()).toBe(true);
  expect(queryKeys(client)).toEqual([sessionKey]);
});

it('ne voit pas un changement dans la première réponse après que le cache a été vidé', async () => {
  const { client } = signedIn();
  client.getQueryCache().clear();
  client.setQueryData(mapKey, ['établissement du nouveau compte']);
  willRead(otherAccount);

  await client.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });

  expect(client.getQueryData(mapKey)).toEqual([
    'établissement du nouveau compte',
  ]);
});

it('ne voit pas un changement dans la première réponse quand aucune session n’était connue', async () => {
  const client = new QueryClient();
  onTestFinished(watchAccountChange(client));
  client.setQueryData(mapKey, ['établissement fictif']);
  willRead(otherAccount);

  await client.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });

  expect(client.getQueryData(mapKey)).toEqual(['établissement fictif']);
});

it.each([
  { cas: 'une connexion', session: otherAccount },
  { cas: 'une déconnexion', session: null },
])(
  'laisse $cas de cet onglet à son propre vidage : la lecture partie depuis ne bouge pas',
  async ({ session }) => {
    const { client } = signedIn();
    // What a write of this tab does: it empties the cache, a screen reads again, then the session is written.
    client.clear();
    const map = readingMap(client);

    client.setQueryData(sessionKey, session);

    expect(map.aborted()).toBe(false);
    map.answer(['établissement du nouveau compte']);
    await map.reading;
    expect(client.getQueryData(mapKey)).toEqual([
      'établissement du nouveau compte',
    ]);
  },
);

it('supporte la connexion de cet onglet qui vide encore le cache après qu’une lecture a déjà tout oublié', async () => {
  const { client, screen } = signedIn();
  willRead(otherAccount);
  await screen.refetch();
  expect(queryKeys(client)).toEqual([sessionKey]);

  // The login that was running ends: it empties the cache a second time, then writes its own session.
  client.clear();
  client.setQueryData(sessionKey, otherAccount);

  expect(queryKeys(client)).toEqual([sessionKey]);
  expect(client.getQueryData(sessionKey)).toEqual(otherAccount);
});

it('suit le compte écrit par cet onglet : la relecture qui retrouve ce compte ne vide rien', async () => {
  const { client, screen } = signedIn();
  client.clear();
  client.setQueryData(sessionKey, otherAccount);
  client.setQueryData(mapKey, ['établissement du nouveau compte']);
  willRead(otherAccount);

  await screen.refetch();

  expect(client.getQueryData(mapKey)).toEqual([
    'établissement du nouveau compte',
  ]);
});

it.each([
  { cas: 'un onglet', wrapper: undefined },
  { cas: 'un onglet en mode strict, monté deux fois', wrapper: StrictMode },
])(
  'surveille le compte pour $cas tant qu’il est monté, et plus après son démontage',
  async ({ wrapper }) => {
    const client = new QueryClient();
    client.setQueryData(sessionKey, formerAccount);
    client.setQueryData(mapKey, ['établissement fictif']);
    const { unmount } = renderHook(() => useAccountChange(client), { wrapper });

    willRead(otherAccount);
    await client.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });
    expect(queryKeys(client)).toEqual([sessionKey]);

    unmount();
    client.setQueryData(mapKey, ['établissement du nouveau compte']);
    willRead(formerAccount);
    await client.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });
    expect(client.getQueryData(mapKey)).toEqual([
      'établissement du nouveau compte',
    ]);
  },
);
