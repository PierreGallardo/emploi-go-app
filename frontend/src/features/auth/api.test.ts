import { http, HttpResponse } from 'msw';
import { afterEach, expect, it, vi } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  sessionResponse,
  signupAcceptedResponse,
  testRegistration,
  testSession,
} from '@/test/handlers/auth';
import {
  holdAuthenticationLock,
  holdNextRead,
  reconnectable,
  readsSessionAs,
  resumesAs,
} from '@/test/auth';
import { holdResponse, recordRequests, stallRequests } from '@/test/requests';
import { server, settled } from '@/test/server';
import { api } from '@/api/client';
import {
  accessGoogleAccount,
  chooseUsername,
  confirmGoogleEmail,
  confirmRegistration,
  linkGoogle,
  login,
  logout,
  readGoogleStatus,
  readSession,
  requestGoogleEmail,
  requestRecovery,
  resendConfirmation,
  resetPassword,
  signup,
  signupGoogle,
  startGoogle,
} from './api';
import {
  AuthenticationCoordinationError,
  SessionUnconfirmedError,
  authenticationMessage,
  defaultRetryAfterSeconds,
  isReconnectionLimited,
  maxRetryAfterSeconds,
  retryAtOf,
} from './errors';
import { authenticationDeadline } from './session/lock';

afterEach(() => {
  vi.useRealTimers();
});

function dateIn(waitMs: number | undefined) {
  return waitMs === undefined ? undefined : Date.now() + waitMs;
}

it('refuse les opérations sans coordination entre onglets et explique comment continuer', async () => {
  vi.stubGlobal('navigator', { locks: undefined });
  const requests = recordRequests();
  const error = await readSession().catch((failure: unknown) => failure);
  expect(error).toBeInstanceOf(AuthenticationCoordinationError);
  expect(authenticationMessage(error)).toContain('navigateur récent');
  await expect(
    login({ identifier: 'fictif', password: 'fictif', rememberMe: false }),
  ).rejects.toBeInstanceOf(AuthenticationCoordinationError);
  await expect(logout()).rejects.toBeInstanceOf(
    AuthenticationCoordinationError,
  );
  await settled();
  expect(requests.received).toEqual([]);
});

it('reprend une session seulement après 401 avec l’indicateur, sans annuler la rotation', async () => {
  reconnectable();
  const controller = new AbortController();
  readsSessionAs(anonymousResponse);
  const rotation = holdResponse();
  server.use(http.post('/api/auth/resume', rotation.resolver, { once: true }));
  const requests = recordRequests();
  const pending = readSession({ signal: controller.signal });
  await vi.waitFor(() =>
    expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1),
  );
  controller.abort();
  rotation.release(sessionResponse('jeune_demo'));
  await expect(pending).resolves.toEqual(testSession('jeune_demo'));
});

it('envoie le jeton CSRF à la reprise de session', async () => {
  reconnectable();
  readsSessionAs(anonymousResponse);
  const requests = recordRequests();
  await expect(readSession()).resolves.toEqual(testSession('jeune_fictif'));
  expect(
    requests.of('POST', '/api/auth/resume')[0]?.headers.get('X-CSRF-TOKEN'),
  ).toBe('csrf-fictif');
});

it('ne tente aucune reprise ni demande de jeton pour un visiteur anonyme sans indicateur', async () => {
  readsSessionAs(anonymousResponse);
  const requests = recordRequests();
  await expect(readSession()).resolves.toBeNull();
  await settled();
  expect(requests.routes()).toEqual(['GET /api/auth/session']);
});

it('ne transforme pas une panne de session en visite anonyme ou reprise', async () => {
  reconnectable();
  readsSessionAs(() => problemResponse(503));
  const requests = recordRequests();
  await expect(readSession()).rejects.toMatchObject({ status: 503 });
  await settled();
  expect(requests.routes()).toEqual(['GET /api/auth/session']);
});

