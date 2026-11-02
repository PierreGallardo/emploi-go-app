import { randomUUID } from 'node:crypto';
import { test as base } from './browser-fixtures';
import { isolatedDatabase } from './database';

/** A username within the rule of the API (at most 32 characters, no hyphen), unique by its UUID. */
export function usernameFor(id: string) {
  return `jeune_${id.replaceAll('-', '').slice(0, 24)}`;
}

export const test = base.extend<{
  account: { username: string; email: string; password: string };
  // Journeys that create an account through the UI declare it here: it is deleted even when they fail.
  createdAccounts: { track: (...keys: string[]) => void };
}>({
  account: [
    // Each test, retry and repetition gets a distinct identity and its own quota.
    // eslint-disable-next-line no-empty-pattern
    async ({}, use) => {
      const password = process.env.E2E_AUTH_PASSWORD;
      if (!password)
        throw new Error(
          'Lancer via npm run test:e2e dans l’environnement isolé.',
        );
      const database = await isolatedDatabase();
      const username = usernameFor(randomUUID());
      const email = `${username}@example.invalid`;
      const inserted = await database.sql(
        `INSERT INTO auth_accounts (username, email_key, password_hash, email_verified, enabled, role) SELECT '${username}', '${email}', password_hash, TRUE, TRUE, 'JEUNE' FROM auth_accounts WHERE username = 'jeune_demo' RETURNING username`,
      );
      if (!inserted.includes(username))
        throw new Error('Le compte temporaire n’a pas pu être préparé.');
      try {
        await use({ username, email, password });
      } finally {
        await database.sql(
          `DELETE FROM auth_accounts WHERE username = '${username}'`,
        );
      }
    },
    { timeout: 60_000 },
  ],
  createdAccounts: [
    // eslint-disable-next-line no-empty-pattern
    async ({}, use) => {
      const keys = new Set<string>();
      try {
        await use({
          track: (...values) =>
            values.forEach((value) => {
              // The keys go into SQL: only the identities the tests generate are accepted.
              if (!/^[a-z0-9@._-]+$/.test(value))
                throw new Error(`Clé de compte non prévue : ${value}`);
              keys.add(value);
            }),
        });
      } finally {
        if (keys.size > 0) {
          const database = await isolatedDatabase();
          const list = [...keys].map((key) => `'${key}'`).join(', ');
          // A registration the UI left pending keeps the address and the username until it expires.
          await database.sql(
            `DELETE FROM auth_registrations WHERE email_key IN (${list});
             DELETE FROM auth_accounts WHERE username IN (${list}) OR email_key IN (${list});`,
          );
        }
      }
    },
    { timeout: 60_000 },
  ],
});
