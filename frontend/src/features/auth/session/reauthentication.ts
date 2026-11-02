import { useLayoutEffect } from 'react';
import {
  CancelledError,
  hashKey,
  type QueryClient,
} from '@tanstack/react-query';
import { onUnauthorized } from '@/api/unauthorized';
import type { Session } from '../api';
import { sessionKey, sessionQueryOptions } from '../queries';
import { accountKey } from './account-change';

async function readThroughCache(client: QueryClient) {
  // Read from the cache, so that a read this call joined counts too.
  let answered = false;
  const sessionHash = hashKey(sessionKey);
  const stop = client.getQueryCache().subscribe((event) => {
    if (
      event.type === 'updated' &&
      event.action.type === 'success' &&
      !event.action.manual &&
      event.query.queryHash === sessionHash
    )
      answered = true;
  });
  try {
    const session = await client.fetchQuery({
      ...sessionQueryOptions(),
      staleTime: 0,
    });
    return { answered, session };
  } finally {
    stop();
  }
}

/** A joined read can answer with a session older than the event that asked for this read, so it is read once more; `answered` stays false when the read was cancelled. */
export async function readFreshSession(client: QueryClient) {
  const joining = client.isFetching({ queryKey: sessionKey }) > 0;
  const first = await readThroughCache(client);
  if (!first.answered || !joining) return first;
  return readThroughCache(client);
}

/** Reads the session after a 401; the one reconnection happens inside that read, under the lock. */
export async function reconnectSession(client: QueryClient) {
  try {
    const { answered, session } = await readFreshSession(client);
    return answered && session ? accountKey(session) : undefined;
  } catch (error) {
    if (error instanceof CancelledError) return undefined;
    throw error;
  }
}

/** Installed in a layout effect: it runs before the passive effects where screens start their reads, so no read can leave without the 401 handler. */
export function useSessionRecovery(client: QueryClient) {
  useLayoutEffect(
    () =>
      onUnauthorized({
        account: () => {
          const session = client.getQueryData<Session | null>(sessionKey);
          return session ? accountKey(session) : undefined;
        },
        reconnect: () => reconnectSession(client),
      }),
    [client],
  );
}