it.each([
  {
    action: 'login',
    mutate: () =>
      login({
        identifier: 'nouveau',
        password: 'mot de passe fictif',
        rememberMe: false,
      }),
  },
  { action: 'logout', mutate: () => logout() },
])(
  'attend une réponse de rotation en vol avant $action, même après annulation de la lecture',
  async ({ action, mutate }) => {
    reconnectable();
    const controller = new AbortController();
    readsSessionAs(anonymousResponse);
    const rotation = holdResponse();
    server.use(
      http.post('/api/auth/resume', rotation.resolver, { once: true }),
    );
    const requests = recordRequests();
    const reading = readSession({ signal: controller.signal });
    await vi.waitFor(() =>
      expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1),
    );
    controller.abort();
    const mutation = mutate();
    await settled();
    expect(requests.of('POST', `/api/auth/${action}`)).toHaveLength(0);
    rotation.release(sessionResponse('ancien'));
    await reading;
    await mutation;
    expect(requests.routes()).toEqual([
      'GET /api/auth/session',
      'POST /api/auth/resume',
      `POST /api/auth/${action}`,
    ]);
  },
);

it('retourne une visite anonyme uniquement si la reprise est refusée', async () => {
  reconnectable();
  readsSessionAs(anonymousResponse);
  resumesAs(anonymousResponse);
  await expect(readSession()).resolves.toBeNull();
});

it('signale une reprise limitée (429) comme une session non confirmée, sans visite anonyme ni nouvelle requête', async () => {
  reconnectable();
  readsSessionAs(anonymousResponse);
  resumesAs(() => problemResponse(429, 'RATE_LIMITED'));
  const requests = recordRequests();
  const error = await readSession().catch((failure: unknown) => failure);
  expect(error).toBeInstanceOf(SessionUnconfirmedError);
  expect((error as SessionUnconfirmedError).cause).toMatchObject({
    status: 429,
    code: 'RATE_LIMITED',
  });
  expect(isReconnectionLimited(error)).toBe(true);
  expect(authenticationMessage(error)).toBe(
    'Trop de tentatives. Réessaie plus tard.',
  );
  // One session read and one resume: the read itself never asks again (the query does not retry).
  await settled();
  expect(requests.routes()).toEqual([
    'GET /api/auth/session',
    'POST /api/auth/resume',
  ]);
});

it.each([
  { cas: 'une panne du serveur', status: 503, code: undefined },
  { cas: 'un jeton CSRF refusé', status: 403, code: 'CSRF_INVALID' },
])(
  'laisse la session non confirmée quand la reprise après un 401 échoue par $cas',
  async ({ status, code }) => {
    reconnectable();
    readsSessionAs(anonymousResponse);
    resumesAs(() => problemResponse(status, code));
    const error = await readSession().catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(SessionUnconfirmedError);
    expect((error as SessionUnconfirmedError).cause).toMatchObject({ status });
    expect(isReconnectionLimited(error)).toBe(false);
  },
);

it('laisse la session non confirmée quand la reprise après un 401 échoue sans réponse du réseau', async () => {
  reconnectable();
  readsSessionAs(anonymousResponse);
  resumesAs(() => HttpResponse.error());
  const error = await readSession().catch((failure: unknown) => failure);
  expect(error).toBeInstanceOf(SessionUnconfirmedError);
  expect((error as SessionUnconfirmedError).cause).toBeInstanceOf(TypeError);
});

it.each([
  { status: 429, header: '30', waitMs: 30_000 },
  { status: 429, header: '900', waitMs: 900_000 },
  { status: 429, header: '86400', waitMs: maxRetryAfterSeconds * 1000 },
  { status: 503, header: '5', waitMs: 5_000 },
  ...['0', '-5', '1.5', 'Wed, 21 Oct 2026 07:28:00 GMT', null].map(
    (header) => ({
      status: 429,
      header,
      waitMs: defaultRetryAfterSeconds * 1000,
    }),
  ),
  { status: 503, header: null, waitMs: undefined },
])(
  'porte l’attente de la reprise en réponse $status avec Retry-After : $header',
  async ({ status, header, waitMs }) => {
    // Only the clock is needed: the wait is computed from `Date.now()`, so the timers stay real.
    vi.useFakeTimers({ toFake: ['Date'] });
    reconnectable();
    readsSessionAs(anonymousResponse);
    resumesAs(() =>
      problemResponse(
        status,
        undefined,
        header === null ? undefined : { 'Retry-After': header },
      ),
    );
    const error = await readSession().catch((failure: unknown) => failure);
    expect(retryAtOf(error)).toBe(dateIn(waitMs));
  },
);

