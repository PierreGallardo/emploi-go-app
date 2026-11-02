import { expect } from '@playwright/test';
import {
  RECONNECTABLE_COOKIE,
  expectAccessible,
  test,
} from './browser-fixtures';

// One journey sets a cookie: nothing is recorded.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

const prerendered = [
  { path: '/connexion', heading: 'Se connecter', field: 'Email ou pseudo' },
  { path: '/inscription', heading: 'Créer un compte', field: 'Email' },
  {
    path: '/mot-de-passe-oublie',
    heading: 'Mot de passe oublié',
    field: 'Email',
  },
];

test.describe('sans JavaScript', () => {
  test.use({ javaScriptEnabled: false });

  for (const { path, heading } of prerendered)
    test(`${path} affiche son écran dès le HTML`, async ({ page }) => {
      await page.goto(path);

      await expect(
        page.getByRole('heading', { name: heading, exact: true }),
      ).toBeVisible();
    });

  test('un formulaire prérendu ne peut pas être envoyé avant le JavaScript', async ({
    page,
  }) => {
    await page.goto('/mot-de-passe-oublie');

    // Sent by the browser itself, the form would put the email in the URL of a GET.
    await expect(
      page.getByRole('button', { name: 'Envoyer la demande', exact: true }),
    ).toBeDisabled();
  });
});

test('une route non prérendue affiche un état de chargement accessible', async ({
  page,
}) => {
  // Without the scripts of the application, the fallback stays, while axe can still run.
  await page.route('/assets/*.js', (route) => route.abort());

  await page.goto('/reinitialiser-mot-de-passe');

  await expect(
    page.getByRole('heading', { name: 'Chargement…', exact: true }),
  ).toBeVisible();
  await expectAccessible(page);
});

for (const { path, field } of prerendered)
  test(`${path} devient interactive sans erreur d’hydratation`, async ({
    page,
  }) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => {
      // The anonymous session check answers 401, which the browser logs as a failed resource.
      if (
        message.type() === 'error' &&
        !message.text().startsWith('Failed to load resource')
      )
        errors.push(message.text());
    });

    await page.goto(path);

    await expect(
      page.getByRole('textbox', { name: field, exact: true }),
    ).toBeEditable();
    expect(errors).toEqual([]);
  });

test('un visiteur anonyme ne télécharge pas la carte', async ({ page }) => {
  const mapRequests: string[] = [];
  page.on('request', (request) => {
    const { pathname } = new URL(request.url());
    if (pathname.startsWith('/cartes/') || pathname.includes('/map-engine-'))
      mapRequests.push(pathname);
  });

  await page.goto('/');

  await expect(
    page.getByRole('heading', { name: 'Se connecter', exact: true }),
  ).toBeVisible();
  expect(mapRequests).toEqual([]);
});

test('avec l’indicateur de reconnexion, la carte est demandée pendant la vérification de session', async ({
  page,
  context,
  baseURL,
}) => {
  await context.addCookies([
    { name: RECONNECTABLE_COOKIE, value: '1', url: baseURL! },
  ]);
  // The check never answers: the map can only be asked for before its end.
  await page.route('/api/auth/session', () => undefined);
  const style = page.waitForRequest((request) =>
    new URL(request.url()).pathname.endsWith('/style.json'),
  );
  const engine = page.waitForRequest((request) =>
    new URL(request.url()).pathname.includes('/map-engine-'),
  );

  await page.goto('/');

  await Promise.all([style, engine]);
});
