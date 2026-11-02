import { expect, type Page } from '@playwright/test';
import { test } from './account-fixtures';
import { expectAccessible } from './browser-fixtures';

// These tests sign in: traces would retain the password and the session cookies.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

const html = (page: Page) => page.locator('html');
const settings = (page: Page) =>
  page.getByRole('dialog', { name: 'Paramètres', exact: true });

async function signIn(
  page: Page,
  account: { username: string; password: string },
) {
  await page.goto('/connexion');
  await page
    .getByLabel('Email ou pseudo', { exact: true })
    .fill(account.username);
  await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Carte', exact: true }),
  ).toBeVisible();
}

async function openSettings(page: Page) {
  await page.getByRole('button', { name: 'Mon compte', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Paramètres', exact: true }).click();
  await expect(settings(page)).toBeVisible();
}

async function choose(page: Page, label: string) {
  await settings(page).getByRole('radio', { name: label, exact: true }).click();
}

test.describe('système clair', () => {
  test.use({ colorScheme: 'light' });

  test('« Sombre » s’applique tout de suite, puis dès le chargement, connexion comprise', async ({
    page,
    account,
  }) => {
    await signIn(page, account);
    await expect(html(page)).toHaveCSS('color-scheme', 'light');

    await openSettings(page);
    await choose(page, 'Sombre');
    await expect(html(page)).toHaveCSS('color-scheme', 'dark');
    await page.keyboard.press('Escape');
    await expect(settings(page)).toBeHidden();
    await expect(
      page.getByRole('button', { name: 'Mon compte', exact: true }),
    ).toBeFocused();

    await page.getByRole('button', { name: 'Mon compte', exact: true }).click();
    await page
      .getByRole('menuitem', { name: 'Se déconnecter', exact: true })
      .click();
    await expect(page).toHaveURL((url) => url.pathname === '/connexion');

    // Read when the <body> is inserted: a deferred or module script would come too late.
    await page.addInitScript(() => {
      new MutationObserver((records, observer) => {
        if (
          records.some((record) =>
            [...record.addedNodes].some((node) => node.nodeName === 'BODY'),
          )
        ) {
          Object.assign(window, {
            themeAtBody: document.documentElement.dataset.theme,
          });
          observer.disconnect();
        }
      }).observe(document, { childList: true, subtree: true });
    });
    await page.reload();
    await expect(
      page.getByRole('heading', { name: 'Se connecter', exact: true }),
    ).toBeVisible();
    expect(await page.evaluate(() => Reflect.get(window, 'themeAtBody'))).toBe(
      'dark',
    );
    await expect(html(page)).toHaveCSS('color-scheme', 'dark');
  });
});

test.describe('système sombre', () => {
  test.use({ colorScheme: 'dark' });

  test('« Clair » l’emporte sur le système, puis « Système » lui rend la main et suit ses changements', async ({
    page,
    account,
  }) => {
    await signIn(page, account);
    await openSettings(page);

    await choose(page, 'Clair');
    await expect(html(page)).toHaveCSS('color-scheme', 'light');

    await choose(page, 'Système');
    await expect(html(page)).toHaveCSS('color-scheme', 'dark');
    await page.emulateMedia({ colorScheme: 'light' });
    await expect(html(page)).toHaveCSS('color-scheme', 'light');
  });

  test('en sombre, la fenêtre « Paramètres » ouverte n’a aucune violation d’accessibilité', async ({
    page,
    account,
  }) => {
    await signIn(page, account);
    await openSettings(page);
    await expectAccessible(page);
  });

  test('dans la fenêtre « Paramètres », la flèche choisit le thème voisin', async ({
    page,
    account,
  }) => {
    await signIn(page, account);
    await openSettings(page);

    await settings(page)
      .getByRole('radio', { name: 'Système', exact: true })
      .focus();
    // Radix moves the focus in a setTimeout and checks only while the arrow is still down: press releases it too early.
    await page.keyboard.down('ArrowUp');
    await expect(
      settings(page).getByRole('radio', { name: 'Sombre', exact: true }),
    ).toBeChecked();
    await page.keyboard.up('ArrowUp');
    await expect(html(page)).toHaveAttribute('data-theme', 'dark');
  });
});