it('relit la session mais ne demande ni jeton ni reprise tant que l’attente d’une reprise limitée court', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  reconnectable();
  readsSessionAs(anonymousResponse);
  server.use(
    http.post(
      '/api/auth/resume',
      () => problemResponse(429, undefined, { 'Retry-After': '30' }),
      { once: true },
    ),
  );
  const requests = recordRequests();
  const limit = await readSession().catch((failure: unknown) => failure);
  document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0';

  vi.advanceTimersByTime(29_000);
  await expect(
    readSession({ resumeLimit: () => limit as SessionUnconfirmedError }),
  ).rejects.toBe(limit);
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(2);
  expect(requests.of('GET', '/api/auth/csrf')).toHaveLength(0);
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);

  vi.advanceTimersByTime(1_000);
  document.cookie = 'XSRF-TOKEN=csrf-fictif; Path=/';
  resumesAs(() => sessionResponse('jeune_fictif'));
  await expect(
    readSession({ resumeLimit: () => limit as SessionUnconfirmedError }),
  ).resolves.toEqual(testSession('jeune_fictif'));
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(2);
});

it('laisse une session valide d’un autre onglet l’emporter sur l’attente d’une reprise limitée', async () => {
  reconnectable();
  server.use(http.get('/api/auth/session', anonymousResponse, { once: true }));
  resumesAs(() => problemResponse(429, undefined, { 'Retry-After': '600' }));
  const limit = await readSession().catch((failure: unknown) => failure);
  await expect(
    readSession({ resumeLimit: () => limit as SessionUnconfirmedError }),
  ).resolves.toEqual(testSession('jeune_fictif'));
});

it('envoie le jeton courant du cookie à chaque mutation', async () => {
  let loginBody: unknown;
  server.use(
    http.post('/api/auth/login', async ({ request }) => {
      loginBody = await request.json();
      document.cookie = 'XSRF-TOKEN=after; Path=/';
      return sessionResponse('jeune_demo');
    }),
  );
  const requests = recordRequests();
  const values = {
    identifier: 'Jeune_demo',
    password: ' phrase 🔑 ',
    rememberMe: false,
  };
  await expect(login(values)).resolves.toEqual(testSession('jeune_demo'));
  await logout();
  expect(requests.routes()).toEqual([
    'POST /api/auth/login',
    'POST /api/auth/logout',
  ]);
  expect(loginBody).toEqual(values);
  expect(
    requests.received.map(({ headers }) => headers.get('X-CSRF-TOKEN')),
  ).toEqual(['csrf-fictif', 'after']);
});

it('demande le cookie CSRF quand il manque, puis envoie son jeton', async () => {
  document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0';
  const requests = recordRequests();
  await login({
    identifier: 'Jeune_demo',
    password: ' phrase 🔑 ',
    rememberMe: false,
  });
  expect(requests.routes()).toEqual([
    'GET /api/auth/csrf',
    'POST /api/auth/login',
  ]);
  expect(
    requests.of('POST', '/api/auth/login')[0]?.headers.get('X-CSRF-TOKEN'),
  ).toBe('csrf-serveur');
});

it('n’envoie aucune mutation quand le cookie CSRF reste absent après sa demande', async () => {
  document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0';
  server.use(
    http.get('/api/auth/csrf', () => new HttpResponse(null, { status: 204 })),
  );
  const requests = recordRequests();
  await expect(
    login({
      identifier: 'Jeune_demo',
      password: ' phrase 🔑 ',
      rememberMe: false,
    }),
  ).rejects.toThrow('Protection de connexion indisponible.');
  await settled();
  expect(requests.routes()).toEqual(['GET /api/auth/csrf']);
});

it.each([
  { cas: 'un pseudo vide', body: { accountId: 'compte-fictif', username: '' } },
  { cas: 'sans compte', body: { username: 'jeune_fictif' } },
  { cas: 'un compte vide', body: { accountId: '', username: 'jeune_fictif' } },
])(
  'refuse une réponse de session réussie incohérente : $cas',
  async ({ body }) => {
    readsSessionAs(() => HttpResponse.json(body));
    await expect(readSession()).rejects.toThrow(
      'Réponse de connexion invalide.',
    );
  },
);

