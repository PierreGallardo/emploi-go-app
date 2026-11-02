import { QueryClient } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { readSession } from '../api';
import { AuthenticationError, SessionUnconfirmedError } from '../errors';
import { reconnectSession } from './reauthentication';
import { sessionKey, sessionQueryOptions } from '../queries';
import { testSession } from '@/test/handlers/auth';

// Partial mock: without it, `readSession` would pass through the lock and the rotation, and this test would stop checking what the reconnection does with the answer.
vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  readSession: vi.fn(),
}));

afterEach(() => {
  vi.mocked(readSession).mockReset();
});

const signedIn = () => {
  const client = new QueryClient();
  client.setQueryData(sessionKey, testSession('jeune_fictif'));
  return client;
};
const limited = () =>
  new SessionUnconfirmedError(new AuthenticationError(429, 'RATE_LIMITED', 30));

it.each([
  {
    cas: 'le même compte',
    now: testSession('jeune_fictif'),
    account: 'compte-jeune_fictif',
  },
  {
    cas: 'un autre compte',
    now: testSession('autre-jeune'),
    account: 'compte-autre-jeune',
  },
  {
    cas: 'un compte sans pseudo',
    now: testSession(null, 'compte-sans-pseudo'),
    account: 'compte-sans-pseudo',
  },
  { cas: 'aucun compte', now: null, account: undefined },
])('rend $cas quand la session relue le donne', async ({ now, account }) => {
  vi.mocked(readSession).mockResolvedValueOnce(now);

  await expect(reconnectSession(signedIn())).resolves.toBe(account);
});

it('laisse la panne de la reconnexion terminer la lecture en erreur', async () => {
  const outage = new SessionUnconfirmedError(new TypeError('Failed to fetch'));
  vi.mocked(readSession).mockRejectedValueOnce(outage);

  await expect(reconnectSession(signedIn())).rejects.toBe(outage);
});

it('respecte l’attente demandée par une reprise limitée : la reconnexion suivante la reçoit', async () => {
  const refusal = limited();
  const client = signedIn();
  vi.mocked(readSession).mockRejectedValueOnce(refusal);
  await expect(reconnectSession(client)).rejects.toBe(refusal);
  const seen: unknown[] = [];
  vi.mocked(readSession).mockImplementationOnce(async (options) => {
    seen.push(options?.resumeLimit?.());
    throw refusal;
  });

  await expect(reconnectSession(client)).rejects.toBe(refusal);

  expect(seen).toEqual([refusal]);
});

it('rejoint la lecture de la session d’un écran en cours, puis la relit pour confirmer une réponse peut-être plus ancienne que le 401', async () => {
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
  const screen = client.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

  const reconnected = reconnectSession(client);
  answer();

  await expect(reconnected).resolves.toBe('compte-jeune_fictif');
  await screen;
  expect(readSession).toHaveBeenCalledTimes(2);
});

it('partage l’échec de la lecture de la session d’un écran en cours, sans la relire', async () => {
  const client = signedIn();
  const outage = new SessionUnconfirmedError(new TypeError('Failed to fetch'));
  let fail!: () => void;
  vi.mocked(readSession).mockImplementationOnce(
    () => new Promise((_, reject) => (fail = () => reject(outage))),
  );
  const screen = client
    .fetchQuery({ ...sessionQueryOptions(), staleTime: 0 })
    .catch(() => undefined);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

  const reconnected = reconnectSession(client);
  fail();

  await expect(reconnected).rejects.toBe(outage);
  await screen;
  expect(readSession).toHaveBeenCalledOnce();
});

it('ne donne aucun compte quand la seconde lecture, qui confirme, est annulée', async () => {
  const client = signedIn();
  let answer!: () => void;
  vi.mocked(readSession)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          answer = () => resolve(testSession('jeune_fictif'));
        }),
    )
    .mockImplementationOnce(() => new Promise(() => {}));
  void client
    .fetchQuery({ ...sessionQueryOptions(), staleTime: 0 })
    .catch(() => undefined);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());
  const reconnected = reconnectSession(client);
  answer();
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledTimes(2));

  await client.cancelQueries();

  await expect(reconnected).resolves.toBeUndefined();
  expect(readSession).toHaveBeenCalledTimes(2);
});

it('ne donne aucun compte quand le cache est vidé pendant la reconnexion', async () => {
  const client = signedIn();
  vi.mocked(readSession).mockImplementationOnce(() => new Promise(() => {}));
  const reconnected = reconnectSession(client);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

  client.clear();

  await expect(reconnected).resolves.toBeUndefined();
});

it('ne donne aucun compte quand la lecture de la session est annulée pendant la reconnexion', async () => {
  const client = signedIn();
  vi.mocked(readSession).mockImplementationOnce(() => new Promise(() => {}));
  const reconnected = reconnectSession(client);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());

  // A login or a logout starts: TanStack gives back the session it had, which no read has confirmed.
  await client.cancelQueries();

  await expect(reconnected).resolves.toBeUndefined();
});
