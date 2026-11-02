import { expect } from '@playwright/test';
import { expectAccessible } from './browser-fixtures';
import { bakery, mapFrame, test } from './map-fixtures';

// The journeys sign in with a password: nothing of them is recorded.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

test('une ligne de la liste ouvre la fiche, avec l’arrêt Divia le plus proche', async ({
  mapPage: page,
}) => {
  await page.getByRole('button', { name: new RegExp(bakery.name) }).click();

  await expect(
    page.getByRole('heading', { name: bakery.name, level: 2, exact: true }),
  ).toBeVisible();
  await expect(page.getByText(`Arrêt Divia ${bakery.stop}, à`)).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`etablissement=${bakery.siret}`));
  await expect(mapFrame(page)).toHaveAttribute('data-map-motion', 'idle');
  await expectAccessible(page);
});

test('la vraie carte dessine les établissements que l’API lui donne', async ({
  mapPage: page,
}) => {
  await expect(mapFrame(page)).toHaveAttribute('data-map-motion', 'idle');

  await expect(mapFrame(page)).toHaveAttribute('data-map-points', /^[1-9]/);
});

test('ouvrir une fiche par son adresse puis revenir en arrière la ferme', async ({
  mapPage: page,
}) => {
  const heading = page.getByRole('heading', {
    name: bakery.name,
    level: 2,
    exact: true,
  });
  await page.goto(`/?etablissement=${bakery.siret}`);
  await expect(heading).toBeVisible();
  await page.reload();
  await expect(heading).toBeVisible();

  await page.goBack();

  await expect(heading).toBeHidden();
  await expect(page).toHaveURL((url) => !url.searchParams.has('etablissement'));
});