it('demande au navigateur de ne rien garder en cache pour les opérations de session', async () => {
  reconnectable();
  readsSessionAs(anonymousResponse);
  document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0';
  // The spies call through to the real client, since MSW does not see the cache mode.
  const get = vi.spyOn(api, 'GET');
  const post = vi.spyOn(api, 'POST');

  await readSession();
  await login({
    identifier: 'Jeune_demo',
    password: ' phrase 🔑 ',
    rememberMe: false,
  });
  await logout();

  const noStore = expect.objectContaining({ cache: 'no-store' });
  expect(get).toHaveBeenCalledWith('/api/auth/session', noStore);
  expect(get).toHaveBeenCalledWith('/api/auth/csrf', noStore);
  expect(get).toHaveBeenCalledTimes(2);
  expect(post).toHaveBeenCalledWith('/api/auth/resume', noStore);
  expect(post).toHaveBeenCalledWith('/api/auth/login', noStore);
  expect(post).toHaveBeenCalledWith('/api/auth/logout', noStore);
  expect(post).toHaveBeenCalledTimes(3);
});

const done = () => new HttpResponse(null, { status: 204 });
const linked = () => HttpResponse.json({ changed: true });
type WriteCase = {
  route: string;
  body: unknown;
  wire: string;
  send: (body: never) => Promise<unknown>;
  answer: () => Response;
  result: unknown;
};
/** Ties the body of a case to what its function accepts: the table itself is not typed by the schema. `wire` is the body as sent. */
function writeCase<Body>(entry: {
  route: string;
  body?: Body;
  send: (body: Body) => Promise<unknown>;
  answer: () => Response;
  result: unknown;
}) {
  const wire = entry.body === undefined ? '' : JSON.stringify(entry.body);
  return { ...entry, wire } as unknown as WriteCase;
}
const writes: WriteCase[] = [
  writeCase({
    route: '/api/auth/google/start',
    send: () => startGoogle(),
    answer: () =>
      HttpResponse.json({ authorizationUri: 'http://localhost/autorisation' }),
    result: 'http://localhost/autorisation',
  }),
  writeCase({
    route: '/api/auth/google/access',
    body: { flowId: 'flux-fictif', identifier: 'jeune', password: 'secret' },
    send: (body) => accessGoogleAccount(body),
    answer: () => sessionResponse('jeune_demo'),
    result: testSession('jeune_demo'),
  }),
  writeCase({
    route: '/api/auth/google/signup',
    body: {
      flowId: 'flux-fictif',
      username: 'jeune',
      acceptedTerms: true,
    },
    send: (body) => signupGoogle(body),
    answer: () => sessionResponse('jeune_demo'),
    result: testSession('jeune_demo'),
  }),
  writeCase({
    route: '/api/auth/google/link',
    body: {
      flowId: 'flux-fictif',
    },
    send: (body) => linkGoogle(body),
    answer: linked,
    result: { changed: true },
  }),
  writeCase({
    route: '/api/auth/google/email',
    body: {
      flowId: 'flux-fictif',
      email: 'jeune@exemple.fr',
    },
    send: (body) => requestGoogleEmail(body),
    answer: done,
    result: undefined,
  }),
  writeCase({
    route: '/api/auth/google/email/confirm',
    body: { flowId: 'flux-fictif', token: 'jeton-fictif' },
    send: (body) => confirmGoogleEmail(body),
    answer: done,
    result: undefined,
  }),
  writeCase({
    route: '/api/auth/login',
    body: { identifier: 'jeune', password: 'secret', rememberMe: false },
    send: (body) => login(body),
    answer: () => sessionResponse('jeune_demo'),
    result: testSession('jeune_demo'),
  }),
  writeCase({
    route: '/api/auth/logout',
    send: () => logout(),
    answer: done,
    result: undefined,
  }),
  writeCase({
    route: '/api/auth/signup',
    body: {
      username: 'jeune',
      email: 'jeune@exemple.fr',
      password: 'secret',
      confirmation: 'secret',
      acceptedTerms: true,
      rememberMe: true,
    },
    send: (body) => signup(body),
    answer: () => signupAcceptedResponse(),
    result: { registration: testRegistration, resendAt: undefined },
  }),
  writeCase({
    route: '/api/auth/signup/resend',
    body: { registration: 'inscription-fictive' },
    send: (body) => resendConfirmation(body),
    answer: done,
    result: undefined,
  }),
  writeCase({
    route: '/api/auth/signup/confirm',
    body: { code: '123456', registration: 'inscription-fictive' },
    send: (body) => confirmRegistration(body),
    answer: () => HttpResponse.json(testSession('jeune_demo')),
    result: testSession('jeune_demo'),
  }),
  writeCase({
    route: '/api/auth/password/forgot',
    body: {
      email: 'jeune@exemple.fr',
    },
    send: (body) => requestRecovery(body),
    answer: done,
    result: undefined,
  }),
  writeCase({
    route: '/api/auth/password/reset',
    body: {
      token: 'jeton-fictif',
      password: 'secret',
      confirmation: 'secret',
    },
    send: (body) => resetPassword(body),
    answer: linked,
    result: { changed: true },
  }),
  writeCase({
    route: '/api/auth/username',
    body: { username: 'jeune' },
    send: (body) => chooseUsername(body),
    answer: () => sessionResponse('jeune'),
    result: testSession('jeune'),
  }),
];

