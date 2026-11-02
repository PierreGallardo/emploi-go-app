import { afterEach, expect, it, vi } from 'vitest';
import { holdAuthenticationLock } from '@/test/auth';
import {
  AuthenticationCoordinationError,
  AuthenticationError,
} from '../errors';
import { authenticationDeadline, withAuthenticationLock } from './lock';

// Operations here end when their signal aborts, not through MSW: an unanswered abandoned request could reach the real network.

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const useDeadlineClock = () =>
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });

const untilAbandoned = (signal: AbortSignal) =>
  new Promise<never>((_, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), {
      once: true,
    });
  });

const timeout = expect.objectContaining({ reason: 'timeout' });

it('abandonne l’opération après 15 s sous le verrou, avec une erreur de délai', async () => {
  useDeadlineClock();
  const outcome = withAuthenticationLock(({ signal }) =>
    untilAbandoned(signal),
  ).catch((failure: unknown) => failure);

  await vi.advanceTimersByTimeAsync(authenticationDeadline - 1);
  await vi.advanceTimersByTimeAsync(1);

  const error = await outcome;
  expect(error).toBeInstanceOf(AuthenticationCoordinationError);
  expect(error).toEqual(timeout);
});

it('ne rend le verrou à l’opération suivante qu’une fois la précédente abandonnée', async () => {
  useDeadlineClock();
  const events: string[] = [];
  const stuck = withAuthenticationLock(async ({ signal }) => {
    try {
      await untilAbandoned(signal);
    } finally {
      events.push('requête abandonnée');
    }
  }).catch(() => undefined);
  await vi.advanceTimersByTimeAsync(1_000);
  const next = withAuthenticationLock(async () => {
    events.push('opération suivante');
  });

  await vi.advanceTimersByTimeAsync(authenticationDeadline);
  await stuck;
  await next;

  expect(events).toEqual(['requête abandonnée', 'opération suivante']);
});

it('expire l’attente d’une demande qui n’obtient jamais le verrou, sans rien exécuter', async () => {
  useDeadlineClock();
  const release = holdAuthenticationLock();
  const ran = vi.fn();
  await vi.advanceTimersByTimeAsync(authenticationDeadline - 1);
  const waiting = withAuthenticationLock(async () => ran()).catch(
    (failure: unknown) => failure,
  );

  await vi.advanceTimersByTimeAsync(authenticationDeadline);

  expect(await waiting).toEqual(timeout);
  expect(ran).not.toHaveBeenCalled();
  release();
});

it('donne à l’opération un délai complet à partir de l’octroi du verrou, pas du début de l’attente', async () => {
  useDeadlineClock();
  const release = holdAuthenticationLock();
  const outcome = withAuthenticationLock(async ({ signal }) => {
    await new Promise<void>((resolve) => setTimeout(resolve, 14_000));
    return signal.aborted ? 'abandonnée' : 'terminée';
  });

  await vi.advanceTimersByTimeAsync(authenticationDeadline - 1_000);
  release();
  await vi.advanceTimersByTimeAsync(14_000);

  await expect(outcome).resolves.toBe('terminée');
});

it('laisse l’annulation de l’appelant pendant l’attente échouer avec sa raison, sans minuterie restante', async () => {
  useDeadlineClock();
  const release = holdAuthenticationLock();
  const controller = new AbortController();
  const reason = new Error('annulée par l’écran');
  const waiting = withAuthenticationLock(
    async () => 'jamais',
    controller.signal,
  );

  controller.abort(reason);

  await expect(waiting).rejects.toBe(reason);
  release();
  expect(vi.getTimerCount()).toBe(0);
});

it('laisse l’annulation de l’appelant pendant l’opération la couper avec sa raison, sans erreur de délai', async () => {
  useDeadlineClock();
  const controller = new AbortController();
  const reason = new Error('annulée par l’écran');
  const outcome = withAuthenticationLock(
    ({ signal }) => untilAbandoned(signal),
    controller.signal,
  );
  await vi.advanceTimersByTimeAsync(1_000);

  controller.abort(reason);

  await expect(outcome).rejects.toBe(reason);
  expect(vi.getTimerCount()).toBe(0);
});

it('garde une erreur de l’opération qui n’est pas l’abandon du délai, même si le délai est passé', async () => {
  useDeadlineClock();
  const refusal = new AuthenticationError(403, 'CSRF_INVALID');
  const outcome = withAuthenticationLock(async ({ signal }) => {
    await new Promise<void>((resolve) => setTimeout(resolve, 20_000));
    expect(signal.aborted).toBe(true);
    throw refusal;
  }).catch((failure: unknown) => failure);

  await vi.advanceTimersByTimeAsync(20_000);

  expect(await outcome).toBe(refusal);
});

it('refuse d’opérer sans Web Locks, sans délai ni minuterie', async () => {
  useDeadlineClock();
  vi.stubGlobal('navigator', { locks: undefined });
  const operation = vi.fn();

  await expect(withAuthenticationLock(operation)).rejects.toEqual(
    expect.objectContaining({ reason: 'unsupported' }),
  );
  expect(operation).not.toHaveBeenCalled();
  expect(vi.getTimerCount()).toBe(0);
});

it('reconnaît le délai même quand le navigateur abandonne avec une AbortError neuve', async () => {
  useDeadlineClock();
  const outcome = withAuthenticationLock(
    ({ signal }) =>
      new Promise<never>((_, reject) => {
        signal.addEventListener('abort', () =>
          reject(new DOMException('Aborted', 'AbortError')),
        );
      }),
  ).catch((failure: unknown) => failure);

  await vi.advanceTimersByTimeAsync(authenticationDeadline);

  expect(await outcome).toEqual(timeout);
});
