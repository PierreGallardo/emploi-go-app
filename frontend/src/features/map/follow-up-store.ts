import { z } from 'zod';
import { followUpActionSchema, type FollowUpAction } from './follow-up-schemas';

// Test data of the acceptance environment: kept in this browser only, one journal per establishment.
const keyOf = (siret: string) => `emploi-go.follow-up.${siret}`;
const journalSchema = z.array(z.unknown());

const timeOf = (action: FollowUpAction) =>
  action.type === 'INTERVIEW' ? (action.time ?? '') : '';

/** Most recent first (date, then time); on the same moment, the order already kept. */
export const sortActions = (actions: FollowUpAction[]) =>
  [...actions].sort(
    (a, b) =>
      b.date.localeCompare(a.date) || timeOf(b).localeCompare(timeOf(a)),
  );

/** The journal of an establishment; an entry that does not match the format is left out, the others are kept. */
export function readActions(siret: string): FollowUpAction[] {
  try {
    const raw = localStorage.getItem(keyOf(siret));
    const parsed = journalSchema.safeParse(raw ? JSON.parse(raw) : []);
    if (!parsed.success) return [];
    return parsed.data.flatMap((entry) => {
      const action = followUpActionSchema.safeParse(entry);
      return action.success ? [action.data] : [];
    });
  } catch {
    return [];
  }
}

/** Throws when the browser refuses the write (full or blocked storage). */
export function writeActions(siret: string, actions: FollowUpAction[]) {
  localStorage.setItem(keyOf(siret), JSON.stringify(sortActions(actions)));
}
