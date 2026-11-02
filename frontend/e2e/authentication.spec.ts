import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { test as browserTest } from './account-fixtures';
import {
  csrfHeaders,
  CSRF_COOKIE,
  expectAccessible,
  RECONNECTABLE_COOKIE,
  RECONNECTION_COOKIE,
  recordRequests,
  reopened,
  SESSION_COOKIE,
} from './browser-fixtures';

const test = browserTest.extend<{ anonymousReadyPage: Page }>({
  anonymousReadyPage: [
    async ({ page }, use) => {
      expect((await page.request.get('/api/ping')).status()).toBe(200);
      expect((await page.request.get('/api/auth/csrf')).status()).toBe(204);
      expect((await page.request.get('/api/auth/session')).status()).toBe(401);
      const browserSession = page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/auth/session',
      );
      const navigation = await page.goto('/');
      expect(navigation?.status()).toBe(200);
      expect((await browserSession).status()).toBe(401);
      await expect(
        page.getByRole('heading', { name: 'Se connecter', exact: true }),
      ).toBeVisible();
      await use(page);
    },
    { timeout: 60_000 },
  ],
});

// Network traces would retain passwords and HttpOnly reconnection cookies.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });
// URL predicates retain navigation checks without attaching a whole-page ARIA snapshot.

const atConnection = (url: URL) => /\/connexion$/.test(url.pathname);
const atHome = (url: URL) =>
  url.pathname === '/' && url.search === '' && url.hash === '';
const card = (page: Page) =>
  page.getByRole('heading', { name: 'Carte', exact: true });

// Signs in without the form: the cookies it sets are what the browser keeps.
async function signInByApi(
  page: Page,
  account: { username: string; password: string },
  rememberMe: boolean,
) {
  const login = await page.request.post('/api/auth/login', {
    headers: await csrfHeaders(page.request),
    data: {
      identifier: account.username,
      password: account.password,
      rememberMe,
    },
  });
  expect(login.status()).toBe(200);
}

async function signInWithForm(
  page: Page,
  account: { username: string; password: string },
) {
  await page
    .getByLabel('Email ou pseudo', { exact: true })
    .fill(account.username);
  await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page).toHaveURL(atHome);
  await expect(card(page)).toBeVisible();
}

test('le clavier parcourt l’accueil puis le formulaire de connexion dans l’ordre', async ({
  anonymousReadyPage: page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('link', { name: 'Emploi Go, accueil', exact: true }),
  ).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(
    page.getByLabel('Email ou pseudo', { exact: true }),
  ).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Mot de passe', { exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('button', {
      name: 'Afficher le mot de passe',
      exact: true,
    }),
  ).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('checkbox', { name: 'Rester connecté', exact: true }),
  ).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('button', { name: 'Se connecter', exact: true }),
  ).toBeFocused();
});

test('un mot de passe faux affiche une alerte, puis le bon ouvre la carte', async ({
  anonymousReadyPage: page,
  account,
}) => {
  await page
    .getByLabel('Email ou pseudo', { exact: true })
    .fill(account.email.toUpperCase());
  await page
    .getByLabel('Mot de passe', { exact: true })
    .fill('mot de passe fictif incorrect');
  const rejected = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith('/api/auth/login'),
  );
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  expect((await rejected).status()).toBe(401);
  await expect(page.getByRole('alert')).toContainText(
    'Identifiant ou mot de passe incorrect',
  );
  await expect(
    page.getByRole('button', { name: 'Se connecter', exact: true }),
  ).toBeEnabled();
  await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
  const connected = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith('/api/auth/login'),
  );
  await page.getByLabel('Mot de passe', { exact: true }).press('Enter');
  const loginResponse = await connected;
  expect(loginResponse.status()).toBe(200);
  expect((await loginResponse.json()).username).toBe(account.username);
  await expect(page).toHaveURL(atHome);
  await expect(card(page)).toBeVisible();
});

test(
  'sans « Rester connecté », fermer le navigateur déconnecte',
  { tag: '@webkit' },
  async ({ anonymousReadyPage: page, browser, context, baseURL, account }) => {
    await signInWithForm(page, account);
    const names = (await context.cookies()).map((cookie) => cookie.name);
    expect(names).not.toContain(RECONNECTION_COOKIE);
    expect(names).not.toContain(RECONNECTABLE_COOKIE);

    const next = await reopened(browser, context, baseURL);
    try {
      await next.goto('/');

      await expect(next).toHaveURL(atConnection);
    } finally {
      await next.context().close();
    }
  },
);

