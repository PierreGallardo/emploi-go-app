import type { QueryClient } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { onTestFinished } from 'vitest';
import { sessionKey } from '@/features/auth/queries';
import {
  anonymousResponse,
  sessionResponse,
  testSession,
} from './handlers/auth';
import { holdResponse } from './requests';
import { server } from './server';

/** A fictional secret of a recovery or confirmation link: a UUID, a dot, then 43 characters. */
export const recoveryLink = `12345678-1234-4234-8234-123456789abc.${'a'.repeat(43)}`;

const googleFlowId = 'f496c1d5-03e2-4a40-a887-115580f9113e';

/** The Google flow the status endpoint reports at `stage`; `null` means that no flow is pending. */
export function googleStage(stage: string | null) {
  server.use(
    http.get('/api/auth/google/status', () =>
      HttpResponse.json({
        pending: stage !== null,
        flowId: stage ? googleFlowId : null,
        stage,
        errorCode: null,
      }),
    ),
  );
}

/** The last status region of the screen: the one that announces the current wait or loading. */
export const lastStatus = () => screen.getAllByRole('status').at(-1);

/** Each request on `path` takes the next answer, and the last one repeats. Returns the bodies received. */
export function answersOn(path: string, ...answers: Array<() => Response>) {
  const bodies: unknown[] = [];
  let next = 0;
  server.use(
    http.post(path, async ({ request }) => {
      bodies.push(await request.json());
      const answer = answers[Math.min(next, answers.length - 1)]!;
      next += 1;
      return answer();
    }),
  );
  return bodies;
}

export function signedInAs(username = 'jeune_fictif') {
  return (client: QueryClient) =>
    client.setQueryData(sessionKey, testSession(username));
}

/** The session the client holds now: `undefined` when none was read, `null` when it ended. */
export function cachedSession(client: QueryClient) {
  return client.getQueryData(sessionKey);
}

export function cachedQueryKeys(client: QueryClient) {
  return client
    .getQueryCache()
    .getAll()
    .map((query) => query.queryKey);
}

/** On `http://localhost` the cookie has no `__Host-` prefix. */
export function reconnectable() {
  document.cookie = 'EMPLOIGO_RECONNECTABLE=1; Path=/';
}

export function readsSessionAs(answer: () => Response) {
  server.use(http.get('/api/auth/session', answer));
}

export function resumesAs(answer: () => Response) {
  server.use(http.post('/api/auth/resume', answer));
}

export function holdNextRead() {
  const held = holdResponse();
  server.use(http.get('/api/auth/session', held.resolver, { once: true }));
  return held.release;
}

export function signedInUntilLogout(username?: string) {
  let revoked = false;
  server.use(
    http.get('/api/auth/session', () =>
      revoked ? anonymousResponse() : sessionResponse(username),
    ),
    http.post('/api/auth/logout', () => {
      revoked = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
}

export function holdAuthenticationLock() {
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const running = navigator.locks.request(
    'emploi-go-authentication',
    {},
    () => held,
  );
  onTestFinished(async () => {
    release();
    await running;
  });
  return release;
}
