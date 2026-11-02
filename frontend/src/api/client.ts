import createClient from 'openapi-fetch';
import type { paths } from './schema';
import { noteSender, replayAfterReconnection } from './unauthorized';

export const api = createClient<paths>({
  baseUrl: '',
  credentials: 'same-origin',
});

// The 401 replay relies on two `openapi-fetch` 0.17 behaviours: `onResponse` may return a new `Response`, and `options.fetch` skips this middleware (see `unauthorized.ts`).
// Source: https://openapi-ts.dev/openapi-fetch/middleware-auth and `dist/index.mjs` of the package.
api.use({
  onRequest: ({ request }) => {
    noteSender(request);
  },
  onResponse: ({ request, response, options }) =>
    replayAfterReconnection({ request, response, send: options.fetch }),
});
