import { http, HttpResponse } from 'msw';
import { expect, it, onTestFinished, vi } from 'vitest';
import { recordRequests } from '@/test/requests';
import { server } from '@/test/server';
import { api } from './client';
import {
  noteSender,
  onUnauthorized,
  replayAfterReconnection,
} from './unauthorized';

const account = 'jeune_fictif';

function install(
  reconnect: () => Promise<string | undefined>,
  { signedIn = true }: { signedIn?: boolean } = {},
) {
  const spy = vi.fn(reconnect);
  const browser = { account: signedIn ? account : undefined };
  onTestFinished(
    onUnauthorized({ account: () => browser.account, reconnect: spy }),
  );
  return Object.assign(spy, { browser });
}
const restores = async () => account;

function refusesFirst(refused: number) {
  let received = 0;
  server.use(
    http.get('/api/establishments', () =>
      ++received <= refused
        ? new HttpResponse(null, { status: 401 })
        : HttpResponse.json({ items: [], total: 0, truncated: false }),
    ),
  );
}
const list = () => api.GET('/api/establishments', { cache: 'no-store' });
const establishmentReads = (requests: ReturnType<typeof recordRequests>) =>
  requests.of('GET', '/api/establishments');

it('reconnecte une fois puis renvoie la lecture, dont la réponse remplace le 401', async () => {
  refusesFirst(1);
  const reconnection = install(restores);
  const requests = recordRequests();

  const { response, data } = await list();

  expect(response.status).toBe(200);
  expect(data).toEqual({ items: [], total: 0, truncated: false });
  expect(reconnection).toHaveBeenCalledOnce();
  expect(establishmentReads(requests)).toHaveLength(2);
});

it.each([
  { cas: 'un autre compte', restored: 'autre-jeune' },
  { cas: 'aucun compte', restored: undefined },
])('rend le 401 quand la reconnexion rétablit $cas', async ({ restored }) => {
  refusesFirst(Infinity);
  const reconnection = install(async () => restored);
  const requests = recordRequests();

  const { response } = await list();

  expect(response.status).toBe(401);
  expect(reconnection).toHaveBeenCalledOnce();
  expect(establishmentReads(requests)).toHaveLength(1);
});

it('ne reconnecte pas une seconde fois quand la lecture renvoyée reçoit encore un 401', async () => {
  refusesFirst(Infinity);
  const reconnection = install(restores);
  const requests = recordRequests();

  const { response } = await list();

  expect(response.status).toBe(401);
  expect(reconnection).toHaveBeenCalledOnce();
  expect(establishmentReads(requests)).toHaveLength(2);
});

it('termine la lecture en erreur, sans la renvoyer, quand la reconnexion échoue', async () => {
  refusesFirst(Infinity);
  const outage = new Error('panne');
  install(() => Promise.reject(outage));
  const requests = recordRequests();

  await expect(list()).rejects.toBe(outage);

  expect(establishmentReads(requests)).toHaveLength(1);
});

it('partage une seule reconnexion entre des lectures qui reçoivent un 401 en même temps', async () => {
  refusesFirst(3);
  let restore!: () => void;
  const reconnection = install(
    () =>
      new Promise<string | undefined>(
        (resolve) => (restore = () => resolve(account)),
      ),
  );
  const requests = recordRequests();

  const reads = [list(), list(), list()];
  await vi.waitFor(() => expect(establishmentReads(requests)).toHaveLength(3));
  await vi.waitFor(() => expect(reconnection).toHaveBeenCalled());
  restore();
  const responses = await Promise.all(reads);

  expect(responses.map(({ response }) => response.status)).toEqual([
    200, 200, 200,
  ]);
  expect(reconnection).toHaveBeenCalledOnce();
  expect(establishmentReads(requests)).toHaveLength(6);
});

it('reconnecte de nouveau pour un 401 d’une lecture envoyée après la fin de la reconnexion précédente', async () => {
  refusesFirst(1);
  const reconnection = install(restores);
  await list();
  server.resetHandlers();
  refusesFirst(1);

  const { response } = await list();

  expect(response.status).toBe(200);
  expect(reconnection).toHaveBeenCalledTimes(2);
});

