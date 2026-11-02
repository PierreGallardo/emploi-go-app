import { QueryClient } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { openChannels, otherTab } from '@/test/broadcast';
import { settled } from '@/test/server';
import { readSession } from '../api';
import { sessionKey } from '../queries';
import { createSessionSynchronizer, useSessionSync } from './session-sync';
import { testSession } from '@/test/handlers/auth';

// Partial mock: without it, `readSession` would pass through the lock and the rotation, and this test would stop checking what the sync does with the answer.
vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  readSession: vi.fn(),
}));

afterEach(() => {
  vi.mocked(readSession).mockReset();
});

// Same shape as the points of the map (`pointsQueryOptions` in `features/map/queries.ts`): the account change must drop it.
const mapKey = ['map', 'jeune_fictif', 'points', { sirets: null }] as const;

function signedIn() {
  const client = new QueryClient();
  client.setQueryData(sessionKey, testSession('jeune_fictif'));
  client.setQueryData(mapKey, ['établissement fictif']);
  return client;
}
const synchronize = (client: QueryClient) =>
  createSessionSynchronizer(client)();
it('rejoint la lecture en cours, puis la relit : sa réponse peut dater d’avant le changement', async () => {
  const client = signedIn();
  let answer!: () => void;
  vi.mocked(readSession)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          answer = () => resolve(testSession('jeune_fictif'));
        }),
    )
    .mockResolvedValueOnce(null);
  const screen = client.fetchQuery({
    queryKey: sessionKey,
    queryFn: () => readSession(),
    staleTime: 0,
  });
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

  const synchronizing = synchronize(client);
  answer();
  await synchronizing;
  await screen;

  expect(readSession).toHaveBeenCalledTimes(2);
  expect(client.getQueryData(sessionKey)).toBeNull();
});

it('fusionne les signaux reçus pendant une lecture en une seule lecture de plus', async () => {
  const client = signedIn();
  let answer!: () => void;
  vi.mocked(readSession)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          answer = () => resolve(testSession('jeune_fictif'));
        }),
    )
    .mockResolvedValueOnce(testSession('jeune_fictif'));
  const request = createSessionSynchronizer(client);

  const first = request();
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());
  const others = [request(), request(), request()];
  answer();
  await Promise.all([first, ...others]);

  expect(readSession).toHaveBeenCalledTimes(2);
});

it('lit de nouveau après une lecture terminée : un signal tardif n’est pas perdu', async () => {
  const client = signedIn();
  vi.mocked(readSession).mockResolvedValue(testSession('jeune_fictif'));
  const request = createSessionSynchronizer(client);

  await request();
  await request();

  expect(readSession).toHaveBeenCalledTimes(2);
});

it('relit une fois après la connexion de cet onglet qui a annulé la lecture, et retrouve le compte qu’elle a écrit', async () => {
  const client = signedIn();
  vi.mocked(readSession)
    .mockImplementationOnce(() => new Promise(() => {}))
    .mockResolvedValueOnce(testSession('autre-jeune'));
  const synchronizing = synchronize(client);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

  await client.cancelQueries();
  client.setQueryData(sessionKey, testSession('autre-jeune'));
  await synchronizing;

  expect(readSession).toHaveBeenCalledTimes(2);
  expect(client.getQueryData(sessionKey)).toEqual(testSession('autre-jeune'));
});

it('lit une fois de plus quand une lecture annulée n’a donné aucune session, par exemple après un formulaire refusé', async () => {
  const client = signedIn();
  vi.mocked(readSession)
    .mockImplementationOnce(() => new Promise(() => {}))
    .mockResolvedValueOnce(testSession('autre-jeune'));
  const synchronizing = synchronize(client);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

  await client.cancelQueries();
  await synchronizing;

  expect(readSession).toHaveBeenCalledTimes(2);
  expect(client.getQueryData(sessionKey)).toEqual(testSession('autre-jeune'));
});

it('ne relit qu’une fois de plus quand la lecture est annulée de nouveau', async () => {
  const client = signedIn();
  vi.mocked(readSession).mockImplementation(() => new Promise(() => {}));
  const synchronizing = synchronize(client);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());
  await client.cancelQueries();
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledTimes(2));

  await client.cancelQueries();
  await synchronizing;

  expect(readSession).toHaveBeenCalledTimes(2);
});

it('n’oublie rien lui-même quand le compte change : le vidage appartient à l’observateur du compte', async () => {
  const client = signedIn();
  vi.mocked(readSession).mockResolvedValueOnce(testSession('autre-jeune'));

  await synchronize(client);

  expect(client.getQueryData(sessionKey)).toEqual(testSession('autre-jeune'));
  expect(client.getQueryData(mapKey)).toEqual(['établissement fictif']);
});

it('n’annonce rien aux autres onglets de ce qu’il apprend', async () => {
  const client = signedIn();
  const { channel, received } = otherTab();
  vi.mocked(readSession).mockResolvedValueOnce(null);

  const { unmount } = renderHook(() => useSessionSync(client));
  channel.postMessage('session-changed');
  await vi.waitFor(() => expect(client.getQueryData(sessionKey)).toBeNull());
  await settled();

  expect(received).toEqual([]);
  unmount();
});

it.each([
  { cas: 'un onglet', wrapper: undefined },
  { cas: 'un onglet en mode strict, monté deux fois', wrapper: StrictMode },
])(
  'lit la session une fois par signal d’un autre onglet pour $cas, et plus du tout après le démontage',
  async ({ wrapper }) => {
    const client = signedIn();
    const { channel } = otherTab();
    vi.mocked(readSession).mockResolvedValue(testSession('jeune_fictif'));
    const { unmount } = renderHook(() => useSessionSync(client), { wrapper });

    channel.postMessage('session-changed');
    await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

    unmount();
    expect(openChannels()).toBe(1);
    channel.postMessage('session-changed');
    await settled();
    expect(readSession).toHaveBeenCalledOnce();
  },
);

it('lit la session d’une page restaurée du cache de navigation, qui a pu manquer des signaux, pas d’une page chargée normalement', async () => {
  const client = signedIn();
  vi.mocked(readSession).mockResolvedValue(testSession('jeune_fictif'));
  const { unmount } = renderHook(() => useSessionSync(client));
  const pageshow = (persisted: boolean) =>
    window.dispatchEvent(Object.assign(new Event('pageshow'), { persisted }));

  pageshow(false);
  pageshow(true);

  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());
  await settled();
  expect(readSession).toHaveBeenCalledOnce();
  unmount();
});
