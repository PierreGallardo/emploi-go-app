import { http, HttpResponse } from 'msw';

/** A session of the API; unless `accountId` says otherwise, each username is its own account. `null`: an account that has not chosen its username yet. */
export function testSession(
  username: string | null = 'jeune_fictif',
  accountId = `compte-${username ?? 'sans-pseudo'}`,
) {
  return { accountId, username };
}

export function sessionResponse(username?: string | null, accountId?: string) {
  return HttpResponse.json(testSession(username, accountId));
}

export function problemResponse(
  status: number,
  code?: string,
  headers?: HeadersInit,
) {
  return code
    ? HttpResponse.json(
        { code, message: 'Détail privé du serveur.' },
        { status, headers },
      )
    : new HttpResponse(null, { status, headers });
}

export function anonymousResponse() {
  return problemResponse(401);
}

/** The identifier of the registration that the API gives the page of an accepted signup. */
export const testRegistration = 'inscription-fictive';

export function signupAcceptedResponse(headers?: Record<string, string>) {
  return new HttpResponse(null, {
    status: 202,
    headers: { 'Registration-Id': testRegistration, ...headers },
  });
}

export function rateLimitedResponse(retryAfter: number) {
  return problemResponse(429, 'RATE_LIMITED', {
    'Retry-After': String(retryAfter),
  });
}

export const authHandlers = [
  // The browser stores the `Set-Cookie` of the API; Node's `fetch` does not, so the handler plays that part.
  http.get('/api/auth/csrf', () => {
    document.cookie = 'XSRF-TOKEN=csrf-serveur; Path=/';
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('/api/auth/session', () => sessionResponse()),
  http.post('/api/auth/login', () => sessionResponse()),
  http.post('/api/auth/logout', () => new HttpResponse(null, { status: 204 })),
  http.post('/api/auth/resume', () => sessionResponse()),
];
