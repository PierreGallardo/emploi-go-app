import { api } from '@/api/client';
import type { components } from '@/api/schema';
import { browserCookie, hasReconnectionIndicator } from './session/cookies';
import { startDeadline, type Deadline } from '@/lib/deadline';
import {
  AuthenticationError,
  SessionUnconfirmedError,
  retryAfterSeconds,
  retryAtAfter,
  retryAtOf,
} from './errors';
import {
  authenticationDeadline,
  interrupted,
  withAuthenticationLock,
} from './session/lock';

export type Session = components['schemas']['SessionResponse'];
type Login = components['schemas']['LoginRequest'];
export type Registration = components['schemas']['RegistrationRequest'];
export type ResetResult = components['schemas']['PasswordResetResponse'];
export type GoogleStatus = components['schemas']['GoogleStatusResponse'];
export type GoogleLinkResult = components['schemas']['GoogleLinkResponse'];

type Outcome<D> = {
  data?: D;
  error?: { code?: string };
  response: Response;
};

/** A 429 carries the wait the screen reads from the error. */
function accepted<D>({ data, error, response }: Outcome<D>): D | undefined {
  if (!response.ok)
    throw new AuthenticationError(
      response.status,
      error?.code,
      response.status === 429 ? retryAfterSeconds(response) : undefined,
    );
  return data;
}

type WriteInit = {
  params: { header: { 'X-CSRF-TOKEN': string } };
  cache: 'no-store';
  signal: AbortSignal;
};

/** A write under the authentication lock, sent once and never retried. */
async function write<D>(
  send: (init: WriteInit) => Promise<Outcome<D>>,
): Promise<void>;
async function write<D, T>(
  send: (init: WriteInit) => Promise<Outcome<D>>,
  accept: (data: D | undefined, response: Response) => T,
): Promise<T>;
async function write<D, T>(
  send: (init: WriteInit) => Promise<Outcome<D>>,
  accept?: (data: D | undefined, response: Response) => T,
) {
  return withAuthenticationLock(async ({ signal }) => {
    const header = await csrfHeaders(signal);
    const outcome = await send({
      params: { header },
      cache: 'no-store',
      signal,
    });
    const data = accepted(outcome);
    return accept?.(data, outcome.response);
  });
}

/** The instant (ms since epoch) before which the address gets no other email, when the API tells it. */
function resendAtOf(_data: unknown, response: Response) {
  const wait = retryAfterSeconds(response);
  return wait === undefined ? undefined : retryAtAfter(wait);
}

/** A session whose account may not have chosen its username yet (`null`). */
function requireSession(data: Session | undefined): Session {
  if (
    !data ||
    typeof data.accountId !== 'string' ||
    !data.accountId ||
    (data.username !== null &&
      (typeof data.username !== 'string' || !data.username.trim()))
  )
    throw new Error('Réponse de connexion invalide.');
  return data;
}

/** The CSRF token is the cookie the API gave this browser: the header must carry its value. */
async function csrfHeaders(signal?: AbortSignal) {
  let token = browserCookie('csrf');
  if (!token) {
    // First visit, or cookie deleted: the API sets it without creating a session.
    accepted(await api.GET('/api/auth/csrf', { signal, cache: 'no-store' }));
    token = browserCookie('csrf');
  }
  if (!token) throw new Error('Protection de connexion indisponible.');
  return { 'X-CSRF-TOKEN': token };
}

function googleAuthorizationUrl(
  data: components['schemas']['GoogleStartedResponse'] | undefined,
): string {
  if (!data || typeof data.authorizationUri !== 'string')
    throw new Error('Parcours Google indisponible.');
  const target = new URL(data.authorizationUri);
  const loopback = ['localhost', '127.0.0.1'];
  if (
    !(
      target.protocol === 'https:' && target.hostname === 'accounts.google.com'
    ) &&
    !(
      loopback.includes(window.location.hostname) &&
      loopback.includes(target.hostname) &&
      target.protocol === 'http:'
    )
  )
    throw new Error('Parcours Google indisponible.');
  return target.href;
}
export async function startGoogle(): Promise<string> {
  return write(
    (init) => api.POST('/api/auth/google/start', init),
    googleAuthorizationUrl,
  );
}
export async function readGoogleStatus({
  signal,
}: { signal?: AbortSignal } = {}): Promise<GoogleStatus> {
  return withAuthenticationLock(async (deadline) => {
    const data = accepted(
      await api.GET('/api/auth/google/status', {
        signal: deadline.signal,
        cache: 'no-store',
      }),
    );
    if (
      !data ||
      typeof data.pending !== 'boolean' ||
      (data.pending &&
        (typeof data.flowId !== 'string' ||
          ![
            'EMAIL_CONFIRMATION_REQUIRED',
            'ACCOUNT_ACCESS_REQUIRED',
            'CHOOSE_USERNAME',
            'CONFIRM_LINK',
          ].includes(data.stage ?? '')))
    )
      throw new Error('Étape Google indisponible.');
    return data;
  }, signal);
}
export async function accessGoogleAccount(
  values: components['schemas']['GoogleAccessRequest'],
): Promise<Session> {
  return write(
    (init) => api.POST('/api/auth/google/access', { ...init, body: values }),
    requireSession,
  );
}
export async function signupGoogle(
  values: components['schemas']['GoogleSignupRequest'],
): Promise<Session> {
  return write(
    (init) => api.POST('/api/auth/google/signup', { ...init, body: values }),
    requireSession,
  );
}
function requireGoogleLink(
  data: GoogleLinkResult | undefined,
): GoogleLinkResult {
  if (!data || data.changed !== true)
    throw new Error('Résultat de la liaison non confirmé.');
  return data;
}
export async function linkGoogle(
  values: components['schemas']['GoogleLinkRequest'],
): Promise<GoogleLinkResult> {
  return write(
    (init) => api.POST('/api/auth/google/link', { ...init, body: values }),
    requireGoogleLink,
  );
}
export async function requestGoogleEmail(
  values: components['schemas']['GoogleEmailRequest'],
): Promise<void> {
  return write((init) =>
    api.POST('/api/auth/google/email', { ...init, body: values }),
  );
}
export async function confirmGoogleEmail(
  values: components['schemas']['GoogleConfirmationRequest'],
): Promise<void> {
  return write((init) =>
    api.POST('/api/auth/google/email/confirm', { ...init, body: values }),
  );
}