it.each(writes)(
  'envoie $route une seule fois, avec le jeton CSRF puis son corps, et rend le résultat contrôlé',
  async ({ route, body, wire, send, answer, result }) => {
    let sent: string | undefined;
    server.use(
      http.post(route, async ({ request }) => {
        sent = await request.text();
        return answer();
      }),
    );
    const requests = recordRequests();

    await expect(send(body as never)).resolves.toEqual(result);

    expect(requests.routes()).toEqual([`POST ${route}`]);
    expect(requests.received[0]?.headers.get('X-CSRF-TOKEN')).toBe(
      'csrf-fictif',
    );
    expect(sent).toBe(wire);
    expect(sent).not.toContain('requestId');
  },
);

const refusals = [
  {
    cas: 'un refus de l’API',
    fails: () => problemResponse(403, 'CSRF_INVALID'),
    expected: { status: 403, code: 'CSRF_INVALID' },
  },
  {
    cas: 'une panne du serveur',
    fails: () => problemResponse(503),
    expected: { status: 503 },
  },
  {
    cas: 'une panne réseau',
    fails: () => HttpResponse.error(),
    expected: { name: 'TypeError' },
  },
];

it.each(
  writes.flatMap((write) =>
    refusals.map((refusal) => ({ ...write, ...refusal })),
  ),
)(
  'ne rejoue pas $route après $cas',
  async ({ route, body, send, fails, expected }) => {
    server.use(http.post(route, fails));
    const requests = recordRequests();

    await expect(send(body as never)).rejects.toMatchObject(expected);
    await settled();

    expect(requests.routes()).toEqual([`POST ${route}`]);
  },
);

// A few wait cases per write: the parsing of `Retry-After` is tested once, on the reading side.
const writeRetryCases = [
  { status: 429, header: '30', waitMs: 30_000 },
  { status: 429, header: 'abc', waitMs: defaultRetryAfterSeconds * 1000 },
  { status: 503, header: '5', waitMs: undefined },
];

it.each(
  writes.flatMap((write) =>
    writeRetryCases.map((retry) => ({ ...write, ...retry })),
  ),
)(
  'porte l’attente de $route en réponse $status avec Retry-After : $header',
  async ({ route, body, send, status, header, waitMs }) => {
    vi.useFakeTimers({ toFake: ['Date'] });
    server.use(
      http.post(route, () =>
        problemResponse(
          status,
          undefined,
          header === null ? undefined : { 'Retry-After': header },
        ),
      ),
    );

    const error = await send(body as never).catch(
      (failure: unknown) => failure,
    );

    expect(error).toMatchObject({ status });
    expect(retryAtOf(error)).toBe(dateIn(waitMs));
  },
);

const unconfirmedWrites = [
  {
    cas: 'une liaison Google',
    route: '/api/auth/google/link',
    send: () => linkGoogle({ flowId: 'flux-fictif' }),
    message: 'Résultat de la liaison non confirmé.',
  },
  {
    cas: 'un changement de mot de passe',
    route: '/api/auth/password/reset',
    send: () =>
      resetPassword({
        token: 'jeton-fictif',
        password: 'secret',
        confirmation: 'secret',
      }),
    message: 'Résultat du changement non confirmé.',
  },
];
const unconfirmedAnswers = [
  {
    reponse: 'avec changed à faux',
    answer: () => HttpResponse.json({ changed: false }),
  },
  {
    reponse: 'sans corps',
    answer: () => new HttpResponse(null, { status: 200 }),
  },
];

