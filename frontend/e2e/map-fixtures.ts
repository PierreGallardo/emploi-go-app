import { expect, type Page } from '@playwright/test';
import { test as accountTest } from './account-fixtures';
import { csrfHeaders } from './browser-fixtures';
import { isolatedDatabase } from './database';

// The frame carries the map's state.
export const mapFrame = (page: Page) => page.locator('[data-map-state]');

/** Fictitious establishments of the map, written in capitals like the State registers do. */
export const bakery = {
  siret: '90000000100012',
  name: 'Le Fournil Fictif',
  stop: 'Gare Fictive',
};
export const street = 'Rue Fictive de la Liberté';

const seed = `
SELECT pg_advisory_xact_lock(4242);
INSERT INTO establishments (
    siret, name, company_name, address, commune_code, longitude, latitude,
    activity_nomenclature, activity_code, company_type, headcount_range, ess, seen_at, visible,
    description)
VALUES
    ('${bakery.siret}', 'LE FOURNIL FICTIF', 'SOCIETE FICTIVE', '1 RUE FICTIVE 21000 DIJON',
     '21231', 5.0273, 47.3233, 'NAF_REV2', '10.71C', 'COMPANY', '02', FALSE, now(), TRUE,
     'Une boulangerie fictive des tests.'),
    ('90000000200012', 'BRASSERIE FICTIVE', 'SOCIETE FICTIVE', '2 RUE FICTIVE 21000 DIJON',
     '21231', 5.0415, 47.3220, 'NAF_REV2', '56.10A', 'COMPANY', '11', TRUE, now(), TRUE,
     NULL)
ON CONFLICT (siret) DO NOTHING;
INSERT INTO transit_lines (name, mode) VALUES ('T9', 'TRAM')
ON CONFLICT (name) DO NOTHING;
WITH stop AS (
    INSERT INTO transit_stops (name, mode, longitude, latitude)
    SELECT '${bakery.stop}', 'TRAM', 5.0270, 47.3235
    WHERE NOT EXISTS (SELECT 1 FROM transit_stops WHERE name = '${bakery.stop}')
    RETURNING id)
INSERT INTO transit_stop_lines (stop_id, line_name) SELECT id, 'T9' FROM stop;
WITH street AS (
    INSERT INTO address_streets (commune_code, name, longitude, latitude)
    VALUES ('21231', '${street}', 5.0410, 47.3221)
    ON CONFLICT (commune_code, name) DO NOTHING
    RETURNING id)
INSERT INTO address_numbers (street_id, label, number, longitude, latitude)
SELECT id, '6', 6, 5.0412, 47.3222 FROM street;
`;

export const test = accountTest.extend<{ mapPage: Page }>({
  mapPage: [
    async ({ page, account }, use) => {
      // Every journey writes the same rows, one at a time: the parallel ones find them in place.
      await (await isolatedDatabase()).sql(seed);
      await page.goto('/');
      await expect(
        page.getByRole('heading', { name: 'Se connecter', exact: true }),
      ).toBeVisible();
      const login = await page.request.post('/api/auth/login', {
        headers: await csrfHeaders(page.request),
        data: {
          identifier: account.username,
          password: account.password,
          rememberMe: true,
        },
      });
      expect(login.status()).toBe(200);
      await page.goto('/');
      await expect(
        page.getByRole('heading', { name: 'Carte', exact: true }),
      ).toBeVisible();
      await expect(mapFrame(page)).toHaveAttribute('data-map-state', 'ready');
      await use(page);
    },
    { timeout: 60_000 },
  ],
});