/** `resumeLimit` is read once the lock is held, so a read waiting behind another sees its refusal; the rotation keeps its own deadline, never shorter, since an abandoned response may hold the only copy of the new secret. */
export async function readSession({
  signal,
  resumeLimit,
}: {
  signal?: AbortSignal;
  resumeLimit?: () => SessionUnconfirmedError | undefined;
} = {}): Promise<Session | null> {
  return withAuthenticationLock(async (deadline) => {
    const result = await api.GET('/api/auth/session', {
      signal: deadline.signal,
      cache: 'no-store',
    });
    if (result.response.ok) return requireSession(result.data);
    if (result.response.status !== 401)
      throw new AuthenticationError(result.response.status, result.error?.code);
    // Without the indicator the API has no reconnection to offer: a visitor stays anonymous, with no other request.
    if (!hasReconnectionIndicator()) return null;
    const limit = resumeLimit?.();
    const waitUntil = retryAtOf(limit);
    if (limit && waitUntil !== undefined && Date.now() < waitUntil) throw limit;
    let rotation: Deadline | undefined;
    try {
      const headers = await csrfHeaders(deadline.signal);
      // The rotation is a mutation: unmounting the reading view must not abort it, only its own deadline does.
      rotation = startDeadline(authenticationDeadline);
      const resumed = await api.POST('/api/auth/resume', {
        params: { header: headers },
        cache: 'no-store',
        signal: rotation.signal,
      });
      if (resumed.response.status === 401) return null;
      if (!resumed.response.ok)
        throw new AuthenticationError(
          resumed.response.status,
          resumed.error?.code,
          retryAfterSeconds(resumed.response),
        );
      return requireSession(resumed.data);
    } catch (error) {
      // The API refused the session: a failing reconnection leaves it unconfirmed, not merely unread.
      throw new SessionUnconfirmedError(
        interrupted(rotation?.expired ? rotation : deadline, error),
      );
    } finally {
      rotation?.release();
    }
  }, signal);
}

export async function login(values: Login): Promise<Session> {
  return write(
    (init) => api.POST('/api/auth/login', { ...init, body: values }),
    requireSession,
  );
}

export async function logout(): Promise<void> {
  return write((init) => api.POST('/api/auth/logout', init));
}

/** @returns the registration kept for this page, and when the address may get another email */
export async function signup(
  values: Registration,
): Promise<{ registration: string; resendAt: number | undefined }> {
  return write(
    (init) => api.POST('/api/auth/signup', { ...init, body: values }),
    (data, response) => {
      // The identifier the code screen of this page sends back.
      const registration = response.headers.get('Registration-Id');
      if (!registration) throw new Error('Inscription indisponible.');
      return { registration, resendAt: resendAtOf(data, response) };
    },
  );
}
/** A new code for the registration of this page. @returns when the address may get another email */
export async function resendConfirmation(
  values: components['schemas']['ResendConfirmationRequest'],
): Promise<number | undefined> {
  return write(
    (init) => api.POST('/api/auth/signup/resend', { ...init, body: values }),
    resendAtOf,
  );
}
export async function confirmRegistration(
  values: components['schemas']['ConfirmationRequest'],
): Promise<Session> {
  return write(
    (init) => api.POST('/api/auth/signup/confirm', { ...init, body: values }),
    requireSession,
  );
}

export async function chooseUsername(
  values: components['schemas']['UsernameRequest'],
): Promise<Session> {
  return write(
    (init) => api.POST('/api/auth/username', { ...init, body: values }),
    requireSession,
  );
}

export async function requestRecovery(
  values: components['schemas']['RecoveryRequest'],
): Promise<number | undefined> {
  return write(
    (init) => api.POST('/api/auth/password/forgot', { ...init, body: values }),
    resendAtOf,
  );
}
function requireReset(data: ResetResult | undefined): ResetResult {
  if (!data || data.changed !== true)
    throw new Error('Résultat du changement non confirmé.');
  return data;
}
export async function resetPassword(
  values: components['schemas']['PasswordResetRequest'],
): Promise<ResetResult> {
  return write(
    (init) => api.POST('/api/auth/password/reset', { ...init, body: values }),
    requireReset,
  );
}
