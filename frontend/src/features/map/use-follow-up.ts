import { useState } from 'react';
import { readActions, writeActions } from './follow-up-store';
import type { FollowUpAction, FollowUpBody } from './follow-up-schemas';

/**
 * The journal of one establishment in the browser, and its writes. A write starts from what the browser holds, so that another tab is not overwritten;
 * it answers whether it was kept.
 */
export function useFollowUp(siret: string) {
  const [actions, setActions] = useState(() => readActions(siret));
  const [failed, setFailed] = useState(false);
  const change = (next: (current: FollowUpAction[]) => FollowUpAction[]) => {
    try {
      writeActions(siret, next(readActions(siret)));
      setActions(readActions(siret));
      setFailed(false);
      return true;
    } catch {
      setFailed(true);
      return false;
    }
  };
  return {
    actions,
    failed,
    clearFailure: () => setFailed(false),
    add: (body: FollowUpBody) =>
      change((current) => [{ ...body, id: crypto.randomUUID() }, ...current]),
    update: (id: string, body: FollowUpBody) =>
      change((current) =>
        current.map((action) => (action.id === id ? { ...body, id } : action)),
      ),
    remove: (id: string) =>
      change((current) => current.filter((action) => action.id !== id)),
  };
}
