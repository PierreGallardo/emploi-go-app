import { randomUUID } from 'node:crypto';
import { expect, type Page } from '@playwright/test';
import {
  RECONNECTION_COOKIE,
  acceptTerms,
  expectAccessible,
  reopened,
} from './browser-fixtures';
import { test, usernameFor } from './account-fixtures';
import { freshPassword } from './passwords';

// These flows carry email bearers and passwords; never retain a network trace.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });
const codeTitle = 'Vérifie tes emails';
const card = (page: Page) =>
  page.getByRole('heading', { name: 'Carte', exact: true });

async function fillSignup(page: Page, email: string, password: string) {
  await page.goto('/inscription');
  await submitSignupForm(page, email, password);
}
async function submitSignupForm(page: Page, email: string, password: string) {
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Mot de passe', { exact: true }).fill(password);
  await page
    .getByLabel('Confirmer le mot de passe', { exact: true })
    .fill(password);
  await acceptTerms(page);
  await page
    .getByLabel('Confirmer le mot de passe', { exact: true })
    .press('Enter');
}
async function signup(page: Page, email: string, password: string) {
  await fillSignup(page, email, password);
  await expect(page.getByRole('heading', { name: codeTitle })).toBeVisible();
}
const usernameTitle = 'Choisis ton pseudo';
async function chooseUsername(page: Page, username: string) {
  await expect(
    page.getByRole('heading', { name: usernameTitle }),
  ).toBeVisible();
  await page.getByLabel('Pseudo', { exact: true }).fill(username);
  await page.getByRole('button', { name: 'Continuer', exact: true }).click();
}
// The sixth digit sends the code: there is no button.
async function enterCode(page: Page, code: string) {
  await page
    .getByLabel('Code à 6 chiffres reçu par email', { exact: true })
    .fill(code);
}
// The same send delay applies to a second signup: it is submitted again until the API accepts it.
async function signupUntilAccepted(
  page: Page,
  email: string,
  password: string,
) {
  await expect
    .poll(
      async () => {
        const answered = page.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            response.url().endsWith('/api/auth/signup'),
        );
        await fillSignup(page, email, password);
        return (await answered).status();
      },
      { timeout: 10_000 },
    )
    .toBe(202);
}
test('le clavier parcourt le formulaire d’inscription sur mobile et ouvre la règle du mot de passe au passage', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/inscription');
  await expect(page.getByLabel('Email', { exact: true })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('link', { name: 'Emploi Go, accueil', exact: true }),
  ).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Email', { exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('button', { name: 'Règle du mot de passe', exact: true }),
  ).toBeFocused();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expectAccessible(page);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toBeHidden();
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Mot de passe', { exact: true })).toBeFocused();
});

test('sur ordinateur, passer de l’inscription au code garde la page et son panneau', async ({
  page,
  createdAccounts,
}) => {
  const id = randomUUID();
  const email = `${id}@example.invalid`;
  createdAccounts.track(usernameFor(id), email);
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/inscription');
  const panel = page.getByText(
    'Trouve sur une carte les entreprises de ton métier, près de chez toi.',
    {
      exact: true,
    },
  );
  await expect(panel).toBeVisible();
  const shown = await panel.elementHandle();
  await page.evaluate(() => {
    Object.assign(window, { sameDocument: true });
  });
  await submitSignupForm(page, email, freshPassword());
  await expect(page.getByRole('heading', { name: codeTitle })).toBeVisible();
  await expect(page).toHaveURL((url) => url.pathname === '/inscription/code');
  await expect
    .poll(() => page.evaluate(() => 'sameDocument' in window))
    .toBe(true);
  await expect
    .poll(() => shown!.evaluate((element) => element.isConnected))
    .toBe(true);
});

test('le retour du navigateur depuis le code rend le formulaire avec l’email sans le mot de passe, même après actualisation', async ({
  page,
  createdAccounts,
}) => {
  const id = randomUUID();
  const email = `${id}@example.invalid`;
  createdAccounts.track(usernameFor(id), email);
  await page.goto('/inscription');
  await submitSignupForm(page, email, freshPassword());
  await expect(page.getByRole('heading', { name: codeTitle })).toBeVisible();

  await page.goBack();
  await expect(page).toHaveURL((url) => url.pathname === '/inscription');
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue(email);
  await expect(page.getByLabel('Mot de passe', { exact: true })).toHaveValue(
    '',
  );

  await page.reload();
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue(email);
});

const rememberMe = (page: Page) =>
  page.getByRole('checkbox', { name: 'Rester connecté', exact: true });

