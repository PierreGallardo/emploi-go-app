import { http, HttpResponse } from 'msw';
import { expect, it, vi } from 'vitest';
import { login } from '@/features/auth/api';
import { holdResponse, recordRequests } from './requests';
import { assertNoUnhandledRequests, server, settled } from './server';
import { testSession } from '@/test/handlers/auth';

const credentials = {
  identifier: 'jeune_demo',
  password: 'phrase fictive',
  rememberMe: false,
};

/** The relative URL resolves only through the `Request` that setup.ts patches. */
function sendUnknown(query = '') {
  return fetch(new Request(`/api/inconnue${query}`));
}

it('refuse au client une requête sans réponse prévue', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});

  await expect(sendUnknown()).rejects.toThrow(TypeError);

  await expect(assertNoUnhandledRequests()).rejects.toThrow(
    /GET \/api\/inconnue/,
  );
});

it('fait échouer le test en nommant la méthode et le chemin sans la query string, même si la requête n’a pas été attendue', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  void sendUnknown('?name=prenom-fictif').catch(() => undefined);

  await expect(assertNoUnhandledRequests()).rejects.toThrow(
    /GET \/api\/inconnue\./,
  );
  await expect(assertNoUnhandledRequests()).resolves.toBeUndefined();
});

it.each([
  ['une chaîne relative', '/api/exemple', `${location.origin}/api/exemple`],
  [
    'une chaîne absolue',
    'https://example.org/api/exemple',
    'https://example.org/api/exemple',
  ],
  [
    'une URL',
    new URL('https://example.org/api/exemple'),
    'https://example.org/api/exemple',
  ],
  [
    'une instance de Request',
    new Request('https://example.org/api/exemple'),
    'https://example.org/api/exemple',
  ],
])('résout %s comme le navigateur', (_, input, expected) => {
  expect(new Request(input).url).toBe(expected);
});

it('laisse une requête ouverte jusqu’à ce que le test libère sa réponse', async () => {
  const held = holdResponse();
  server.use(http.post('/api/auth/login', held.resolver, { once: true }));
  const requests = recordRequests();
  let answered = false;

  const session = login(credentials).then((value) => {
    answered = true;
    return value;
  });
  // `settled()` cannot wait for a request not yet started (the lock delays it): wait for the request itself first.
  await vi.waitFor(() =>
    expect(requests.of('POST', '/api/auth/login')).toHaveLength(1),
  );
  await settled();

  expect(answered).toBe(false);
  held.release(HttpResponse.json(testSession('jeune_demo')));
  await expect(session).resolves.toEqual(testSession('jeune_demo'));
});
