import { http, HttpResponse } from 'msw';
import { onTestFinished } from 'vitest';
import { markDecided, server } from './server';

export type ReceivedRequest = {
  method: string;
  path: string;
  search: URLSearchParams;
  headers: Headers;
};

/** A request body is read in the test's own answer. */
export function recordRequests() {
  const received: ReceivedRequest[] = [];
  const listener = ({ request }: { request: Request }) => {
    const url = new URL(request.url);
    received.push({
      method: request.method,
      path: url.pathname,
      search: url.searchParams,
      headers: request.headers,
    });
  };
  server.events.on('request:start', listener);
  onTestFinished(() => {
    server.events.removeListener('request:start', listener);
  });
  return {
    received,
    of: (method: string, path: string) =>
      received.filter((sent) => sent.method === method && sent.path === path),
    routes: () => received.map(({ method, path }) => `${method} ${path}`),
  };
}

/** Keeps the body of each request sent to `path`; `answers` gives the response to each one in turn, the last one for the rest. */
export function postTo(path: string, ...answers: Array<() => Response>) {
  const bodies: unknown[] = [];
  server.use(
    http.post(path, async ({ request }) => {
      bodies.push(await request.json());
      return answers[Math.min(bodies.length, answers.length) - 1]!();
    }),
  );
  return bodies;
}

/** Do not abort a held request: with MSW 3.0.3, the next request may go to the real network. */
export function holdResponse() {
  let release!: (response: Response) => void;
  const gate = new Promise<Response>((resolve) => {
    release = resolve;
  });
  onTestFinished(() => release(HttpResponse.error()));
  return {
    resolver: ({ requestId }: { requestId: string }) => {
      markDecided(requestId);
      return gate;
    },
    release,
  };
}

type Stall = {
  onRequest: (options: { request: Request }) => Promise<never> | undefined;
};

/** Stalled requests never reach MSW, so `recordRequests` does not list them: count them with `started()`, and use this only for abandon tests. */
export function stallRequests(
  client: { use: (stall: Stall) => void; eject: (stall: Stall) => void },
  method: string,
  path: string,
  { times = Infinity }: { times?: number } = {},
) {
  let started = 0;
  const stall: Stall = {
    onRequest: ({ request }) => {
      if (
        request.method !== method ||
        new URL(request.url).pathname !== path ||
        started >= times
      )
        return undefined;
      started += 1;
      return new Promise<never>((_, reject) => {
        request.signal.addEventListener(
          'abort',
          () => reject(request.signal.reason),
          { once: true },
        );
      });
    },
  };
  client.use(stall);
  onTestFinished(() => client.eject(stall));
  return { started: () => started };
}
