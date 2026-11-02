import { useLayoutEffect } from 'react';
import { hashKey, type QueryClient } from '@tanstack/react-query';
import type { Session } from '../api';
import { sessionKey } from '../queries';

/** The account of a session, even one that has no username yet. */
export const accountKey = (session: Session) => session.accountId;

const accountOf = (session: Session | null | undefined) =>
  session === undefined
    ? undefined
    : session === null
      ? null
      : accountKey(session);

/** Only an API answer showing another account (or none) triggers the reset: a manual write has already emptied the cache, and mutations in flight are kept. */
export function watchAccountChange(client: QueryClient) {
  const cache = client.getQueryCache();
  const sessionHash = hashKey(sessionKey);
  const others = (query: { queryHash: string }) =>
    query.queryHash !== sessionHash;
  // Not a copy of the cache: the session query is dropped once no screen reads it (`gcTime: 0`), so only this value remembers whose data the cache holds.
  let account = accountOf(client.getQueryData<Session | null>(sessionKey));
  return cache.subscribe((event) => {
    // An empty cache holds the data of nobody: the next answer is a first one, not a change.
    if (event.type === 'removed') {
      if (cache.getAll().length === 0) account = undefined;
      return;
    }
    if (
      event.type !== 'updated' ||
      event.action.type !== 'success' ||
      event.query.queryHash !== sessionHash
    )
      return;
    const former = account;
    account = accountOf(event.query.state.data as Session | null | undefined);
    if (event.action.manual || former === undefined || former === account)
      return;
    // Keep the default `revert`: a cancellation that notifies observers would render under the old account's key.
    void client.cancelQueries({ predicate: others });
    cache
      .findAll({ predicate: others })
      .forEach((query) => cache.remove(query));
  });
}

/** Installs the watch before the passive effects where the queries of the screens start. */
export function useAccountChange(client: QueryClient) {
  useLayoutEffect(() => watchAccountChange(client), [client]);
}
