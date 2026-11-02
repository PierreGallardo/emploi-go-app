import type { components } from '@/api/schema';
import { weakPasswordMessage } from './password-rule';
import { usernameRule } from './username-rule';

export type ErrorCode =
  components['schemas']['AuthenticationErrorResponse']['code'];

/** Tab coordination failed: no Web Locks (`unsupported`), or the deadline passed while waiting or running (`timeout`). */
export class AuthenticationCoordinationError extends Error {
  readonly reason: 'unsupported' | 'timeout';
  constructor(reason: 'unsupported' | 'timeout' = 'unsupported') {
    super(
      reason === 'timeout'
        ? 'Cette action a pris trop de temps. Vérifie ta connexion puis réessaie.'
        : 'Utilise un navigateur récent et une connexion sécurisée (HTTPS).',
    );
    this.reason = reason;
  }
}

/** Caps `Retry-After`: a larger delay would overflow timers. */
export const maxRetryAfterSeconds = 3600;

/** A link already used: its request may have changed the password, so the sign-in comes first. */
export const usedLinkMessage =
  'Ce lien a déjà servi. Si tu viens de changer ton mot de passe, connecte-toi avec le nouveau.';
export const recentlySentMessage =
  'Un code a déjà été demandé pour cette adresse il y a moins d’une minute. Regarde tes emails.';

const messagesByCode: Partial<Record<ErrorCode, string>> = {
  WEAK_PASSWORD: weakPasswordMessage,
  INVALID_USERNAME: usernameRule,
  USERNAME_UNAVAILABLE: 'Ce pseudo est déjà pris.',
  USERNAME_ALREADY_CHOSEN: 'Ton pseudo est déjà choisi.',
  INVALID_LINK: 'Ce lien est invalide. Demande un nouveau lien.',
  EXPIRED_LINK: 'Ce lien a expiré. Demande un nouveau lien.',
  USED_LINK: usedLinkMessage,
  REPLACED_LINK: 'Ce lien a été remplacé. Utilise le dernier lien reçu.',
  INVALID_CODE: 'Code incorrect. Vérifie le dernier email reçu.',
  CODE_EXPIRED: 'Code expiré. Demande un nouveau code.',
  CODE_REPLACED: 'Ce code a été remplacé. Utilise le dernier email reçu.',
  CODE_USED: 'Code déjà utilisé. Connecte-toi.',
  CODE_ATTEMPTS_EXCEEDED: 'Trop d’essais. Demande un nouveau code.',
  NO_PENDING_REGISTRATION:
    'Ton inscription n’est plus en cours sur cette page. Recommence l’inscription.',
  CODES_BLOCKED:
    'Trop d’essais sur ce code. Aucun nouveau code pour le moment.',
  MAIL_RECENTLY_SENT: recentlySentMessage,
  INVALID_INPUT: 'Vérifie les champs du formulaire.',
  BUSY: 'Le service est très sollicité. Réessaie dans quelques secondes.',
};

/** The instant (ms since epoch) a wait of `seconds` ends. */
export function retryAtAfter(seconds: number) {
  return Date.now() + Math.min(seconds, maxRetryAfterSeconds) * 1000;
}

function messageOf(status: number, code: string | undefined) {
  if (code !== undefined && Object.hasOwn(messagesByCode, code))
    return messagesByCode[code as ErrorCode]!;
  if (status === 429) return 'Trop de tentatives. Réessaie plus tard.';
  if (status === 503)
    return 'Connexion temporairement indisponible. Réessaie plus tard.';
  if (code === 'CSRF_INVALID')
    return 'Ta session a changé. Réessaie pour continuer.';
  if (status === 401) return 'Identifiant ou mot de passe incorrect.';
  return 'Impossible de terminer cette action. Réessaie.';
}

