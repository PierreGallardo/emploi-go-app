import { expect } from '@playwright/test';
import { test, watchCspViolations } from './browser-fixtures';

// Its own context: the violation it provokes would fail the check of the `page` fixture.
test('la CSP servie bloque un script inline ajouté à la page @webkit', async ({
  browser,
  baseURL,
}) => {
  const context = await browser.newContext({ baseURL });
  const violations = await watchCspViolations(context);
  const page = await context.newPage();

  const response = await page.goto('/connexion');
  await expect(
    page.getByRole('textbox', { name: 'Email ou pseudo', exact: true }),
  ).toBeEditable();
  const ran = await page.evaluate(() => {
    const script = document.createElement('script');
    script.textContent = 'document.body.dataset.injected = "oui";';
    document.body.append(script);
    return document.body.dataset.injected === 'oui';
  });

  expect(response?.headers()['content-security-policy']).toMatch(
    /script-src 'self' 'sha256-/,
  );
  expect(ran).toBe(false);
  await expect
    .poll(() => violations)
    .toEqual([expect.stringMatching(/^script-src(-elem)? inline$/)]);
  await context.close();
});