it.each(
  unconfirmedWrites.flatMap((write) =>
    unconfirmedAnswers.map((answer) => ({ ...write, ...answer })),
  ),
)(
  'refuse le résultat de $cas quand la réponse est $reponse',
  async ({ route, send, answer, message }) => {
    server.use(http.post(route, answer));

    await expect(send()).rejects.toThrow(message);
  },
);

it.each([
  {
    cas: 'une étape inconnue',
    body: { pending: true, flowId: 'flux-fictif', stage: 'INCONNUE' },
  },
  {
    cas: 'une étape sans identifiant de flux',
    body: { pending: true, stage: 'CHOOSE_USERNAME' },
  },
  {
    cas: 'une réponse sans indicateur d’attente',
    body: { stage: 'CHOOSE_USERNAME' },
  },
])('refuse un statut Google avec $cas', async ({ body }) => {
  server.use(
    http.get('/api/auth/google/status', () => HttpResponse.json(body)),
  );

  await expect(readGoogleStatus()).rejects.toThrow(
    'Étape Google indisponible.',
  );
});

it.each([
  { cas: 'aucune étape en attente', body: { pending: false } },
  {
    cas: 'l’étape de choix du pseudo',
    body: { pending: true, flowId: 'flux-fictif', stage: 'CHOOSE_USERNAME' },
  },
])('rend le statut Google pour $cas', async ({ body }) => {
  server.use(
    http.get('/api/auth/google/status', () => HttpResponse.json(body)),
  );

  await expect(readGoogleStatus()).resolves.toEqual(body);
});

const deadlineMs = authenticationDeadline;
/** Only the timers: the deadline is a timer, and the network of the tests must keep its own clock. */
const useDeadlineClock = () =>
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });

const timeoutError = expect.objectContaining({
  name: 'Error',
  reason: 'timeout',
});

it.each(writes)(
  'abandonne $route après 15 s d’attente du verrou, sans rien envoyer',
  async ({ send, body }) => {
    useDeadlineClock();
    holdAuthenticationLock();
    const requests = recordRequests();
    const outcome = send(body as never).catch((failure: unknown) => failure);

    await vi.advanceTimersByTimeAsync(deadlineMs);

    const error = await outcome;
    expect(error).toBeInstanceOf(AuthenticationCoordinationError);
    expect(error).toEqual(timeoutError);
    await settled();
    expect(requests.received).toEqual([]);
  },
);

it('attend le verrou tant que le délai n’est pas atteint, puis s’exécute', async () => {
  useDeadlineClock();
  const release = holdAuthenticationLock();
  const requests = recordRequests();
  const pending = login({
    identifier: 'jeune',
    password: 'secret',
    rememberMe: false,
  });

  await vi.advanceTimersByTimeAsync(deadlineMs - 1);
  release();

  await expect(pending).resolves.toEqual(testSession('jeune_fictif'));
  expect(requests.routes()).toEqual(['POST /api/auth/login']);
});

it('rend le verrou à l’onglet suivant quand l’attente de la demande précédente a expiré', async () => {
  useDeadlineClock();
  const release = holdAuthenticationLock();
  const abandoned = login({
    identifier: 'jeune',
    password: 'secret',
    rememberMe: false,
  }).catch((failure: unknown) => failure);
  await vi.advanceTimersByTimeAsync(deadlineMs);
  await abandoned;
  release();

  await expect(
    login({ identifier: 'jeune', password: 'secret', rememberMe: false }),
  ).resolves.toEqual(testSession('jeune_fictif'));
});

it('laisse l’annulation de l’appelant interrompre l’attente du verrou, avec sa raison, sans lecture envoyée ensuite', async () => {
  useDeadlineClock();
  const release = holdAuthenticationLock();
  const requests = recordRequests();
  const controller = new AbortController();
  const reason = new Error('annulée par l’écran');
  const reading = readSession({ signal: controller.signal });

  controller.abort(reason);

  await expect(reading).rejects.toBe(reason);
  release();
  await settled();
  expect(requests.received).toEqual([]);
});

it('laisse une lecture déjà annulée échouer avec sa raison, sans attendre le délai', async () => {
  useDeadlineClock();
  const reason = new Error('déjà annulée');
  const requests = recordRequests();

  await expect(readSession({ signal: AbortSignal.abort(reason) })).rejects.toBe(
    reason,
  );
  await settled();
  expect(requests.received).toEqual([]);
});