test(
  'avec « Rester connecté » à l’inscription, le navigateur rouvert reste connecté après le code et le pseudo',
  { tag: '@webkit' },
  async ({ page, browser, context, baseURL, mailbox, createdAccounts }) => {
    const id = randomUUID();
    const email = `${id}@example.invalid`;
    const username = usernameFor(id);
    createdAccounts.track(username, email);
    await page.goto('/inscription');
    await rememberMe(page).check();
    await submitSignupForm(page, email, freshPassword());
    await expect(page.getByRole('heading', { name: codeTitle })).toBeVisible();
    await enterCode(page, await mailbox.code(email));
    await chooseUsername(page, username);
    await expect(card(page)).toBeVisible();

    const next = await reopened(browser, context, baseURL);
    try {
      await next.goto('/');

      await expect(card(next)).toBeVisible();
    } finally {
      await next.context().close();
    }
  },
);

test(
  'l’inscription mène de l’email au code puis au pseudo, et la session est conservée au rechargement',
  { tag: '@webkit' },
  async ({ page, context, mailbox, createdAccounts }) => {
    const id = randomUUID();
    const email = `${id}@example.invalid`;
    const username = usernameFor(id);
    createdAccounts.track(username, email);
    await page.setViewportSize({ width: 390, height: 844 });
    await signup(page, email, freshPassword());
    await expect(page).toHaveURL((url) => url.pathname === '/inscription/code');
    await expect(page.getByRole('heading', { name: codeTitle })).toBeFocused();
    await expect(page.getByText(`Email envoyé à ${email} ·`)).toBeVisible();
    expect(
      (await context.cookies()).map((cookie) => cookie.name),
    ).not.toContain(RECONNECTION_COOKIE);
    await expectAccessible(page);
    await enterCode(page, await mailbox.code(email));
    await expect(page).toHaveURL(
      (url) => url.pathname === '/inscription/pseudo',
    );
    await expect(
      page.getByRole('heading', { name: usernameTitle }),
    ).toBeFocused();
    await expectAccessible(page);
    await chooseUsername(page, username);
    await expect(card(page)).toBeVisible();
    await expect(page).toHaveURL((url) => url.pathname === '/');
    await page.reload();
    await expect(card(page)).toBeVisible();
  },
);

test('le rechargement de la page du code garde l’adresse et l’inscription de la session : le code confirme toujours', async ({
  page,
  mailbox,
  createdAccounts,
}) => {
  const id = randomUUID();
  const email = `${id}@example.invalid`;
  createdAccounts.track(usernameFor(id), email);
  await signup(page, email, freshPassword());
  const code = await mailbox.code(email);
  await page.reload();
  await expect(page.getByRole('heading', { name: codeTitle })).toBeVisible();
  await expect(page.getByText(email)).toBeVisible();
  await enterCode(page, code);
  await expect(
    page.getByRole('heading', { name: usernameTitle }),
  ).toBeVisible();
});

test('un compte sans pseudo qui se connecte choisit son pseudo avant d’entrer', async ({
  page,
  browser,
  baseURL,
  mailbox,
  createdAccounts,
}) => {
  const id = randomUUID();
  const email = `${id}@example.invalid`;
  const username = usernameFor(id);
  const password = freshPassword();
  createdAccounts.track(username, email);
  await signup(page, email, password);
  await enterCode(page, await mailbox.code(email));
  await expect(
    page.getByRole('heading', { name: usernameTitle }),
  ).toBeVisible();
  const other = await browser.newContext({ baseURL });
  try {
    const next = await other.newPage();
    await next.goto('/connexion');
    await next.getByLabel('Email ou pseudo', { exact: true }).fill(email);
    await next.getByLabel('Mot de passe', { exact: true }).fill(password);
    await next
      .getByRole('button', { name: 'Se connecter', exact: true })
      .click();
    await chooseUsername(next, username);
    await expect(card(next)).toBeVisible();
  } finally {
    await other.close();
  }
});

test('refaire l’inscription avec le même email envoie un nouveau code qui confirme', async ({
  page,
  mailbox,
  createdAccounts,
}) => {
  const id = randomUUID();
  const email = `${id}@example.invalid`;
  const username = usernameFor(id);
  createdAccounts.track(username, email);
  await signup(page, email, freshPassword());
  const first = await mailbox.code(email);
  await signupUntilAccepted(page, email, freshPassword());
  await enterCode(page, await mailbox.code(email, first));
  await expect(
    page.getByRole('heading', { name: usernameTitle }),
  ).toBeVisible();
});