it.each([
  { cas: 'un 403', answer: () => new HttpResponse(null, { status: 403 }) },
  { cas: 'un 500', answer: () => new HttpResponse(null, { status: 500 }) },
])('ne reconnecte pas pour $cas', async ({ answer }) => {
  server.use(http.get('/api/establishments', answer));
  const reconnection = install(restores);
  const requests = recordRequests();

  const { response } = await list();

  expect(response.status).toBe(answer().status);
  expect(reconnection).not.toHaveBeenCalled();
  expect(establishmentReads(requests)).toHaveLength(1);
});

it('ne reconnecte pas pour une panne du réseau', async () => {
  server.use(http.get('/api/establishments', () => HttpResponse.error()));
  const reconnection = install(restores);

  await expect(list()).rejects.toBeInstanceOf(TypeError);

  expect(reconnection).not.toHaveBeenCalled();
});

it('rend le 401 tel quel quand aucune reconnexion n’est installée', async () => {
  refusesFirst(Infinity);
  const requests = recordRequests();

  const { response } = await list();

  expect(response.status).toBe(401);
  expect(establishmentReads(requests)).toHaveLength(1);
});

it('ne reconnecte pas quand aucun compte n’était connu à l’envoi de la lecture', async () => {
  refusesFirst(Infinity);
  const reconnection = install(restores, { signedIn: false });

  const { response } = await list();

  expect(response.status).toBe(401);
  expect(reconnection).not.toHaveBeenCalled();
});

it('rend à une lecture annulée pendant la reconnexion sa raison, sans la renvoyer', async () => {
  refusesFirst(Infinity);
  let restore!: () => void;
  const reconnection = install(
    () =>
      new Promise<string | undefined>(
        (resolve) => (restore = () => resolve(account)),
      ),
  );
  const requests = recordRequests();
  const deadline = new AbortController();
  const outcome = api
    .GET('/api/establishments', { signal: deadline.signal })
    .catch((failure: unknown) => failure);
  await vi.waitFor(() => expect(restore).toBeTypeOf('function'));

  const expired = new DOMException('Délai dépassé.', 'TimeoutError');
  deadline.abort(expired);
  restore();

  expect(await outcome).toBe(expired);
  expect(establishmentReads(requests)).toHaveLength(1);
  expect(reconnection).toHaveBeenCalledOnce();
});

const answer401 = () => new Response(null, { status: 401 });
function sentRequest(url: string, method = 'GET', signal?: AbortSignal) {
  const request = new Request(`${location.origin}${url}`, { method, signal });
  noteSender(request);
  return request;
}
const sends = () => vi.fn(async () => new Response(null, { status: 204 }));

it.each([
  { cas: 'une écriture', method: 'POST', url: '/api/establishments' },
  { cas: 'une modification', method: 'PUT', url: '/api/establishments/1' },
  { cas: 'une suppression', method: 'DELETE', url: '/api/establishments/1' },
  { cas: 'la lecture de la session', method: 'GET', url: '/api/auth/session' },
  { cas: 'la lecture du jeton CSRF', method: 'GET', url: '/api/auth/csrf' },
  {
    cas: 'une lecture d’une autre origine',
    method: 'GET',
    url: 'http://autre.example/api/establishments',
  },
])('ne rejoue jamais $cas', async ({ method, url }) => {
  const reconnection = install(restores);
  const send = sends();
  const request = new Request(new URL(url, location.origin), { method });
  noteSender(request);

  const replayed = await replayAfterReconnection({
    request,
    response: answer401(),
    send,
  });

  expect(replayed).toBeUndefined();
  expect(reconnection).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalled();
});

it.each(['GET', 'HEAD'])('rejoue une lecture %s', async (method) => {
  install(restores);
  const send = sends();

  const replayed = await replayAfterReconnection({
    request: sentRequest('/api/establishments', method),
    response: answer401(),
    send,
  });

  expect(replayed?.status).toBe(204);
  expect(send).toHaveBeenCalledOnce();
});

it('ne reconnecte ni ne rejoue une lecture envoyée pour un autre compte que celui du moment', async () => {
  const reconnection = install(async () => 'autre-jeune');
  const request = sentRequest('/api/establishments');
  reconnection.browser.account = 'autre-jeune';
  const send = sends();

  const replayed = await replayAfterReconnection({
    request,
    response: answer401(),
    send,
  });

  expect(replayed).toBeUndefined();
  expect(reconnection).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalled();
});

