import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { otherTab } from '@/test/broadcast';
import { settled } from '@/test/server';
import {
  accessGoogleAccount,
  confirmRegistration,
  linkGoogle,
  login,
  logout,
  readSession,
  resetPassword,
  signupGoogle,
  type Session,
} from './api';
import {
  AuthenticationCoordinationError,
  AuthenticationError,
  SessionUnconfirmedError,
} from './errors';
import {
  sessionKey,
  sessionQueryOptions,
  useConfirmRegistration,
  useGoogleAccess,
  useGoogleLink,
  useGoogleSignup,
  useLogin,
  useLogout,
  useResetPassword,
} from './queries';
import { testSession } from '@/test/handlers/auth';

// Partial mock, the documented exception of docs/frontend/qualite.md: these tests read the `resumeLimit` given to each read, and they reject writes with the exact error to check; MSW sees neither.
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  readSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  accessGoogleAccount: vi.fn(),
  signupGoogle: vi.fn(),
  linkGoogle: vi.fn(),
  resetPassword: vi.fn(),
  confirmRegistration: vi.fn(),
}));

const limitsSeen: unknown[] = [];
afterEach(() => {
  limitsSeen.length = 0;
  vi.useRealTimers();
  vi.resetAllMocks();
});

const limited = (retryAfter?: number) =>
  new SessionUnconfirmedError(
    new AuthenticationError(429, 'RATE_LIMITED', retryAfter),
  );
const read = (client: QueryClient) =>
  client.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });
function nextRead(result: () => Promise<Session | null>) {
  vi.mocked(readSession).mockImplementationOnce(async (options) => {
    limitsSeen.push(options?.resumeLimit?.());
    return result();
  });
}
const refuses = (error: Error) => () => Promise.reject(error);
const finds = (session: Session | null) => () => Promise.resolve(session);
const lastLimit = () => limitsSeen.at(-1);

it('garde l’attente plus longtemps que la durée de vie par défaut du cache', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
  const refusal = limited(900);
  nextRead(refuses(refusal));
  const client = new QueryClient();
  await expect(read(client)).rejects.toBe(refusal);

  await vi.advanceTimersByTimeAsync(6 * 60_000);
  nextRead(finds(null));
  await read(client);
  expect(lastLimit()).toBe(refusal);
});

it('oublie l’attente quand le cache est vidé par une nouvelle connexion', async () => {
  nextRead(refuses(limited(600)));
  const client = new QueryClient();
  await expect(read(client)).rejects.toBeInstanceOf(SessionUnconfirmedError);

  client.clear();
  nextRead(finds(null));
  await read(client);
  expect(lastLimit()).toBeUndefined();
});

it('oublie l’attente quand une lecture restaure la session, par exemple connectée dans un autre onglet', async () => {
  nextRead(refuses(limited(600)));
  const client = new QueryClient();
  await expect(read(client)).rejects.toBeInstanceOf(SessionUnconfirmedError);
  nextRead(finds(testSession('jeune_fictif')));
  await read(client);

  nextRead(finds(null));
  await read(client);
  expect(lastLimit()).toBeUndefined();
});

it('garde l’attente d’une reprise dont la lecture a été annulée avant sa réponse', async () => {
  const refusal = limited(600);
  const client = new QueryClient();
  let refuse!: () => void;
  vi.mocked(readSession).mockImplementationOnce(
    () =>
      new Promise((_resolve, reject) => {
        refuse = () => reject(refusal);
      }),
  );
  const reading = read(client);
  reading.catch(() => undefined);
  await vi.waitFor(() => expect(readSession).toHaveBeenCalledOnce());
  // A login form is sent: the read is cancelled, but the reconnection it started still answers.
  await client.cancelQueries();
  refuse();
  await reading.catch(() => undefined);

  nextRead(finds(null));
  await read(client);
  expect(lastLimit()).toBe(refusal);
});

it('ne garde pas d’attente pour un refus sans délai', async () => {
  nextRead(refuses(new SessionUnconfirmedError(new AuthenticationError(503))));
  const client = new QueryClient();
  await expect(read(client)).rejects.toBeInstanceOf(SessionUnconfirmedError);

  nextRead(finds(null));
  await read(client);
  expect(lastLimit()).toBeUndefined();
});

const wrote = [
  {
    cas: 'une connexion',
    write: login,
    useWrite: useLogin,
    values: { identifier: 'jeune_fictif', password: 'x' },
  },
  {
    cas: 'une déconnexion',
    write: logout,
    useWrite: useLogout,
    values: undefined,
  },
  {
    cas: 'un accès par Google',
    write: accessGoogleAccount,
    useWrite: useGoogleAccess,
    values: { flowId: 'flux-fictif' },
  },
  {
    cas: 'une inscription par Google',
    write: signupGoogle,
    useWrite: useGoogleSignup,
    values: { flowId: 'flux-fictif' },
  },
  {
    cas: 'une liaison Google',
    write: linkGoogle,
    useWrite: useGoogleLink,
    values: { flowId: 'flux-fictif' },
  },
  {
    cas: 'un changement de mot de passe',
    write: resetPassword,
    useWrite: useResetPassword,
    values: { flowId: 'flux-fictif' },
  },
  {
    cas: 'une confirmation',
    write: confirmRegistration,
    useWrite: useConfirmRegistration,
    values: { flowId: 'flux-fictif' },
  },
] as const;

