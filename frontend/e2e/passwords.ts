import { randomUUID } from 'node:crypto';

/** Spaces and an emoji on purpose: the password rule counts them as special characters. */
export function freshPassword() {
  return `${randomUUID()} Phrase fictive 🔑`;
}