it('ne reconnecte pas pour une lecture dont le signal est déjà annulé', async () => {
  const reconnection = install(restores);
  const reason = new Error('déjà annulée');

  await expect(
    replayAfterReconnection({
      request: sentRequest(
        '/api/establishments',
        'GET',
        AbortSignal.abort(reason),
      ),
      response: answer401(),
      send: sends(),
    }),
  ).rejects.toBe(reason);

  expect(reconnection).not.toHaveBeenCalled();
});

it('fait partager l’échec d’une reconnexion à une lecture partie avant elle dont le 401 arrive après', async () => {
  const outage = new Error('panne');
  const reconnection = install(() => Promise.reject(outage));
  const send = sends();
  const first = sentRequest('/api/establishments');
  const second = sentRequest('/api/establishments');

  await expect(
    replayAfterReconnection({ request: first, response: answer401(), send }),
  ).rejects.toBe(outage);
  await expect(
    replayAfterReconnection({ request: second, response: answer401(), send }),
  ).rejects.toBe(outage);

  expect(reconnection).toHaveBeenCalledOnce();
  expect(send).not.toHaveBeenCalled();
});

it('rejoue le 401 tardif d’une lecture partie avant une reconnexion réussie et rend la réponse rejouée', async () => {
  // A success says nothing about who is signed in later: the late 401 asks the application again.
  const reconnection = install(restores);
  const send = sends();
  const first = sentRequest('/api/establishments');
  const second = sentRequest('/api/establishments');

  await replayAfterReconnection({
    request: first,
    response: answer401(),
    send,
  });
  const replayed = await replayAfterReconnection({
    request: second,
    response: answer401(),
    send,
  });

  expect(replayed?.status).toBe(204);
  expect(reconnection).toHaveBeenCalledTimes(2);
  expect(send).toHaveBeenCalledTimes(2);
});

it('fait partager l’échec d’une reconnexion à une lecture envoyée pendant elle dont le 401 arrive après', async () => {
  const outage = new Error('panne');
  let fail!: () => void;
  const reconnection = install(
    () => new Promise((_, reject) => (fail = () => reject(outage))),
  );
  const send = sends();
  const first = replayAfterReconnection({
    request: sentRequest('/api/establishments'),
    response: answer401(),
    send,
  }).catch((failure: unknown) => failure);
  const second = sentRequest('/api/establishments');
  fail();
  expect(await first).toBe(outage);

  await expect(
    replayAfterReconnection({ request: second, response: answer401(), send }),
  ).rejects.toBe(outage);

  expect(reconnection).toHaveBeenCalledOnce();
});

it('reconnecte de nouveau, après un échec, pour une lecture envoyée après lui', async () => {
  const reconnection = install(() => Promise.reject(new Error('panne')));
  const send = sends();
  await replayAfterReconnection({
    request: sentRequest('/api/establishments'),
    response: answer401(),
    send,
  }).catch(() => undefined);

  await replayAfterReconnection({
    request: sentRequest('/api/establishments'),
    response: answer401(),
    send,
  }).catch(() => undefined);

  expect(reconnection).toHaveBeenCalledTimes(2);
});

it('refuse d’installer une seconde reconnexion tant que la première n’est pas retirée', () => {
  const remove = onUnauthorized({
    account: () => account,
    reconnect: restores,
  });
  onTestFinished(remove);

  expect(() =>
    onUnauthorized({ account: () => account, reconnect: restores }),
  ).toThrow('déjà installée');

  remove();
  onTestFinished(
    onUnauthorized({ account: () => account, reconnect: restores }),
  );
});

it('ne retire que la reconnexion de celui qui la retire', () => {
  const first = onUnauthorized({ account: () => account, reconnect: restores });
  first();
  const second = onUnauthorized({
    account: () => account,
    reconnect: restores,
  });
  onTestFinished(second);

  first();

  expect(() =>
    onUnauthorized({ account: () => account, reconnect: restores }),
  ).toThrow('déjà installée');
});