test(
  'avec « Rester connecté », le navigateur rouvert reste connecté',
  { tag: '@webkit' },
  async ({ anonymousReadyPage: page, browser, context, baseURL, account }) => {
    await page
      .getByRole('checkbox', { name: 'Rester connecté', exact: true })
      .check();
    await signInWithForm(page, account);

    const next = await reopened(browser, context, baseURL);
    try {
      await next.goto('/');

      await expect(card(next)).toBeVisible();
    } finally {
      await next.context().close();
    }
  },
);

test('avec « Rester connecté », la session reste ouverte au rechargement, même sans cookie de session', async ({
  anonymousReadyPage: page,
  context,
  account,
}) => {
  await signInByApi(page, account, true);
  await page.goto('/');
  await expect(card(page)).toBeVisible();
  await page.reload();
  await expect(card(page)).toBeVisible();
  await context.clearCookies({ name: SESSION_COOKIE });
  await page.reload();
  await expect(card(page)).toBeVisible();
});

test('la déconnexion efface les cookies de session, de reconnexion et le jeton CSRF et renvoie à la connexion', async ({
  anonymousReadyPage: page,
  context,
  account,
}) => {
  await signInByApi(page, account, true);
  await page.goto('/');
  const csrf = async () =>
    (await context.cookies()).find((cookie) => cookie.name === CSRF_COOKIE)
      ?.value;
  const before = await csrf();
  expect(before).toBeTruthy();
  await page.getByRole('button', { name: 'Mon compte', exact: true }).click();
  await expect(
    page.getByRole('menuitem', { name: 'Se déconnecter', exact: true }),
  ).toBeVisible();
  await expectAccessible(page);
  await page
    .getByRole('menuitem', { name: 'Se déconnecter', exact: true })
    .click();
  await expect(page).toHaveURL(atConnection);
  const names = (await context.cookies()).map((cookie) => cookie.name);
  expect(names).not.toContain(SESSION_COOKIE);
  expect(names).not.toContain(RECONNECTION_COOKIE);
  expect(names).not.toContain(RECONNECTABLE_COOKIE);
  expect(await csrf()).not.toBe(before);
  await page.goto('/');
  await expect(page).toHaveURL(atConnection);
});

test('le pseudo en majuscules ouvre la carte', async ({
  anonymousReadyPage: page,
  account,
}) => {
  await page
    .getByLabel('Email ou pseudo', { exact: true })
    .fill(account.username.toUpperCase());
  await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page).toHaveURL(atHome);
  await expect(card(page)).toBeVisible();
});

test(
  'le retour arrière du navigateur rend le focus à l’écran précédent',
  { tag: '@webkit' },
  async ({ anonymousReadyPage: page }) => {
    await page
      .getByRole('link', { name: 'Mot de passe oublié ?', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Mot de passe oublié', exact: true }),
    ).toBeFocused();
    await page
      .getByRole('link', { name: 'Revenir à la connexion', exact: true })
      .press('Enter');
    await expect(
      page.getByRole('heading', { name: 'Se connecter', exact: true }),
    ).toBeFocused();
    await page.goBack();
    await expect(
      page.getByRole('heading', { name: 'Mot de passe oublié', exact: true }),
    ).toBeFocused();
  },
);

test('un visiteur anonyme n’envoie aucune reprise et ne reçoit aucun cookie de session', async ({
  page,
  context,
}) => {
  const calls = recordRequests(page);
  const checked = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/auth/session',
  );
  await page.goto('/connexion');
  expect((await checked).status()).toBe(401);
  await expect(
    page.getByRole('heading', { name: 'Se connecter', exact: true }),
  ).toBeVisible();
  expect(calls).not.toContain('POST /api/auth/resume');
  expect(calls).not.toContain('GET /api/auth/csrf');
  const names = (await context.cookies()).map((cookie) => cookie.name);
  // The API gives every browser its CSRF cookie with the first response, even a 401, and nothing else.
  expect(names).toContain(CSRF_COOKIE);
  expect(names).not.toContain(SESSION_COOKIE);
  expect(names).not.toContain(RECONNECTION_COOKIE);
  expect(names).not.toContain(RECONNECTABLE_COOKIE);
});

test(
  'connexion et carte fictive accessibles sur mobile',
  { tag: '@webkit' },
  async ({ page, account }) => {
    const { password } = account;
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/connexion');
    await expect(
      page.getByRole('heading', { name: 'Se connecter', exact: true }),
    ).toBeVisible();
    await expectAccessible(page);
    await page
      .getByLabel('Email ou pseudo', { exact: true })
      .fill(account.username);
    await page.getByLabel('Mot de passe', { exact: true }).fill(password);
    await page
      .getByRole('button', { name: 'Se connecter', exact: true })
      .click();
    await expect(page).toHaveURL(atHome);
    await expect(card(page)).toBeVisible();
    await expectAccessible(page);
  },
);