async function failWriteWith(
  { write, useWrite, values }: (typeof wrote)[number],
  error: Error,
) {
  vi.mocked(write as (values?: unknown) => Promise<unknown>).mockRejectedValue(
    error,
  );
  const client = new QueryClient();
  client.setQueryData(sessionKey, testSession('jeune_fictif'));
  const { result } = renderHook(
    () => (useWrite as () => ReturnType<typeof useLogout>)(),
    {
      wrapper: ({ children }: { children: ReactNode }) =>
        createElement(QueryClientProvider, { client }, children),
    },
  );
  result.current.mutate(values as never);
  await waitFor(() => expect(result.current.isError).toBe(true));
  return { client, write };
}

it.each(wrote)(
  'invalide la session quand $cas dépasse son délai, sans renvoyer l’écriture',
  async (kind) => {
    const { client, write } = await failWriteWith(
      kind,
      new AuthenticationCoordinationError('timeout'),
    );

    expect(client.getQueryState(sessionKey)?.isInvalidated).toBe(true);
    expect(client.getQueryData(sessionKey)).toEqual(
      testSession('jeune_fictif'),
    );
    expect(write).toHaveBeenCalledOnce();
  },
);

it.each([
  {
    cas: 'un refus de l’API',
    error: new AuthenticationError(401),
  },
  {
    cas: 'un navigateur sans verrou',
    error: new AuthenticationCoordinationError('unsupported'),
  },
  { cas: 'une panne', error: new TypeError('Failed to fetch') },
])('garde la session telle quelle après $cas', async ({ error }) => {
  const { client } = await failWriteWith(wrote[0], error);

  expect(client.getQueryState(sessionKey)?.isInvalidated).toBe(false);
});

async function runWrite(
  { write, useWrite, values }: (typeof wrote)[number],
  outcome: unknown,
) {
  vi.mocked(write as (values?: unknown) => Promise<unknown>).mockResolvedValue(
    outcome,
  );
  const client = new QueryClient();
  const { result } = renderHook(
    () => (useWrite as () => ReturnType<typeof useLogout>)(),
    {
      wrapper: ({ children }: { children: ReactNode }) =>
        createElement(QueryClientProvider, { client }, children),
    },
  );
  result.current.mutate(values as never);
  await waitFor(() => expect(result.current.isSuccess).toBe(true));
}

const [
  loginWrite,
  logoutWrite,
  googleAccessWrite,
  googleSignupWrite,
  googleLinkWrite,
  resetWrite,
  confirmationWrite,
] = wrote;

it.each([
  { kind: loginWrite, outcome: testSession('jeune_fictif') },
  { kind: logoutWrite, outcome: undefined },
  { kind: googleAccessWrite, outcome: testSession('jeune_fictif') },
  { kind: googleSignupWrite, outcome: testSession('jeune_fictif') },
  { kind: resetWrite, outcome: { changed: true } },
  { kind: confirmationWrite, outcome: testSession('jeune_fictif') },
])(
  'prévient les autres onglets une fois que $kind.cas est confirmé',
  async ({ kind, outcome }) => {
    const { received: heard } = otherTab();

    await runWrite(kind, outcome);

    await waitFor(() => expect(heard).toEqual(['session-changed']));
  },
);

it.each([
  {
    cas: 'une liaison Google, qui conserve les sessions',
    kind: googleLinkWrite,
    outcome: { changed: true },
  },
])(
  'ne prévient pas les autres onglets après $cas',
  async ({ kind, outcome }) => {
    const { received: heard } = otherTab();

    await runWrite(kind, outcome);
    await settled();

    expect(heard).toEqual([]);
  },
);

it.each(wrote)(
  'prévient les autres onglets quand $cas dépasse son délai : ses cookies ont pu changer',
  async (kind) => {
    const { received: heard } = otherTab();

    await failWriteWith(kind, new AuthenticationCoordinationError('timeout'));

    await waitFor(() => expect(heard).toEqual(['session-changed']));
  },
);

it.each([
  { cas: 'un refus de l’API', error: new AuthenticationError(401) },
  {
    cas: 'un navigateur sans verrou',
    error: new AuthenticationCoordinationError('unsupported'),
  },
  { cas: 'une panne', error: new TypeError('Failed to fetch') },
])('ne prévient pas les autres onglets après $cas', async ({ error }) => {
  const { received: heard } = otherTab();

  await failWriteWith(loginWrite, error);
  await settled();

  expect(heard).toEqual([]);
});
