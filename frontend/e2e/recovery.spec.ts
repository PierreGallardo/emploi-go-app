import { randomUUID } from 'node:crypto';
import { expect, type Page } from '@playwright/test';
import {
  csrfHeaders,
  expectAccessible,
  openLink,
  RECONNECTION_COOKIE,
  recordRequests,
} from './browser-fixtures';
import { test as accountTest, usernameFor } from './account-fixtures';
import { freshPassword } from './passwords';

accountTest.use({ trace: 'off', screenshot: 'off', video: 'off' });
const card = (page: Page) =>
  page.getByRole('heading', { name: 'Carte', exact: true });

// The demand is sent through the API: its own form is what the first journey tests.
async function requestReset(page: Page, email: string) {
  const response = await page.request.post('/api/auth/password/forgot', {
    headers: await csrfHeaders(page.request),
    data: { email },
  });
  expect(response.status()).toBe(202);
}

const recoveryTest = accountTest.extend<{
  owner: { email: string; original: string; replacement: string };
}>({
  owner: [
    async ({ page, mailbox, createdAccounts }, use) => {
      const target = process.env.API_TARGET;
      if (!target) throw new Error('API E2E isolée requise.');
      const id = randomUUID();
      const email = `${id}@example.invalid`;
      createdAccounts.track(usernameFor(id), email);
      const original = freshPassword();
      const replacement = freshPassword();
      async function post(
        path: string,
        data: Record<string, string | boolean>,
      ) {
        return page.request.post(`${target}/api/auth/${path}`, {
          headers: await csrfHeaders(page.request, target),
          data,
        });
      }
      const signup = await post('signup', {
        email,
        password: original,
        confirmation: original,
        acceptedTerms: true,
        rememberMe: false,
      });
      expect(signup.status()).toBe(202);
      const confirmed = await post('signup/confirm', {
        code: await mailbox.code(email),
        registration: signup.headers()['registration-id']!,
      });
      expect(confirmed.status()).toBe(200);
      expect(
        (await post('username', { username: usernameFor(id) })).status(),
      ).toBe(200);
      // Recovery starts from a normal local login, not from the confirmation auto-login (tested apart).
      expect(
        (
          await post('login', { identifier: email, password: original })
        ).status(),
      ).toBe(200);
      expect((await page.request.get('/api/auth/session')).status()).toBe(200);
      const browserSession = page.waitForResponse(
        (response) =>
          response.url().endsWith('/api/auth/session') &&
          response.request().method() === 'GET',
      );
      const navigation = await page.goto('/');
      expect(navigation?.status()).toBe(200);
      expect((await browserSession).status()).toBe(200);
      await expect(card(page)).toBeVisible();
      await use({ email, original, replacement });
    },
    // Real API/CSRF/mailbox preparation is separate from the 30 s recovery journey.
    { timeout: 60_000 },
  ],
});

recoveryTest(
  'la demande de récupération au clavier mène à l’écran de vérification',
  async ({ context, owner: { email } }) => {
    const recovery = await context.newPage();
    await recovery.setViewportSize({ width: 390, height: 844 });
    await recovery.goto('/mot-de-passe-oublie');
    await expect(
      recovery.getByRole('heading', {
        name: 'Mot de passe oublié',
        exact: true,
      }),
    ).toBeVisible();
    await recovery.keyboard.press('Tab');
    await expect(
      recovery.getByRole('link', { name: 'Emploi Go, accueil', exact: true }),
    ).toBeFocused();
    await recovery.keyboard.press('Tab');
    await expect(recovery.getByLabel('Email', { exact: true })).toBeFocused();
    await expectAccessible(recovery);
    await recovery.getByLabel('Email', { exact: true }).fill(email);
    await recovery.getByLabel('Email', { exact: true }).press('Enter');
    await expect(
      recovery.getByRole('heading', {
        name: 'Vérifie tes emails',
        exact: true,
      }),
    ).toBeFocused();
  },
);

recoveryTest(
  'le lien de récupération ouvre le formulaire sans changer le mot de passe',
  async ({ page, context, mailbox, owner: { email } }) => {
    await requestReset(page, email);
    const recovery = await context.newPage();
    await recovery.setViewportSize({ width: 390, height: 844 });
    const calls = recordRequests(recovery);
    await openLink(
      recovery,
      '/reinitialiser-mot-de-passe',
      await mailbox.link(email, 'reinitialiser-mot-de-passe'),
    );
    const form = recovery.getByTestId('auth-card');
    await expect(
      form.getByLabel('Nouveau mot de passe', { exact: true }),
    ).toBeVisible();
    expect(calls).not.toContain('POST /api/auth/password/reset');
    await expectAccessible(recovery);
  },
);

recoveryTest(
  'la réinitialisation remplace le mot de passe, révoque la session ouverte et ouvre la connexion avec le nouveau',
  async ({
    page,
    context,
    mailbox,
    owner: { email, original, replacement },
  }) => {
    await requestReset(page, email);
    const recovery = await context.newPage();
    const calls = recordRequests(recovery);
    await openLink(
      recovery,
      '/reinitialiser-mot-de-passe',
      await mailbox.link(email, 'reinitialiser-mot-de-passe'),
    );
    const form = recovery.getByTestId('auth-card');
    await form
      .getByLabel('Nouveau mot de passe', { exact: true })
      .fill(replacement);
    await form
      .getByLabel('Confirmer le nouveau mot de passe', { exact: true })
      .fill(replacement);
    await form
      .getByLabel('Confirmer le nouveau mot de passe', { exact: true })
      .press('Enter');
    await expect(
      recovery.getByRole('heading', {
        name: 'Mot de passe changé',
        exact: true,
      }),
    ).toBeVisible();
    expect(
      calls.filter((call) => call === 'POST /api/auth/password/reset'),
    ).toHaveLength(1);
    expect(
      (await context.cookies()).map((cookie) => cookie.name),
    ).not.toContain(RECONNECTION_COOKIE);
    await page.reload();
    await expect(
      page.getByRole('heading', { name: 'Se connecter', exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Se connecter', exact: true }),
    ).toBeEnabled();
    await page.getByLabel('Email ou pseudo', { exact: true }).fill(email);
    await page.getByLabel('Mot de passe', { exact: true }).fill(original);
    const refused = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith('/api/auth/login'),
    );
    await page.getByLabel('Mot de passe', { exact: true }).press('Enter');
    expect((await refused).status()).toBe(401);
    await expect(page.getByRole('alert')).toContainText(
      'Identifiant ou mot de passe incorrect',
    );
    await expect(
      page.getByRole('button', { name: 'Se connecter', exact: true }),
    ).toBeEnabled();
    await page.getByLabel('Mot de passe', { exact: true }).fill(replacement);
    await page.getByLabel('Mot de passe', { exact: true }).press('Enter');
    await expect(card(page)).toBeVisible();
  },
);

accountTest(
  'le lien « Mot de passe oublié ? » de la connexion ouvre la récupération',
  async ({ page }) => {
    await page.goto('/connexion');
    await page
      .getByRole('link', { name: 'Mot de passe oublié ?', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Mot de passe oublié', exact: true }),
    ).toBeVisible();
  },
);

accountTest(
  'un lien de réinitialisation absent affiche une alerte et propose une nouvelle demande',
  async ({ page }) => {
    await page.goto('/reinitialiser-mot-de-passe');
    await expect(page.getByRole('alert')).toContainText('Ce lien est invalide');
    await expect(
      page.getByRole('link', { name: 'Demander un nouveau lien', exact: true }),
    ).toHaveAttribute('href', '/mot-de-passe-oublie');
  },
);