test.describe('thème sombre', () => {
  test.use({ colorScheme: 'dark' });

  test('le thème sombre du système ne produit aucune violation d’accessibilité, de la connexion à la carte', async ({
    page,
    account,
  }) => {
    await page.goto('/connexion');
    await expect(
      page.getByRole('heading', { name: 'Se connecter', exact: true }),
    ).toBeVisible();
    await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark');
    await expectAccessible(page);
    await page
      .getByLabel('Email ou pseudo', { exact: true })
      .fill(account.username);
    await page
      .getByLabel('Mot de passe', { exact: true })
      .fill('mot de passe fictif incorrect');
    await page
      .getByRole('button', { name: 'Se connecter', exact: true })
      .click();
    await expect(page.getByRole('alert')).toContainText(
      'Identifiant ou mot de passe incorrect',
    );
    await expectAccessible(page);
    await page
      .getByLabel('Mot de passe', { exact: true })
      .fill(account.password);
    await page
      .getByRole('button', { name: 'Se connecter', exact: true })
      .click();
    await expect(page).toHaveURL(atHome);
    await expect(card(page)).toBeVisible();
    await expectAccessible(page);
  });
});

// The reconnection of `page` waits for `release`, in a cookie jar of its own: an APIRequestContext would otherwise update the browser's cookies early.
async function holdResume(page: Page, isolated: APIRequestContext) {
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let markRotated!: () => void;
  const rotated = new Promise<void>((resolve) => {
    markRotated = resolve;
  });
  await page.route('**/api/auth/resume', async (route) => {
    const response = await isolated.fetch(route.request());
    markRotated();
    await held;
    await route.fulfill({ response });
  });
  return { rotated, release };
}

test('la déconnexion dans un autre onglet attend la réponse de reprise puis révoque ses cookies', async ({
  page,
  context,
  playwright,
  account,
}) => {
  await page.goto('/connexion');
  await page
    .getByLabel('Email ou pseudo', { exact: true })
    .fill(account.username);
  await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
  await page
    .getByRole('checkbox', { name: 'Rester connecté', exact: true })
    .check();
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page).toHaveURL(atHome);
  const other = await context.newPage();
  await other.goto('/');
  // Enabled, not visible: the menu is disabled while the session is unknown, and a first read after the cookies are cleared would rotate the reconnection before `page` does.
  await expect(
    other.getByRole('button', { name: 'Mon compte', exact: true }),
  ).toBeEnabled();
  await context.clearCookies({ name: SESSION_COOKIE });
  const isolated = await playwright.request.newContext({
    storageState: await context.storageState(),
  });
  const { rotated, release } = await holdResume(page, isolated);
  try {
    const requests = recordRequests(other);
    await page.reload();
    await rotated;
    await other
      .getByRole('button', { name: 'Mon compte', exact: true })
      .click();
    await other
      .getByRole('menuitem', { name: 'Se déconnecter', exact: true })
      .click();
    await expect(
      other.getByRole('menuitem', { name: 'Déconnexion…', exact: true }),
    ).toBeVisible();
    expect(requests).not.toContain('POST /api/auth/logout');
    release();
    await expect(other).toHaveURL(atConnection);
    expect(requests).toContain('POST /api/auth/logout');
    expect(
      (await context.cookies()).map((cookie) => cookie.name),
    ).not.toContain(RECONNECTION_COOKIE);
  } finally {
    release();
    await page.unroute('**/api/auth/resume');
    await isolated.dispose();
  }
  await page.reload();
  await expect(page).toHaveURL(atConnection);
  await other.close();
});

[
  { page: '/connexion', title: 'Se connecter', submit: 'Se connecter' },
  {
    page: '/inscription',
    title: 'Créer un compte',
    submit: 'Créer mon compte',
  },
].forEach(({ page: path, title, submit }) => {
  [320, 390].forEach((width) => {
    test(`le squelette de ${title.toLowerCase()} a la taille du formulaire sur ${width} px`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 844 });
      let answer!: () => void;
      const answered = new Promise<void>((resolve) => {
        answer = resolve;
      });
      await page.route('**/api/auth/session', async (route) => {
        await answered;
        await route.continue();
      });
      await page.goto(path);
      await expect(page.getByRole('status')).toHaveText('Chargement…');
      const authCard = page.getByTestId('auth-card');
      await expect(authCard).toBeVisible();
      const skeleton = (await authCard.boundingBox())!;
      answer();
      await expect(
        page.getByRole('button', { name: submit, exact: true }),
      ).toBeVisible();
      const form = (await authCard.boundingBox())!;
      expect(form.width).toBe(skeleton.width);
      expect(Math.abs(form.height - skeleton.height)).toBeLessThanOrEqual(1);
    });
  });
});