it('donne à la rotation son propre délai complet, même après une lecture lente', async () => {
  useDeadlineClock();
  reconnectable();
  const answerRead = holdNextRead();
  const rotation = holdResponse();
  server.use(http.post('/api/auth/resume', rotation.resolver, { once: true }));
  const requests = recordRequests();
  const reading = readSession();
  await settled();

  await vi.advanceTimersByTimeAsync(deadlineMs - 1_000);
  answerRead(anonymousResponse());
  await settled();
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
  await vi.advanceTimersByTimeAsync(deadlineMs - 1_000);
  rotation.release(sessionResponse('jeune_demo'));

  await expect(reading).resolves.toEqual(testSession('jeune_demo'));
});

// The requests below are stalled by `stallRequests`, not by MSW: see why in `src/test/requests.ts`.

it('abandonne une écriture sans réponse après 15 s et rend le verrou à la suivante', async () => {
  useDeadlineClock();
  const stalled = stallRequests(api, 'POST', '/api/auth/login');
  const requests = recordRequests();
  const outcome = login({
    identifier: 'jeune',
    password: 'secret',
    rememberMe: false,
  }).catch((failure: unknown) => failure);
  await vi.waitFor(() => expect(stalled.started()).toBe(1));

  await vi.advanceTimersByTimeAsync(authenticationDeadline);

  expect(await outcome).toEqual(timeoutError);
  await expect(logout()).resolves.toBeUndefined();
  expect(requests.routes()).toEqual(['POST /api/auth/logout']);
});

it.each([
  {
    lecture: 'de la session',
    stall: () => stallRequests(api, 'GET', '/api/auth/session'),
    read: () => readSession(),
  },
  {
    lecture: 'de l’étape Google',
    stall: () => stallRequests(api, 'GET', '/api/auth/google/status'),
    read: () => readGoogleStatus(),
  },
])(
  'signale une lecture $lecture sans réponse après 15 s comme une panne, pas comme une session refusée',
  async ({ stall, read }) => {
    useDeadlineClock();
    stall();
    const outcome = read().catch((failure: unknown) => failure);
    await settled();

    await vi.advanceTimersByTimeAsync(authenticationDeadline);

    const error = await outcome;
    expect(error).toEqual(timeoutError);
    expect(error).not.toBeInstanceOf(SessionUnconfirmedError);
  },
);

it('abandonne la rotation sans réponse 15 s après son envoi et laisse la session non confirmée', async () => {
  useDeadlineClock();
  reconnectable();
  readsSessionAs(anonymousResponse);
  const stalled = stallRequests(api, 'POST', '/api/auth/resume');
  const requests = recordRequests();
  const reading = readSession().catch((failure: unknown) => failure);
  await vi.waitFor(() => expect(stalled.started()).toBe(1));

  // The reading before the rotation took part of its own 15 s: the rotation has its own.
  await vi.advanceTimersByTimeAsync(authenticationDeadline);

  const error = await reading;
  expect(error).toBeInstanceOf(SessionUnconfirmedError);
  expect((error as SessionUnconfirmedError).cause).toEqual(timeoutError);
  expect(authenticationMessage(error)).toContain('pris trop de temps');
  await vi.waitFor(() => expect(stalled.started()).toBe(1));
  expect(requests.routes()).toEqual(['GET /api/auth/session']);
});

it.each([
  { cas: 'une autre origine', url: 'https://evil.example/oauth' },
  {
    cas: 'un domaine qui contient celui de Google',
    url: 'https://accounts.google.com.evil.example/oauth',
  },
  { cas: 'Google sans HTTPS', url: 'http://accounts.google.com/oauth' },
  { cas: 'une boucle locale en HTTPS', url: 'https://localhost/oauth' },
])('refuse de suivre $cas pour commencer Google', async ({ url }) => {
  server.use(
    http.post('/api/auth/google/start', () =>
      HttpResponse.json({ authorizationUri: url }),
    ),
  );

  await expect(startGoogle()).rejects.toThrow('Parcours Google indisponible.');
});

it('refuse une inscription acceptée sans identifiant d’inscription', async () => {
  server.use(
    http.post(
      '/api/auth/signup',
      () => new HttpResponse(null, { status: 202 }),
    ),
  );

  await expect(
    signup({
      email: 'jeune@exemple.fr',
      password: 'secret',
      confirmation: 'secret',
      acceptedTerms: true,
      rememberMe: false,
    }),
  ).rejects.toThrow('Inscription indisponible.');
});
