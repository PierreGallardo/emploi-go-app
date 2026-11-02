import { expect } from '@playwright/test';
import { expectAccessible, test } from './browser-fixtures';

test('l’accueil protégé conduit à la connexion accessible sans session', async ({
  page,
}) => {
  await page.goto('/');
  await expect(
    page.getByRole('heading', { name: 'Se connecter', exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL((url) => url.pathname === '/connexion');
  await expectAccessible(page);
});

test('une adresse inconnue permet de revenir à l’accueil', async ({ page }) => {
  await page.goto('/page-inconnue');
  await expect(
    page.getByRole('heading', { name: 'Page introuvable', exact: true }),
  ).toBeVisible();
  await page
    .getByRole('link', { name: 'Revenir à l’accueil', exact: true })
    .click();
  await expect(
    page.getByRole('heading', { name: 'Se connecter', exact: true }),
  ).toBeVisible();
});
