import { randomUUID } from 'node:crypto';
import { expect, type Page } from '@playwright/test';
import {
  acceptTerms,
  csrfHeaders,
  openLink,
  recordRequests,
} from './browser-fixtures';
import { test as accountTest, usernameFor } from './account-fixtures';
import { freshPassword } from './passwords';

accountTest.use({ trace: 'off', screenshot: 'off', video: 'off' });
// The fake provider keeps one identity for the whole file: its journeys must not overlap.
accountTest.describe.configure({ mode: 'serial' });

const card = (page: Page) =>
  page.getByRole('heading', { name: 'Carte', exact: true });

async function provider(values: Record<string, unknown>) {
  const origin = process.env.E2E_GOOGLE_PROVIDER;
  if (!origin) throw new Error('Fournisseur Google fictif isolé requis.');
  const response = await fetch(`${origin}/fixture`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(values),
  });
  expect(response.status).toBe(200);
}
async function post(page: Page, path: string, data: Record<string, unknown>) {
  const origin = process.env.API_TARGET;
  if (!origin) throw new Error('API fictive isolée requise.');
  return page.request.post(`${origin}/api/auth/${path}`, {
    headers: await csrfHeaders(page.request, origin),
    data,
  });
}

const googleTest = accountTest.extend<{
  identity: { email: string; username: string; password: string };
}>({
  identity: [
    async ({ page, createdAccounts }, use) => {
      const id = randomUUID();
      const identity = {
        email: `${id}@gmail.com`,
        username: usernameFor(id),
        password: freshPassword(),
      };
      createdAccounts.track(identity.username, identity.email);
      await provider({
        email: identity.email,
        subject: id,
        verified: true,
        hd: null,
        cancel: false,
      });
      const requests = recordRequests(page);
      const navigation = await page.goto('/connexion');
      expect(navigation?.status()).toBe(200);
      await expect(
        page.getByRole('heading', { name: 'Se connecter', exact: true }),
      ).toBeVisible();
      expect(
        requests.filter((request) =>
          /^[A-Z]+ \/api\/auth\/(csrf|resume)$/.test(request),
        ),
      ).toEqual([]);
      await use(identity);
    },
    { auto: true, timeout: 60_000 },
  ],
});

