import { useLayoutEffect } from 'react';
import { hashKey, type QueryClient } from '@tanstack/react-query';
import { readFreshSession } from './reauthentication';
import { sessionKey } from '../queries';
import { onSessionChanged } from './session-signal';

/** Never throws and forgets nothing (`account-change.ts` does that); resolves to `false` when the read was cancelled and no write set the session. */
async function syncSession(client: QueryClient) {
  // A login of this tab that finishes while the read waits for the lock cancels it, then answers the signal itself.
  let written = false;
  const sessionHash = hashKey(sessionKey);
  const stop = client.getQueryCache().subscribe((event) => {
    if (
      event.query.queryHash === sessionHash &&
      event.type === 'updated' &&
      event.action.type === 'success' &&
      event.action.manual
    )
      written = true;
  });
  try {
    const { answered } = await readFreshSession(client);
    if (!answered) return written;
  } catch {
    // A failed read leaves its error in the cache, where the screens show it.
  } finally {
    stop();
  }
  return true;
}

/** One synchronisation at a time: a signal that arrives meanwhile asks for one more read; a read cancelled without a session write is read once more. */
export function createSessionSynchronizer(client: QueryClient) {
  let running: Promise<void> | undefined;
  let again = false;
  return () => {
    if (running) {
      again = true;
      return running;
    }
    running = (async () => {
      let retries = 1;
      do {
        again = false;
        if (!(await syncSession(client)) && retries-- > 0) again = true;
      } while (again);
    })().finally(() => {
      running = undefined;
    });
    return running;
  };
}

/** Also reads when a page restored from the back-forward cache comes back, since it may have missed signals. */
export function useSessionSync(client: QueryClient) {
  useLayoutEffect(() => {
    const synchronize = createSessionSynchronizer(client);
    const stopListening = onSessionChanged(() => void synchronize());
    const restored = (event: PageTransitionEvent) => {
      if (event.persisted) void synchronize();
    };
    window.addEventListener('pageshow', restored);
    return () => {
      window.removeEventListener('pageshow', restored);
      stopListening();
    };
  }, [client]);
}
