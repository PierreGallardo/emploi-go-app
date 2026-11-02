import { expect, type Page } from '@playwright/test';
import { test } from './account-fixtures';
import { recordRequests } from './browser-fixtures';

// Network traces, screenshots and videos would retain passwords and cookies.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

const atConnection = (url: URL) => /\/connexion$/.test(url.pathname);
const atHome = (url: URL) =>
  url.pathname === '/' && url.search === '' && url.hash === '';

/** The application listens to the signal only once the session has been read. */
async function ready(page: Page, button: string) {
  await expect(
    page.getByRole('button', { name: button, exact: true }),
  ).toBeEnabled();
}

// A reload resets the page: the marker shows that the other tab was never reloaded.
async function mark(page: Page) {
  await page.evaluate(() => {
    document.documentElement.dataset.marker = 'kept';
  });
}
const marker = (page: Page) =>
  page.evaluate(() => document.documentElement.dataset.marker);

// The second tab learns only from the signal: without it, it would keep its screen for a minute (`sessionStaleTime`).

test('la déconnexion dans un onglet ramène l’autre à la connexion, sans recharger', async ({
  page,
  context,
  account,
}) => {
  await page.goto('/connexion');
  await page
    .getByLabel('Email ou pseudo', { exact: true })
    .fill(account.username);
  await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page).toHaveURL(atHome);
  const other = await context.newPage();
  await other.goto('/');
  // « Mon compte » is on the shell while the session is unknown, but disabled.
  await ready(other, 'Mon compte');
  await mark(other);
  const requests = recordRequests(other);

  await page.getByRole('button', { name: 'Mon compte', exact: true }).click();
  await page
    .getByRole('menuitem', { name: 'Se déconnecter', exact: true })
    .click();
  await expect(page).toHaveURL(atConnection);

  await expect(other).toHaveURL(atConnection);
  await expect(
    other.getByRole('heading', { name: 'Se connecter', exact: true }),
  ).toBeVisible();
  expect(requests).toContain('GET /api/auth/session');
  expect(requests).not.toContain('POST /api/auth/logout');
  expect(await marker(other)).toBe('kept');
});

test('la connexion dans un onglet ouvre l’application dans l’autre, sans recharger', async ({
  page,
  context,
  account,
}) => {
  await page.goto('/connexion');
  const other = await context.newPage();
  await other.goto('/connexion');
  await ready(page, 'Se connecter');
  await ready(other, 'Se connecter');
  await mark(other);

  await page
    .getByLabel('Email ou pseudo', { exact: true })
    .fill(account.username);
  await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page).toHaveURL(atHome);

  await expect(other).toHaveURL(atHome);
  await expect(
    other.getByRole('heading', { name: 'Carte', exact: true }),
  ).toBeVisible();
  await expect(
    other.getByRole('button', { name: 'Mon compte', exact: true }),
  ).toBeEnabled();
  expect(await marker(other)).toBe('kept');
});