export class AuthenticationError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  /** Instant (ms since epoch) before which the API asked for no new attempt. */
  readonly retryAt: number | undefined;
  constructor(status: number, code?: string, waitSeconds?: number) {
    super(messageOf(status, code));
    this.status = status;
    this.code = code;
    this.retryAt =
      waitSeconds === undefined ? undefined : retryAtAfter(waitSeconds);
  }
}
export function hasCode(error: unknown, codes: readonly ErrorCode[]) {
  return (
    error instanceof AuthenticationError &&
    codes.some((code) => code === error.code)
  );
}
/** The wait when the API limits a reconnection without a usable `Retry-After` (a proxy may drop it). */
export const defaultRetryAfterSeconds = 60;
/** Seconds before the next attempt, from `Retry-After`; without a usable value only a 429 waits (`defaultRetryAfterSeconds`). */
export function retryAfterSeconds(response: Response) {
  const value = response.headers.get('Retry-After');
  if (value !== null && /^\d{1,9}$/.test(value.trim()) && Number(value) > 0)
    return Number(value);
  return response.status === 429 ? defaultRetryAfterSeconds : undefined;
}

export class SessionUnconfirmedError extends Error {
  constructor(cause: unknown) {
    super(authenticationMessage(cause), { cause });
  }
}
export function retryAtOf(error: unknown) {
  const source = error instanceof SessionUnconfirmedError ? error.cause : error;
  return source instanceof AuthenticationError ? source.retryAt : undefined;
}
/** The reconnection was refused for a while: the visitor can still use the password. */
export function isReconnectionLimited(error: unknown) {
  return (
    error instanceof SessionUnconfirmedError &&
    error.cause instanceof AuthenticationError &&
    error.cause.status === 429
  );
}
export function authenticationMessage(error: unknown) {
  return error instanceof AuthenticationError ||
    error instanceof AuthenticationCoordinationError ||
    error instanceof SessionUnconfirmedError
    ? error.message
    : 'Connexion impossible pour le moment. Vérifie ta connexion puis réessaie.';
}
/** A 503 from an outage or a failed email, not the limit of simultaneous computations (`BUSY`, a few seconds). */
function isOutage(error: unknown) {
  return (
    error instanceof AuthenticationError &&
    error.status === 503 &&
    error.code !== 'BUSY'
  );
}
/** The refusal of a signup: the code of an email just sent only works on the page that asked for it. */
export function signupMessage(error: unknown) {
  if (hasCode(error, ['MAIL_RECENTLY_SENT']))
    return 'Un code a déjà été demandé pour cette adresse il y a moins d’une minute. Saisis-le sur la page qui l’a demandé, ou recommence après l’attente.';
  return registrationMessage(error);
}
export function registrationMessage(error: unknown) {
  if (
    error instanceof AuthenticationError &&
    error.code === 'ACCOUNT_ALREADY_EXISTS'
  )
    return 'Un compte utilise déjà cette adresse. Connecte-toi avec ton mot de passe ou Google.';
  return isOutage(error)
    ? 'Action temporairement indisponible. Attends une minute puis réessaie.'
    : authenticationMessage(error);
}
/** The hour without new codes after too many failures: it stops the entry and the request of a code alike. */
export function isCodeBlock(error: unknown) {
  return hasCode(error, ['CODES_BLOCKED']);
}
/** The end of the hour without new codes, for the entry and the request of a code alike. */
export const codeBlockHint = (time: string) => `Réessaie à ${time}.`;
export function registrationCodeMessage(error: unknown) {
  if (isCodeBlock(error)) return 'Trop d’essais.';
  if (
    error instanceof AuthenticationError &&
    error.code === 'ACCOUNT_ALREADY_EXISTS'
  )
    return 'Tu as déjà un compte. Connecte-toi.';
  return registrationMessage(error);
}
export function recoveryMessage(error: unknown) {
  if (error instanceof AuthenticationError) {
    if (error.code === 'EXPIRED_LINK')
      return 'Ce lien a expiré. Demande un nouveau lien.';
    if (error.code === 'USED_LINK') return usedLinkMessage;
    if (isOutage(error))
      return 'Action temporairement indisponible. Attends une minute puis réessaie.';
    return authenticationMessage(error);
  }
  if (error instanceof AuthenticationCoordinationError) return error.message;
  return 'Impossible de confirmer la demande. Vérifie ta connexion puis réessaie.';
}
