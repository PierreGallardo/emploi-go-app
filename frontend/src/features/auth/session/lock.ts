import { AuthenticationCoordinationError } from '../errors';
import { startDeadline, type Deadline } from '@/lib/deadline';

/** Longest wait for the lock, then the longest run under it, so a stalled connection cannot hold it for every tab; a reconnecting read may hold it twice as long (see `readSession`). */
export const authenticationDeadline = 15_000;

/** After the deadline, an abort is a coordination timeout even when the browser rejects with a new `AbortError` instead of the signal's reason. */
export function interrupted(deadline: Deadline | undefined, error: unknown) {
  return deadline?.expired &&
    (error === deadline.signal.reason ||
      (error instanceof DOMException && error.name === 'AbortError'))
    ? new AuthenticationCoordinationError('timeout')
    : error;
}

/** `operation` must pass `deadline.signal` to its requests so none writes cookies after the lock is released; on timeout the cookies may still have changed. */
export async function withAuthenticationLock<T>(
  operation: (deadline: Deadline) => Promise<T>,
  signal?: AbortSignal,
): Promise<T> {
  if (!navigator.locks) throw new AuthenticationCoordinationError();
  const waiting = startDeadline(authenticationDeadline, signal);
  let running: Deadline | undefined;
  try {
    return await navigator.locks.request(
      'emploi-go-authentication',
      { signal: waiting.signal },
      async () => {
        // Granted: the deadline of the wait is over, and the operation has a full one of its own.
        waiting.release();
        running = startDeadline(authenticationDeadline, signal);
        try {
          return await operation(running);
        } finally {
          running.release();
        }
      },
    );
  } catch (error) {
    throw interrupted(running ?? waiting, error);
  } finally {
    waiting.release();
  }
}
