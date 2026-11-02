import {
  expect,
  test as base,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfoError,
} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { createMailbox, type Mailbox } from './mailbox';

// The E2E API runs over HTTP with the `local` profile: no `__Host-` prefix and no `Secure`, unlike production.
export const SESSION_COOKIE = 'EMPLOIGO_SESSION';
export const RECONNECTION_COOKIE = 'EMPLOIGO_RECONNECTION';
// Readable by the script of the page: the CSRF token, and the indicator that a reconnection is possible.
export const CSRF_COOKIE = 'XSRF-TOKEN';
export const RECONNECTABLE_COOKIE = 'EMPLOIGO_RECONNECTABLE';

export async function csrfHeaders(request: APIRequestContext, origin = '') {
  const response = await request.get(`${origin}/api/auth/csrf`);
  expect(response.status()).toBe(204);
  const { cookies } = await request.storageState();
  const token = cookies.find((cookie) => cookie.name === CSRF_COOKIE)?.value;
  if (!token) throw new Error('Cookie CSRF absent.');
  return { 'X-CSRF-TOKEN': token };
}

// A browser that is closed then opened again keeps only the cookies that have an expiry date.
export async function reopened(
  browser: Browser,
  context: BrowserContext,
  baseURL: string | undefined,
) {
  const { cookies, origins } = await context.storageState();
  const next = await browser.newContext({
    baseURL,
    storageState: {
      cookies: cookies.filter((cookie) => cookie.expires !== -1),
      origins,
    },
  });
  return next.newPage();
}

/** Axe reads the colours of the screen as it is: transitions that end first are part of that state, a looping one (a blinking caret) never ends. */
export async function expectAccessible(page: Page) {
  await page.evaluate(() =>
    Promise.allSettled(
      document
        .getAnimations()
        .filter(
          (animation) => animation.effect?.getTiming().iterations !== Infinity,
        )
        .map((animation) => animation.finished),
    ),
  );
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
}

/** Opens an e-mail link: the page loads, then receives the fragment, which the app removes from the address. */
export async function openLink(page: Page, path: string, fragment: string) {
  await page.goto(path);
  await expect(page.getByRole('main')).toBeVisible();
  await page.evaluate((value) => {
    window.location.hash = value;
  }, fragment);
  await expect.poll(() => new URL(page.url()).hash).toBe('');
}

/** Checks the box of the terms, required by every form that creates an account. */
export async function acceptTerms(page: Page) {
  await page
    .getByLabel(
      'J’accepte les conditions d’utilisation et la politique de confidentialité.',
      { exact: true },
    )
    .check();
}

/** Call it before the navigation: only the requests made from then on are recorded. */
export function recordRequests(page: Page) {
  const requests: string[] = [];
  page.on('request', (request) => {
    requests.push(`${request.method()} ${new URL(request.url()).pathname}`);
  });
  return requests;
}

// Browser preparation has its own bound; each journey keeps its 30 s budget.
export const test = base.extend<{
  privateErrorContext: void;
  mailbox: Mailbox;
}>({
  // eslint-disable-next-line no-empty-pattern
  mailbox: async ({}, use) => {
    await use(createMailbox());
  },
  privateErrorContext: [
    // Runs during fixture teardown, after hooks, before Playwright writes error-context.md.
    // eslint-disable-next-line no-empty-pattern
    async ({}, use, testInfo) => {
      await use();
      for (const error of testInfo.errors) removePageContext(error);
    },
    { auto: true },
  ],
  page: [
    async (
      {
        browser,
        contextOptions,
        baseURL,
        viewport,
        userAgent,
        deviceScaleFactor,
        isMobile,
        hasTouch,
        actionTimeout,
        navigationTimeout,
      },
      use,
    ) => {
      const context = await browser.newContext({
        ...contextOptions,
        baseURL,
        viewport,
        userAgent,
        deviceScaleFactor,
        isMobile,
        hasTouch,
      });
      context.setDefaultTimeout(actionTimeout);
      context.setDefaultNavigationTimeout(navigationTimeout);
      // The preview sends the policy of production: every journey of this fixture proves that nothing breaks it.
      const violations = await watchCspViolations(context);
      try {
        await use(await context.newPage());
      } finally {
        await context.close();
      }
      expect(violations, 'violations de la CSP').toEqual([]);
    },
    { scope: 'test', timeout: 60_000 },
  ],
  context: async ({ page }, use) => {
    await use(page.context());
  },
});

/** The violations of the policy in every page of the context: the event is the same in Chromium and WebKit, unlike the console text. */
export async function watchCspViolations(context: BrowserContext) {
  const violations: string[] = [];
  await context.exposeFunction('reportCspViolation', (violation: string) =>
    violations.push(violation),
  );
  await context.addInitScript(() =>
    document.addEventListener('securitypolicyviolation', (event) =>
      (
        window as unknown as { reportCspViolation: (v: string) => void }
      ).reportCspViolation(`${event.violatedDirective} ${event.blockedURI}`),
    ),
  );
  return violations;
}

function removePageContext(error: TestInfoError) {
  // Matcher snapshots can contain password values even when network tracing is disabled.
  delete error.errorContext;
  if (error.cause) removePageContext(error.cause);
}