googleTest(
  'inscription Google avec pseudo choisi, accès Carte et connexion après déconnexion',
  async ({ page, identity }) => {
    await page
      .getByRole('button', { name: 'Continuer avec Google', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Choisis ton pseudo', exact: true }),
    ).toBeVisible();
    await page.getByLabel('Pseudo', { exact: true }).fill(identity.username);
    await acceptTerms(page);
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();
    await expect(card(page)).toBeVisible();
    await page.getByRole('button', { name: 'Mon compte', exact: true }).click();
    await expect(
      page.getByText(identity.username, { exact: true }),
    ).toBeVisible();
    await page
      .getByRole('menuitem', { name: 'Se déconnecter', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Se connecter', exact: true }),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'Continuer avec Google', exact: true })
      .click();
    await expect(card(page)).toBeVisible();
  },
);

const localGoogleTest = googleTest.extend<{ owner: void }>({
  owner: [
    async ({ page, identity, mailbox }, use) => {
      const signup = await post(page, 'signup', {
        email: identity.email,
        password: identity.password,
        confirmation: identity.password,
        acceptedTerms: true,
        rememberMe: false,
      });
      expect(signup.status()).toBe(202);
      expect(
        (
          await post(page, 'signup/confirm', {
            code: await mailbox.code(identity.email),
            registration: signup.headers()['registration-id'],
          })
        ).status(),
      ).toBe(200);
      expect(
        (
          await post(page, 'username', { username: identity.username })
        ).status(),
      ).toBe(200);
      expect((await post(page, 'logout', {})).status()).toBe(204);
      await page.goto('/connexion');
      await expect(
        page.getByRole('heading', { name: 'Se connecter', exact: true }),
      ).toBeVisible();
      await use();
    },
    { auto: true, timeout: 60_000 },
  ],
});

localGoogleTest(
  'email égal exige le mot de passe puis la confirmation, et garde une autre session',
  async ({ page, browser, identity }) => {
    const other = await browser.newContext();
    try {
      const otherPage = await other.newPage();
      expect(
        (
          await post(otherPage, 'login', {
            identifier: identity.username,
            password: identity.password,
          })
        ).status(),
      ).toBe(200);
      await page
        .getByRole('button', { name: 'Continuer avec Google', exact: true })
        .click();
      await expect(
        page.getByRole('heading', { name: 'Retrouve ton compte', exact: true }),
      ).toBeVisible();
      await page
        .getByLabel('Email ou pseudo', { exact: true })
        .fill(identity.username);
      await page
        .getByLabel('Mot de passe', { exact: true })
        .fill(identity.password);
      await page
        .getByRole('button', { name: 'Vérifier mon compte', exact: true })
        .click();
      await expect(
        page.getByRole('button', {
          name: 'Confirmer l’association de Google',
          exact: true,
        }),
      ).toBeVisible();
      await expect(page.getByText(/Ton compte reste le même/)).toBeVisible();
      await page
        .getByRole('button', {
          name: 'Confirmer l’association de Google',
          exact: true,
        })
        .click();
      await expect(card(page)).toBeVisible();
      await page
        .getByRole('button', { name: 'Mon compte', exact: true })
        .click();
      await expect(
        page.getByText(identity.username, { exact: true }),
      ).toBeVisible();
      expect(
        (
          await otherPage.request.get(
            `${process.env.API_TARGET}/api/auth/session`,
          )
        ).status(),
      ).toBe(200);
    } finally {
      await other.close();
    }
  },
);

googleTest(
  'annulation Google contrôlée sans compte ni accès Carte',
  async ({ page }) => {
    await provider({ cancel: true });
    await page
      .getByRole('button', { name: 'Continuer avec Google', exact: true })
      .click();
    await expect(page.getByRole('alert')).toContainText('annulée');
    expect((await page.request.get('/api/auth/session')).status()).toBe(401);
    await expect(
      page.getByRole('button', { name: 'Continuer avec Google', exact: true }),
    ).toBeEnabled();
  },
);

googleTest(
  'le bouton Google est visible sur mobile avant toute saisie privée',
  async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const button = page.getByRole('button', {
      name: 'Continuer avec Google',
      exact: true,
    });
    await expect(button).toBeVisible();
    await expect(button).toBeInViewport();
    await page.screenshot({
      path: 'test-results/google-login-safe.png',
      fullPage: true,
    });
  },
);

googleTest(
  'email externe confirmé explicitement sans connexion avant reprise Google',
  async ({ page, identity, mailbox }) => {
    const email = `${randomUUID()}@example.invalid`;
    await provider({ email });
    await page
      .getByRole('button', { name: 'Continuer avec Google', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Confirme ton email', exact: true }),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'Envoyer le lien', exact: true })
      .click();
    await expect(
      page.getByRole('status').filter({ hasText: 'Consulte tes emails' }),
    ).toBeVisible();
    await openLink(
      page,
      '/confirmation-google',
      await mailbox.link(email, 'confirmation-google'),
    );
    expect((await page.request.get('/api/auth/session')).status()).toBe(401);
    await page
      .getByRole('button', { name: 'Confirmer mon email', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Adresse confirmée', exact: true }),
    ).toBeVisible();
    expect((await page.request.get('/api/auth/session')).status()).toBe(401);
    await page
      .getByRole('button', { name: 'Continuer avec Google', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Choisis ton pseudo', exact: true }),
    ).toBeVisible();
    await page.getByLabel('Pseudo', { exact: true }).fill(identity.username);
    await acceptTerms(page);
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();
    await expect(card(page)).toBeVisible();
  },
);
